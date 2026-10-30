package cl.tablero.core.scrape

import cl.tablero.core.data.IngestResult
import cl.tablero.core.data.Scraped
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

/*
 * Port of update.update_stores: scrape stores concurrently, write them one at
 * a time. The database side is passed in as two functions so the app can run
 * them on its single database thread while scrapes run on their own.
 */

/** Three on the phone against Python's five: mobile networks and Cloudflare both prefer fewer. */
const val DEFAULT_WORKERS = 3

data class UpdateTotals(
    val ingested: Int = 0,
    val new: Int = 0,
    val restock: Int = 0,
    val failed: Int = 0,
    val rejected: Map<String, Int> = emptyMap(),
)

/**
 * Scrape `targets` and write each result. An empty scrape counts as a failure
 * and leaves that store's data untouched, exactly as in Python.
 *
 * `onProgress(done, total, products, store)` fires once per finished store,
 * failures included; `store` is the one that just came back.
 */
fun updateStores(
    targets: List<Site>,
    ingest: (store: String, records: List<Scraped>) -> IngestResult,
    recordFailure: (store: String) -> Unit,
    scrape: (Site) -> List<Scraped>,
    workers: Int = DEFAULT_WORKERS,
    onFailure: (store: String, reason: String) -> Unit = { _, _ -> },
    onProgress: (done: Int, total: Int, products: Int, store: String) -> Unit = { _, _, _, _ -> },
): UpdateTotals {
    if (targets.isEmpty()) return UpdateTotals()
    val pool = Executors.newFixedThreadPool(workers)
    try {
        val done = ExecutorCompletionService<Pair<Site, Result<List<Scraped>>>>(pool)
        targets.forEach { site -> done.submit { site to runCatching { scrape(site) } } }

        var totals = UpdateTotals()
        repeat(targets.size) { i ->
            val (site, outcome) = done.take().get()
            try {
                val records = outcome.getOrElse { e ->
                    onFailure(site.name, e.toString())
                    recordFailure(site.name)
                    totals = totals.copy(failed = totals.failed + 1)
                    return@repeat
                }
                val result = ingest(site.name, records)
                if (result.skipped) {
                    onFailure(site.name, "sin productos (¿URL o parser roto?)")
                    recordFailure(site.name)
                    totals = totals.copy(failed = totals.failed + 1)
                    return@repeat
                }
                totals = totals.copy(
                    ingested = totals.ingested + result.ingested,
                    new = totals.new + result.new,
                    restock = totals.restock + result.restock,
                    rejected = (totals.rejected.keys + result.rejected.keys).associateWith {
                        (totals.rejected[it] ?: 0) + (result.rejected[it] ?: 0)
                    },
                )
            } finally {
                onProgress(i + 1, targets.size, totals.ingested, site.name)
            }
        }
        return totals
    } finally {
        pool.shutdownNow()
    }
}
