package cl.tablero.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cl.tablero.core.data.Scraped
import cl.tablero.core.data.ingestStore
import cl.tablero.core.data.initDb
import cl.tablero.core.data.query
import cl.tablero.core.data.recordFailure
import cl.tablero.core.scrape.Site
import cl.tablero.core.scrape.updateStores
import kotlin.test.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** update.update_stores: every store reported, failures recorded, data untouched. */
class UpdateTest {
    private fun site(name: String) = Site(name, "https://$name.cl", "page_param") { emptyList() }

    @Test
    fun reportsEveryStoreAndRecordsFailures() = BundledSQLiteDriver().open(":memory:").use { conn ->
        initDb(conn)
        val scraped = mapOf(
            "ok" to listOf(Scraped("Catan", "https://ok.cl/catan", "$40.000")),
            "empty" to emptyList(),
        )
        val progress = mutableListOf<Pair<Int, String>>()
        val failures = mutableListOf<String>()

        val totals = updateStores(
            targets = listOf(site("ok"), site("empty"), site("down")),
            ingest = { store, records -> ingestStore(conn, store, records, ts = 10) },
            recordFailure = { recordFailure(conn, it, ts = 10) },
            scrape = { scraped[it.name] ?: error("connection refused") },
            workers = 2,
            onFailure = { store, _ -> failures += store },
            onProgress = { done, total, _, store -> progress += done to store; assertEquals(3, total) },
        )

        assertEquals(listOf(1, 2, 3), progress.map { it.first })
        assertEquals(setOf("ok", "empty", "down"), progress.map { it.second }.toSet())
        assertEquals(setOf("empty", "down"), failures.toSet())
        assertEquals(1, totals.ingested)
        assertEquals(2, totals.failed)
        val runs = conn.query("SELECT store, success FROM scrape_run ORDER BY store")
            .associate { it["store"] to it["success"] }
        assertEquals(mapOf<Any?, Any?>("down" to 0L, "empty" to 0L, "ok" to 1L), runs)
    }

    @Test
    fun interruptingTheCallerStopsTheScrapes() {
        // What Detener relies on: the worker turns a cancel into an interrupt.
        val started = CountDownLatch(3)
        val interrupted = AtomicInteger()
        var thrown: Throwable? = null
        val caller = Thread {
            try {
                updateStores(
                    targets = (1..6).map { site("s$it") },
                    ingest = { _, _ -> error("no store may finish") },
                    recordFailure = { error("no failure may be recorded either") },
                    scrape = {
                        started.countDown()
                        try { Thread.sleep(30_000) } catch (e: InterruptedException) { interrupted.incrementAndGet(); throw e }
                        emptyList()
                    },
                    workers = 3,
                )
            } catch (e: Throwable) {
                thrown = e
            }
        }
        caller.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        caller.interrupt()
        caller.join(5_000)

        assertFalse(caller.isAlive, "updateStores ignored the interrupt")
        assertTrue(thrown is InterruptedException, "got $thrown")
        // The three running scrapes are interrupted; the three queued never start.
        val deadline = System.currentTimeMillis() + 5_000
        while (interrupted.get() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(3, interrupted.get())
    }
}
