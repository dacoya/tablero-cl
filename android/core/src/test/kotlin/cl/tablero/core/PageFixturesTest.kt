package cl.tablero.core

import cl.tablero.core.parity.pageMismatches
import cl.tablero.core.parity.readGzipText
import cl.tablero.core.scrape.PARSERS
import cl.tablero.core.scrape.registry
import cl.tablero.core.scrape.sites
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every saved store page whose parser is ported, against Python's records. */
class PageFixturesTest {
    private val pages = FIXTURES.listFiles()!!.filter { it.isDirectory }
        .flatMap { dir -> dir.listFiles()!!.filter { it.name.endsWith(".html.gz") } }
        .sortedBy { it.path }

    @Test
    fun portedParsersMatchPython() {
        val checked = pages.filter { it.parentFile.name in PARSERS }
        assertTrue(checked.size >= 25, "only ${checked.size} pages checked")
        val wrong = checked.flatMap { page ->
            val store = page.parentFile.name
            val expected = page.resolveSibling(page.name.replace(".html.gz", ".expected.json")).readText()
            pageMismatches("$store/${page.name}", PARSERS.getValue(store), readGzipText(page.inputStream()), expected)
        }
        assertEquals("", wrong.joinToString("\n"), "${wrong.size} pages differ")
    }

    @Test
    fun everyBindingNamesARegisteredStore() {
        val names = registry().map { it.first }.toSet()
        assertEquals(emptySet(), PARSERS.keys - names, "bound to a store scrape.py no longer has")
        assertEquals(PARSERS.size, sites().size)
    }

    @Test
    fun registryMatchesUrlVectors() {
        // sites.tsv and url_vectors.json are both written from scrape.sites.
        val bases = corpus("url_vectors.json").toString()
        // A store listing several URLs has a vector for the first.
        registry().forEach { (_, base, _) -> base.split(' ').first().let { assertTrue(it in bases, it) } }
    }
}
