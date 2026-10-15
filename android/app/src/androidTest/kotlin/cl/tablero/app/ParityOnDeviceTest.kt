package cl.tablero.app

import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cl.tablero.core.parity.deriveMismatches
import cl.tablero.core.parity.ingestMismatches
import cl.tablero.core.parity.pageMismatches
import cl.tablero.core.parity.readGzipText
import cl.tablero.core.scrape.PARSERS
import cl.tablero.core.parity.urlMismatches
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * core's parity checks on the device: regexes on Android's ICU engine, SQL on
 * the framework SQLite. The JVM run uses neither, so it cannot vouch for them.
 */
@RunWith(AndroidJUnit4::class)
class ParityOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun corpus(name: String): JsonElement =
        instrumentation.context.assets.open(name).bufferedReader().use { Json.parseToJsonElement(it.readText()) }

    private fun assertNone(mismatches: List<String>) =
        assertEquals("${mismatches.size} differ", "", mismatches.take(10).joinToString("\n"))

    @Test
    fun urls() = assertNone(urlMismatches(corpus("url_vectors.json")))

    @Test
    fun derive() {
        val vectors = corpus("derive_vectors.json")
        // An empty or missing asset would otherwise pass with nothing compared.
        assertTrue("corpus looks truncated", vectors.jsonArray.size > 1000)
        assertNone(deriveMismatches(vectors))
    }

    @Test
    fun pages() {
        val assets = instrumentation.context.assets
        // aapt gunzips .gz assets and drops the extension, so the fixtures
        // arrive as pageN.html; accept either in case that ever changes.
        val pages = assets.list("")!!.filter { it in PARSERS }
            .flatMap { store ->
                assets.list(store)!!.filter { it.endsWith(".html") || it.endsWith(".html.gz") }.map { "$store/$it" }
            }
        assertTrue("only ${pages.size} pages", pages.size >= 25)
        assertNone(pages.flatMap { path ->
            val html = assets.open(path).use { if (path.endsWith(".gz")) readGzipText(it) else it.reader().readText() }
            val expected = assets.open(path.substringBefore(".html") + ".expected.json").bufferedReader().use { it.readText() }
            pageMismatches(path, PARSERS.getValue(path.substringBefore('/')), html, expected)
        })
    }

    @Test
    fun ingestScenarioOnFrameworkSqlite() {
        val file = instrumentation.targetContext.cacheDir.resolve("scenario.db").apply { delete() }
        try {
            AndroidSQLiteDriver().open(file.path).use { conn ->
                assertNone(ingestMismatches(conn, corpus("ingest_scenario.json")))
            }
        } finally {
            file.delete()
        }
    }
}
