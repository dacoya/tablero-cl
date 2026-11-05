package cl.tablero.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cl.tablero.core.scrape.buildUrl
import cl.tablero.core.parity.deriveMismatches
import cl.tablero.core.parity.ingestMismatches
import cl.tablero.core.parity.urlMismatches
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Python-generated corpora, on the JVM. app/src/androidTest runs the same
 * checks on a device.
 */
class ParityTest {
    private fun assertNone(mismatches: List<String>, total: Int) =
        assertEquals("", mismatches.take(10).joinToString("\n"), "${mismatches.size} of $total differ")

    @Test
    fun urls() {
        val vectors = corpus("url_vectors.json")
        assertNone(urlMismatches(vectors), vectors.jsonArray.size)
    }

    @Test
    fun unknownPaginationFailsOnlyPastPageOne() {
        assertEquals("https://x.cl", buildUrl("https://x.cl", "nope", 1))
        assertFailsWith<IllegalArgumentException> { buildUrl("https://x.cl", "nope", 2) }
    }

    @Test
    fun derive() {
        val vectors = corpus("derive_vectors.json")
        assertTrue(vectors.jsonArray.size > 1000, "corpus looks truncated")
        assertNone(deriveMismatches(vectors), vectors.jsonArray.size)
    }

    @Test
    fun ingestScenario() {
        BundledSQLiteDriver().open(":memory:").use { conn ->
            assertNone(ingestMismatches(conn, corpus("ingest_scenario.json")), 1)
        }
    }
}
