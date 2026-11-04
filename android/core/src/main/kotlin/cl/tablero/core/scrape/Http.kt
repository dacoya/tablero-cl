package cl.tablero.core.scrape

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.TimeUnit

/*
 * Port of scrape.py's HTTP layer: the shared session, its retry policy, and
 * fetch_html's rules for what counts as a page.
 */

// Same headers as the Python session. Standard scraping hygiene against
// trivial user-agent filtering, not a bot-detection bypass.
private val HEADERS = Headers.headersOf(
    "User-Agent",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
    "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language", "es-CL,es;q=0.9,en;q=0.8",
)

// Substrings of a full-page anti-bot interstitial, only trusted on a small page.
private val CHALLENGE_MARKERS = listOf(
    "just a moment", "checking your browser", "cf-chl-", "/cdn-cgi/challenge",
    "sucuri_cloudproxy", "captcha-delivery.com",
)
private const val CHALLENGE_MAX_LEN = 30_000

private const val TIMEOUT_SECONDS = 20L

/** scrape._MAX_PAGE_BYTES: real pages run 30-400 KB; past this it is not one. */
const val MAX_PAGE_BYTES = 10L * 1024 * 1024

/**
 * urllib3's `Retry(total=3, backoff_factor=1, status_forcelist=[429, 500, 502,
 * 503, 504], raise_on_status=False)`: up to three retries on those statuses
 * or a connection failure, sleeping 0 s, 2 s, 4 s, and Retry-After when the
 * server sends one. After the last retry the final response is returned as is.
 */
private class Retry(private val sleep: (Long) -> Unit) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var errors = 0
        while (true) {
            val response = try {
                chain.proceed(chain.request())
            } catch (e: IOException) {
                if (errors >= RETRIES) throw e
                null
            }
            if (response != null && (response.code !in RETRY_STATUS || errors >= RETRIES)) return response
            val retryAfter = response?.header("Retry-After")?.trim()?.toLongOrNull()
            response?.close()
            errors++
            sleep(retryAfter?.coerceAtMost(MAX_RETRY_AFTER_SECONDS)?.times(1000) ?: backoffMillis(errors))
        }
    }

    // urllib3: no sleep after the first error, then factor * 2^(n-1) seconds.
    private fun backoffMillis(errors: Int): Long = if (errors <= 1) 0 else (1L shl (errors - 1)) * 1000

    companion object {
        const val RETRIES = 3
        val RETRY_STATUS = setOf(429, 500, 502, 503, 504)

        // ponytail: urllib3 honours any Retry-After; capped here so one store
        // cannot park a phone worker. Raise it if a store needs longer.
        const val MAX_RETRY_AFTER_SECONDS = 60L
    }
}

/** requests.Session keeps cookies across a store's pages; OkHttp needs telling. */
private class MemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            this.cookies += c
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt < now }
        return cookies.filter { it.matches(url) }
    }
}

/**
 * One client for every store, like the Python module-level session.
 *
 * `log` receives the lines Python prints for a skipped page; the page itself
 * comes back as null either way.
 */
class Http(
    private val log: (String) -> Unit = {},
    sleep: (Long) -> Unit = Thread::sleep,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .cookieJar(MemoryCookieJar())
        .addInterceptor(Retry(sleep))
        .build()

    /** `text` is null when the body ran past MAX_PAGE_BYTES; it was never read whole. */
    private class Page(val code: Int, val cloudflare: Boolean, val text: String?)

    private fun read(r: Response): Page {
        // peekBody buffers at most the limit, so an endless or hostile body
        // costs MAX_PAGE_BYTES + 1 of memory at worst, never the whole stream.
        val body = r.peekBody(MAX_PAGE_BYTES + 1)
        val cloudflare = r.header("cf-ray") != null || "cloudflare" in (r.header("server") ?: "").lowercase()
        return Page(r.code, cloudflare, if (body.contentLength() > MAX_PAGE_BYTES) null else body.string())
    }

    /** A plain text file (a CSV), or null on any failure or a body past MAX_PAGE_BYTES. */
    fun fetchText(url: String): String? = try {
        val request = Request.Builder().url(url).headers(HEADERS).build()
        client.newCall(request).execute().use(::read).takeIf { it.code < 400 }?.text
    } catch (e: IOException) {
        log("Network error on $url: $e"); null
    } catch (e: IllegalArgumentException) {
        log("Network error on $url: $e"); null
    }

    /**
     * `fetch_html`: the parsed page, or null for anything that is not one --
     * a network failure, a Cloudflare block, an error status, a challenge
     * interstitial, or a 404 (the normal end of pagination, so not logged).
     *
     * ponytail: the body is decoded with the Content-Type charset, else UTF-8.
     * requests falls back to ISO-8859-1 for text/html without a charset; no
     * store relies on that today (no mojibake in the catalog).
     */
    fun fetchHtml(url: String): Document? {
        val page = try {
            val request = Request.Builder().url(url).headers(HEADERS).build()
            client.newCall(request).execute().use(::read)
        } catch (e: IOException) {
            log("Network error on $url: $e")
            return null
        } catch (e: IllegalArgumentException) {
            log("Network error on $url: $e")
            return null
        }

        if (page.code in setOf(403, 429, 503) && page.cloudflare) {
            log("  Cloudflare block (${page.code}) on $url — skipping.")
            return null
        }
        if (page.code == 404) return null
        if (page.code >= 400) {
            log("Network error on $url: HTTP ${page.code}")
            return null
        }
        val text = page.text ?: run {
            log("  Page too large on $url — skipping")
            return null
        }
        if (text.length < CHALLENGE_MAX_LEN) {
            val low = text.lowercase()
            if (CHALLENGE_MARKERS.any { it in low }) {
                log("  Anti-bot challenge detected on $url — skipping page (data left intact)")
                return null
            }
        }
        return Jsoup.parse(text, url)
    }
}
