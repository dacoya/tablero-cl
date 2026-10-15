package cl.tablero.core.scrape

import cl.tablero.core.data.Scraped
import org.jsoup.nodes.Document
import kotlin.random.Random

/*
 * Port of runner.scrape_site: walk one store's pages and collect its products.
 * Returns records; writing them is ingest's job.
 */

// Politeness delay between page requests. Jitter avoids a robotic cadence.
const val PAGE_DELAY_MIN = 1.0
const val PAGE_DELAY_JITTER = 1.5

fun politePause() = Thread.sleep(((PAGE_DELAY_MIN + Random.nextDouble(PAGE_DELAY_JITTER)) * 1000).toLong())

/**
 * Every page of one store, deduplicated on URL and sorted by it.
 *
 * `fetch` is injected so tests can replay saved pages; on the phone it is
 * [Http.fetchHtml]. A null page or a page that parses to nothing ends the
 * walk, and so does a page identical to the last one -- some stores serve the
 * final page again instead of a 404.
 */
fun crawl(
    site: Site,
    fetch: (String) -> Document?,
    dryRun: Boolean = false,
    pause: () -> Unit = ::politePause,
): List<Scraped> {
    val products = mutableListOf<Scraped>()
    var previousTitles: List<String?> = emptyList()
    var page = 1

    while (true) {
        val html = fetch(buildUrl(site.baseUrl, site.pagination, page)) ?: break
        val pageData = site.parser(html)
        if (pageData.isEmpty()) break

        val titles = pageData.map { it.title }
        if (titles == previousTitles) break

        previousTitles = titles
        products += pageData
        page++
        if (dryRun) break
        pause()
    }

    // A record with no URL has no identity -- it is a newsletter box or a
    // banner that matched the card selector. First record per URL wins.
    // ponytail: sortedBy compares UTF-16 units, Python sorts code points; they
    // differ only for astral characters, which store URLs do not contain.
    return products
        .filter { !it.url.isNullOrEmpty() }
        .distinctBy { it.url }
        .sortedBy { it.url }
}
