package cl.tablero.app

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
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
import cl.tablero.core.data.getCursor
import cl.tablero.core.data.ingestStore
import cl.tablero.core.data.recordFailure
import cl.tablero.core.data.sinceLastReview
import cl.tablero.core.data.watchAlerts
import cl.tablero.core.scrape.Http
import cl.tablero.core.scrape.crawl
import cl.tablero.core.scrape.sites
import cl.tablero.core.scrape.updateStores

/**
 * Scrapes stores in the background. A full run is far past the ten minutes a
 * plain background job gets, so it runs as a foreground service of type
 * dataSync with a progress notification.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // One update at a time across the daily job and manual ones -- they
        // are separate WorkManager entries and would otherwise scrape the
        // same stores side by side -- and never during an import.
        val db = (applicationContext as TableroApp).db
        if (!db.busy.tryLock()) return Result.success(workDataOf(KEY_SKIPPED to true))
        try {
            return update()
        } finally {
            db.busy.unlock()
        }
    }

    private suspend fun update(): Result {
        val wanted = inputData.getNullableStringArray(KEY_STORES)?.filterNotNull()?.toSet()
        val targets = sites().filter { wanted.isNullOrEmpty() || it.name in wanted }
        val app = applicationContext as TableroApp
        val db = app.db
        val failures = mutableListOf<String>()
        setForeground(foreground("Actualizando ${targets.size} tiendas…", 0, targets.size))
        // Named up front when there is only one, since nothing else reports
        // until that store is done.
        setProgress(workDataOf(KEY_DONE to 0, KEY_TOTAL to targets.size,
            KEY_STORE to targets.singleOrNull()?.name))

        val http = Http()
        // updateStores blocks. runInterruptible turns a cancellation (Detener)
        // into a thread interrupt, which stops its scrapes mid-page; without
        // it the job showed as stopped while the scraping went on to the end.
        val totals = runInterruptible(Dispatchers.IO) { updateStores(
            targets = targets,
            ingest = { store, records -> db.writeBlocking { ingestStore(it, store, records) } },
            recordFailure = { store -> db.writeBlocking { recordFailure(it, store) } },
            scrape = { crawl(it, http::fetchHtml) },
            onFailure = { store, reason -> failures += "$store: $reason" },
            onProgress = { done, total, products, store ->
                setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total,
                    KEY_PRODUCTS to products, KEY_STORE to store))
                setForegroundAsync(foreground("$store · $products productos", done, total))
            },
        ) }
        db.updated()

        // What the run means for the user, now that the data is in.
        val hits = db.read { conn -> watchAlerts(conn).map { it["matched_title"] as String to it["price"] as Double } }
        Notify.targets(applicationContext, app.prefs, hits)
        val changed = db.read { conn ->
            if (getCursor(conn) == null) 0 else sinceLastReview(conn, limit = null).size
        }
        Notify.changes(applicationContext, app.prefs, changed)

        return Result.success(workDataOf(
            KEY_PRODUCTS to totals.ingested, KEY_NEW to totals.new,
            KEY_FAILED to failures.toTypedArray(), KEY_TOTAL to targets.size,
        ))
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

    companion object {
        const val WORK_NAME = "update"
        const val KEY_SKIPPED = "skipped"

        /** Both kinds of update, for whoever shows progress or cancels. */
        val ALL_WORK = listOf(WORK_NAME, Schedule.NAME)
        const val KEY_STORES = "stores"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_PRODUCTS = "products"
        const val KEY_STORE = "store"
        const val KEY_NEW = "new"
        const val KEY_FAILED = "failed"
        private const val NOTIFICATION_ID = 1

        /**
         * Queue an update of `stores` (empty = every store the phone can
         * scrape). WiFi only unless `mobileData`: a full run moves ~150 MB.
         * One at a time; asking again while one runs is a no-op.
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
