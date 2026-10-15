package cl.tablero.app

import android.util.Log
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cl.tablero.core.data.ingestStore
import cl.tablero.core.data.initDb
import cl.tablero.core.scrape.Http
import cl.tablero.core.scrape.crawl
import cl.tablero.core.scrape.sites
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One real store scraped from the device and ingested into the framework
 * SQLite. Hits the network, so it only runs when asked:
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=cl.tablero.app.LiveScrapeTest \
 *     -Pandroid.testInstrumentationRunnerArguments.liveStore=laguaridadeldragon
 *
 * The product count is logged under the "tablero" tag, to compare with the
 * same store scraped by the Python tool.
 */
@RunWith(AndroidJUnit4::class)
class LiveScrapeTest {
    @Test
    fun scrapesAndIngestsOneStore() {
        // Not an Assume: AGP's report counts an assumption failure as a failure.
        val store = InstrumentationRegistry.getArguments().getString("liveStore")
        if (store == null) {
            Log.i("tablero", "LiveScrapeTest skipped: pass liveStore=<name> to run")
            return
        }

        val site = sites().single { it.name == store }
        val started = System.currentTimeMillis()
        val records = crawl(site, Http(log = { Log.w("tablero", it) })::fetchHtml)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.cacheDir.resolve("live.db").apply { delete() }
        val result = try {
            AndroidSQLiteDriver().open(file.path).use { conn ->
                initDb(conn)
                ingestStore(conn, site.name, records)
            }
        } finally {
            file.delete()
        }
        Log.i("tablero", "live $store: ${records.size} records, ${result.ingested} ingested, " +
            "rejected ${result.rejected}, ${(System.currentTimeMillis() - started) / 1000}s")
        assertTrue("nothing scraped from $store", result.ingested > 0)
    }
}
