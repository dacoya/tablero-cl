package cl.tablero.app

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.sqlite.SQLiteConnection
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import cl.tablero.app.ui.feedFile
import cl.tablero.app.ui.saveFeed
import cl.tablero.core.data.CATALOG_HTTP_CURSOR
import cl.tablero.core.data.getCursor
import cl.tablero.core.data.ingestStore
import cl.tablero.core.data.openCatalog
import cl.tablero.core.data.query
import cl.tablero.core.data.recordFailure
import cl.tablero.core.data.sinceLastReview
import cl.tablero.core.data.stores
import cl.tablero.core.data.watchAlerts
import cl.tablero.core.scrape.Http
import cl.tablero.core.scrape.Site
import cl.tablero.core.scrape.crawl
import cl.tablero.core.scrape.sites
import cl.tablero.core.scrape.updateStores

/**
 * A manual update, one of two kinds. With no store named: sync, i.e. the
 * catalogue the scrape workflow publishes, swapped in with the user's own
 * tables kept. With a store named: that one store scraped here on the phone,
 * for the ones the workflow cannot reach (Cloudflare blocks its servers on
 * some). The phone never scrapes every store.
 *
 * ponytail: the next sync replaces phone-scraped rows with the workflow's
 * stale ones, losing what the phone recorded for those stores. Carry their
 * product/price_obs across in carryUserState if that starts to matter.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // One update at a time, and never during an import.
        val db = (applicationContext as TableroApp).db
        if (!db.busy.tryLock()) return Result.success(workDataOf(KEY_SKIPPED to true))
        try {
            return update()
        } finally {
            db.busy.unlock()
        }
    }

    private suspend fun update(): Result {
        val app = applicationContext as TableroApp
        val db = app.db
        val wanted = inputData.getString(KEY_TARGET)
        val notes = mutableListOf<String>()

        val synced = if (wanted != null) null else sync(db, notes)
        val site = wanted?.let { name -> sites().firstOrNull { it.name == name } }
        val scraped = site?.let { scrape(db, it, notes) }

        if (synced == null && scraped == null) {
            // Nothing new: the catalogue was already current.
            return if (notes.isEmpty()) Result.success(workDataOf(KEY_CURRENT to true))
                else Result.failure(workDataOf(KEY_FAILED to notes.toTypedArray()))
        }

        // Home reads this instead of querying, so it is built once per run.
        db.read { saveFeed(feedFile(applicationContext), it) }
        db.updated()

        // What the run means for the user, now that the data is in.
        val hits = db.read { conn -> watchAlerts(conn).map { it["matched_title"] as String to it["price"] as Double } }
        Notify.targets(applicationContext, app.prefs, hits)
        val changed = db.read { conn ->
            if (getCursor(conn) == null) 0 else sinceLastReview(conn, limit = null).size
        }
        Notify.changes(applicationContext, app.prefs, changed)

        val brokenStores = db.read { conn -> stores(conn).count { it["last_success"] == 0L } }
        val newCount = db.read { it.query("SELECT COUNT(*) AS n FROM product WHERE flag = 'new'").first()["n"] as Long }
        return Result.success(workDataOf(
            KEY_PRODUCTS to db.read(::productCount), KEY_NEW to newCount.toInt(),
            KEY_FAILED to notes.toTypedArray(), KEY_TOTAL to if (site == null) 0 else 1,
            KEY_BROKEN_STORES to brokenStores,
        ))
    }

    /** The published catalogue, swapped in. Null when it was already here or could not be fetched. */
    private suspend fun sync(db: Db, notes: MutableList<String>): Db.Synced? {
        setProgress(workDataOf(KEY_DONE to 0, KEY_TOTAL to 0, KEY_STORE to "catálogo"))
        val since = db.read { getCursor(it, CATALOG_HTTP_CURSOR) }
        val synced = try {
            withContext(Dispatchers.IO) {
                // Unchanged since the last fetch: nothing downloaded.
                openCatalog(since)?.let { db.sync(it, applicationContext.cacheDir) }
            }
        } catch (e: Exception) {
            notes += "catálogo: ${e.message}"
            return null
        }
        if (synced != null && synced.dropped > 0) {
            notes += "${synced.dropped} juegos de tu lista, colección o carrito ya no están en el catálogo"
        }
        return synced
    }

    /**
     * Scrape one store on the phone. A big catalogue can take minutes, so as
     * a foreground service of type dataSync with a progress notification.
     */
    private suspend fun scrape(db: Db, site: Site, notes: MutableList<String>): Int {
        setForeground(foreground("Actualizando ${site.name}…", 0, 1))
        setProgress(workDataOf(KEY_DONE to 0, KEY_TOTAL to 1, KEY_STORE to site.name))

        val http = Http()
        // updateStores blocks. runInterruptible turns a cancellation (Detener)
        // into a thread interrupt, which stops its scrapes mid-page.
        val totals = runInterruptible(Dispatchers.IO) { updateStores(
            targets = listOf(site),
            ingest = { store, records -> db.writeBlocking { ingestStore(it, store, records) } },
            recordFailure = { store -> db.writeBlocking { recordFailure(it, store) } },
            scrape = { crawl(it, http::fetchHtml) },
            onFailure = { store, reason -> notes += "$store: $reason" },
            onProgress = { done, total, products, store ->
                setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total,
                    KEY_PRODUCTS to products, KEY_STORE to store))
                setForegroundAsync(foreground("$store · $products productos", done, total))
            },
        ) }
        return totals.ingested
    }

    private fun foreground(text: String, done: Int, total: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, TableroApp.UPDATE_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Tablero")
            .setContentText(text)
            .setProgress(total, done, false)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun productCount(conn: SQLiteConnection): Int =
        (conn.query("SELECT COUNT(*) AS n FROM product").first()["n"] as Long).toInt()

    companion object {
        const val WORK_NAME = "update"
        const val KEY_SKIPPED = "skipped"
        /** The one store to scrape on the phone; absent for a sync. */
        const val KEY_TARGET = "target"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_PRODUCTS = "products"
        const val KEY_STORE = "store"
        const val KEY_NEW = "new"
        const val KEY_FAILED = "failed"
        /** Stores whose last scrape failed, after the run. */
        const val KEY_BROKEN_STORES = "broken_stores"
        /** Tag of a catalogue sync, as opposed to one store scraped on the phone. */
        const val TAG_SYNC = "sync"
        /** A sync that found the catalogue already here. */
        const val KEY_CURRENT = "current"
        private const val NOTIFICATION_ID = 1

        /**
         * Queue an update: the catalogue sync, or, with `store`, that one
         * store scraped on the phone. Any network will do. One at a time;
         * asking again while one runs is a no-op.
         */
        fun start(context: Context, store: String? = null) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setInputData(workDataOf(KEY_TARGET to store))
                .apply { if (store == null) addTag(TAG_SYNC) }
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
