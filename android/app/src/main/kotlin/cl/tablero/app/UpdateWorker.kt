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
 * A manual update, one of two kinds. With no stores named: sync, i.e. the
 * catalogue the scrape workflow publishes, swapped in with the user's own
 * tables kept. With stores named: those scraped here on the phone, for the
 * ones the workflow cannot reach (Cloudflare blocks its servers on some).
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
        val wanted = inputData.getNullableStringArray(KEY_STORES)?.filterNotNull().orEmpty()
        val notes = mutableListOf<String>()

        val synced = if (wanted.isNotEmpty()) null else sync(db, notes)
        val targets = sites().filter { it.name in wanted }
        val scraped = if (targets.isEmpty()) null else scrape(db, targets, notes)

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

        val newCount = db.read { it.query("SELECT COUNT(*) AS n FROM product WHERE flag = 'new'").first()["n"] as Long }
        return Result.success(workDataOf(
            KEY_PRODUCTS to db.read(::productCount), KEY_NEW to newCount.toInt(),
            KEY_FAILED to notes.toTypedArray(), KEY_TOTAL to targets.size,
        ))
    }

    /** The published catalogue, swapped in. Null when it was already here or could not be fetched. */
    private suspend fun sync(db: Db, notes: MutableList<String>): Db.Synced? {
        setProgress(workDataOf(KEY_DONE to 0, KEY_TOTAL to 0, KEY_STORE to "catálogo"))
        val synced = try {
            withContext(Dispatchers.IO) { db.sync(openCatalog(), applicationContext.cacheDir) }
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
     * Scrape `targets` on the phone, biggest first so a long catalogue does
     * not start last and finish alone. Can run long, so as a foreground
     * service of type dataSync with a progress notification.
     */
    private suspend fun scrape(db: Db, targets: List<Site>, notes: MutableList<String>): Int {
        val size = db.read { conn -> stores(conn).associate { it["name"] as String to (it["n_products"] as Long? ?: 0L) } }
        val ordered = targets.sortedByDescending { size[it.name] ?: 0L }
        setForeground(foreground("Actualizando ${ordered.size} tiendas…", 0, ordered.size))
        // Named up front when there is only one, since nothing else reports
        // until that store is done.
        setProgress(workDataOf(KEY_DONE to 0, KEY_TOTAL to ordered.size, KEY_STORE to ordered.singleOrNull()?.name))

        val http = Http()
        // updateStores blocks. runInterruptible turns a cancellation (Detener)
        // into a thread interrupt, which stops its scrapes mid-page.
        val totals = runInterruptible(Dispatchers.IO) { updateStores(
            targets = ordered,
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
        const val KEY_STORES = "stores"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_PRODUCTS = "products"
        const val KEY_STORE = "store"
        const val KEY_NEW = "new"
        const val KEY_FAILED = "failed"
        /** A sync that found the catalogue already here. */
        const val KEY_CURRENT = "current"
        private const val NOTIFICATION_ID = 1

        /**
         * Queue an update: the catalogue sync, or, when `stores` is not
         * empty, just those scraped on the phone.
         * WiFi only unless `mobileData`. One at a time; asking again while
         * one runs is a no-op.
         */
        fun start(context: Context, stores: List<String> = emptyList(),
                  mobileData: Boolean = !(context.applicationContext as TableroApp).prefs.wifiOnly.value) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setInputData(workDataOf(KEY_STORES to stores.toTypedArray()))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (mobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
