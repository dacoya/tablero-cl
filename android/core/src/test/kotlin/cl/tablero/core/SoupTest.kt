package cl.tablero.core

import cl.tablero.core.scrape.find
import cl.tablero.core.scrape.findAll
import cl.tablero.core.scrape.hasCls
import cl.tablero.core.scrape.txt
import cl.tablero.core.scrape.url
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * BeautifulSoup behaviours the parsers depend on, each measured against
 * bs4 4.13 with html.parser. The page fixtures happen not to exercise all of
 * them, so they are pinned here directly.
 */
class SoupTest {
    private fun body(html: String) = Jsoup.parse(html).body()

    @Test
    fun getTextStripsEachStringAndJoinsWithNothing() {
        // bs4: 'axbNz' -- script, style, template and comments skipped; noscript kept.
        val div = body(
            "<div>a <b> x </b><script>JS</script><style>CSS</style><!--c-->b" +
                "<template>T</template><noscript>N</noscript>&nbsp;z　</div>"
        ).child(0)
        assertEquals("axbNz", txt(div))
        assertEquals("\$29.990", txt(body("<p><span>\$29</span> <span>.990</span></p>").child(0)))
    }

    @Test
    fun classMatchingIsCaseSensitive() {
        val li = body("<ul><li class=\"Product outStock\">x</li></ul>").find("li")!!
        assertEquals(false, li.hasCls("product"))
        assertEquals(true, li.hasCls("outStock"))
        assertEquals(false, li.hasCls("outstock"))
    }

    @Test
    fun findSearchesDescendantsOnly() {
        val outer = body("<div class=\"p\"><div class=\"p\">inner</div></div>").child(0)
        assertEquals("inner", txt(outer.find("div", cls = "p")))
        assertEquals(1, outer.findAll("div", cls = "p").size)
    }

    @Test
    fun emptyHrefIsNoUrl() {
        val root = body("<a href=\"\">e</a><a href=\"/p/x\">r</a><a href=\"https://o.cl/y\">a</a>")
        val (empty, relative, absolute) = root.findAll("a")
        assertNull(url(empty, "https://b.cl"))
        assertEquals("https://b.cl/p/x", url(relative, "https://b.cl"))
        assertEquals("https://o.cl/y", url(absolute, "https://b.cl"))
    }
}
