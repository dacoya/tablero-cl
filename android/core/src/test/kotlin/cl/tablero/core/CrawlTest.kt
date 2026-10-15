package cl.tablero.core

import cl.tablero.core.data.Scraped
import cl.tablero.core.scrape.Site
import cl.tablero.core.scrape.crawl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.test.Test
import kotlin.test.assertEquals

/** runner.scrape_site's stopping and cleaning rules. */
class CrawlTest {
    // A page is a list of "title|url" lines; the parser reads them back.
    private fun page(vararg cards: String): Document = Jsoup.parse(cards.joinToString("") { "<p>$it</p>" })

    private val site = Site("t", "https://t.cl/c", "page_param") { html ->
        html.select("p").map { p ->
            val (title, url) = p.text().split("|")
            Scraped(title = title, url = url.ifEmpty { null })
        }
    }

    private fun serve(vararg pages: Document?): (String) -> Document? {
        val byUrl = pages.withIndex().associate { (i, p) ->
            (if (i == 0) "https://t.cl/c" else "https://t.cl/c?page=${i + 1}") to p
        }
        return { byUrl[it] }
    }

    @Test
    fun walksUntilThereIsNoPage() {
        val got = crawl(site, serve(page("A|u1"), page("B|u2")), pause = {})
        assertEquals(listOf("u1", "u2"), got.map { it.url })
    }

    @Test
    fun stopsWhenAPageRepeats() {
        val got = crawl(site, serve(page("A|u1"), page("A|u1"), page("C|u3")), pause = {})
        assertEquals(listOf("u1"), got.map { it.url })
    }

    @Test
    fun stopsAtAnEmptyPage() {
        val got = crawl(site, serve(page("A|u1"), page(), page("C|u3")), pause = {})
        assertEquals(listOf("u1"), got.map { it.url })
    }

    @Test
    fun dropsUrlLessKeepsFirstPerUrlAndSorts() {
        val got = crawl(site, serve(page("Z|u9", "Newsletter|", "First|u1"), page("Second|u1")), pause = {})
        assertEquals(listOf("u1" to "First", "u9" to "Z"), got.map { it.url to it.title })
    }

    @Test
    fun dryRunReadsOnePage() {
        var pauses = 0
        val got = crawl(site, serve(page("A|u1"), page("B|u2")), dryRun = true, pause = { pauses++ })
        assertEquals(listOf("u1"), got.map { it.url })
        assertEquals(0, pauses)
    }
}
