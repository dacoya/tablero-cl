package cl.tablero.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** The daily background update, kept in step with the settings. */
object Schedule {
    const val NAME = "daily-update"

    // Overnight: the phone is usually charging and on WiFi, and the prices are
    // fresh by breakfast.
    private const val HOUR = 4

    /**
     * Delay until the next HOUR:00. Without it WorkManager runs a new periodic
     * job at once, so a fresh install started a full ~150 MB scrape the moment
     * it opened -- before there was any chance to import the laptop's copy.
     */
    private fun untilNextRun(now: Calendar = Calendar.getInstance()): Long {
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }

    fun apply(context: Context, prefs: Prefs) {
        val work = WorkManager.getInstance(context)
        if (!prefs.autoUpdate.value) {
            work.cancelUniqueWork(NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (prefs.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(prefs.chargingOnly.value)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setInitialDelay(untilNextRun(), TimeUnit.MILLISECONDS)
            .build()
        // UPDATE keeps an existing schedule's timing but takes new constraints.
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** The BGG ranking download, every `bggFrequency` days; independent of the store update. */
    fun applyBgg(context: Context, prefs: Prefs) {
        val request = PeriodicWorkRequestBuilder<BggWorker>(prefs.bggFrequency.days, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (prefs.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(untilNextRun(), TimeUnit.MILLISECONDS)
            .build()
        // UPDATE takes the new interval and constraints without restarting the cycle.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(BggWorker.NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
