package cl.tablero.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cl.tablero.core.data.bggRanksUrls
import cl.tablero.core.data.refreshBggRanks
import cl.tablero.core.scrape.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneOffset

/** Downloads the latest BGG ranking and refreshes the ranks in the database. ~7 MB. */
class BggWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val db = (applicationContext as TableroApp).db
        val http = Http()
        val csv = withContext(Dispatchers.IO) {
            bggRanksUrls(LocalDate.now(ZoneOffset.UTC)).firstNotNullOfOrNull(http::fetchText)
        } ?: return Result.failure()
        return runCatching { db.write { refreshBggRanks(it, csv) } }
            .fold({ Result.success() }, { Result.failure() })
    }

    companion object {
        const val NAME = "bgg-ranks"
        const val NOW = "bgg-ranks-now"

        /** Download now, whatever the schedule says. */
        fun now(context: Context, mobileData: Boolean) {
            val request = OneTimeWorkRequestBuilder<BggWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (mobileData) NetworkType.CONNECTED else NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
