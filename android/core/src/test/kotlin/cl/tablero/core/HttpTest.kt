package cl.tablero.core

import cl.tablero.core.scrape.Http
import cl.tablero.core.scrape.MAX_PAGE_BYTES
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** fetch_html's rules for what counts as a page, and the retry policy. */
class HttpTest {
    private val server = MockWebServer()
    private val logged = mutableListOf<String>()
    private val slept = mutableListOf<Long>()
    private val http = Http(log = { logged += it }, sleep = { slept += it })

    private val bigPage = "<html><body>" + "<p>x</p>".repeat(5000) + "</body></html>"

    @BeforeTest fun start() = server.start()
    @AfterTest fun stop() = server.close()

    private fun reply(code: Int, body: String = bigPage, vararg headers: Pair<String, String>) {
        val b = MockResponse.Builder().code(code).body(body)
        headers.forEach { (k, v) -> b.addHeader(k, v) }
        server.enqueue(b.build())
    }

    private fun fetch() = http.fetchHtml(server.url("/c").toString())

    @Test
    fun pageParsesAndSendsTheBrowserHeaders() {
        reply(200)
        assertNotNull(fetch())
        val request = server.takeRequest()
        assertTrue(request.headers["User-Agent"]!!.startsWith("Mozilla/5.0"))
        assertEquals("es-CL,es;q=0.9,en;q=0.8", request.headers["Accept-Language"])
    }

    @Test
    fun notFoundEndsPaginationQuietly() {
        reply(404)
        assertNull(fetch())
        assertEquals(emptyList(), logged)
    }

    @Test
    fun otherErrorsAreLogged() {
        reply(410)
        assertNull(fetch())
        assertEquals(1, logged.size)
    }

    @Test
    fun cloudflareBlockIsSkippedWithoutRetrying() {
        reply(403, headers = arrayOf("cf-ray" to "abc"))
        assertNull(fetch())
        assertTrue("Cloudflare" in logged.single())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun smallChallengePageIsNotAPage() {
        reply(200, "<html><title>Just a moment...</title></html>")
        assertNull(fetch())
        assertTrue("challenge" in logged.single())
    }

    @Test
    fun largePageMentioningACaptchaIsStillAPage() {
        reply(200, bigPage + "<script src='https://captcha-delivery.com/x.js'></script>")
        assertNotNull(fetch())
    }

    @Test
    fun oversizedPageIsNotReadWhole() {
        val big = Buffer().write(ByteArray((MAX_PAGE_BYTES + 1).toInt()) { 'x'.code.toByte() })
        server.enqueue(MockResponse.Builder().code(200).body(big).build())
        assertNull(fetch())
        assertTrue("too large" in logged.single())
    }

    @Test
    fun pageJustUnderTheCapIsStillAPage() {
        val body = "<html><body>" + "x".repeat((MAX_PAGE_BYTES - 100).toInt()) + "</body></html>"
        reply(200, body)
        assertNotNull(fetch())
    }

    @Test
    fun retriesServerErrorsWithUrllib3Backoff() {
        reply(503); reply(502); reply(500); reply(200)
        assertNotNull(fetch())
        assertEquals(4, server.requestCount)
        assertEquals(listOf(0L, 2000L, 4000L), slept)
    }

    @Test
    fun givesUpAfterThreeRetriesAndReturnsTheLastResponse() {
        repeat(4) { reply(500) }
        assertNull(fetch())
        assertEquals(4, server.requestCount)
    }

    @Test
    fun honoursRetryAfter() {
        reply(429, headers = arrayOf("Retry-After" to "7")); reply(200)
        assertNotNull(fetch())
        assertEquals(listOf(7000L), slept)
    }
}
