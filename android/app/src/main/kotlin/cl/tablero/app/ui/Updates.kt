package cl.tablero.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cl.tablero.app.Schedule
import cl.tablero.app.TableroApp
import cl.tablero.app.UpdateWorker

/**
 * Manual and daily updates seen together, as Tiendas and Ajustes both need.
 * A periodic job sits ENQUEUED between runs, so only a RUNNING one counts.
 */
class Updates(manual: List<WorkInfo>, daily: List<WorkInfo>) {
    private val dailyRunning = daily.filter { it.state == WorkInfo.State.RUNNING }
    private val all = manual + dailyRunning

    /** What to show: a run still going first, else the last one that finished. */
    val shown: WorkInfo? = all.maxByOrNull { if (it.state.isFinished) 0 else 1 }

    /** A run queued (waiting for its network) or going. */
    val active: WorkInfo? = all.firstOrNull { !it.state.isFinished }

    /** A run actually scraping right now. */
    val running: Boolean = all.any { it.state == WorkInfo.State.RUNNING }

    /** Stop the manual run and today's daily one. The daily schedule stays. */
    fun stop(context: Context) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(UpdateWorker.WORK_NAME)
        dailyRunning.forEach { work.cancelWorkById(it.id) }
        // Cancelling a periodic job by id removes the schedule; put it back.
        if (dailyRunning.isNotEmpty()) Schedule.apply(context, (context.applicationContext as TableroApp).prefs)
    }
}

@Composable
fun rememberUpdates(): Updates {
    val context = LocalContext.current
    val work = remember { WorkManager.getInstance(context) }
    val manual by remember { work.getWorkInfosForUniqueWorkFlow(UpdateWorker.WORK_NAME) }.collectAsState(emptyList())
    val daily by remember { work.getWorkInfosForUniqueWorkFlow(Schedule.NAME) }.collectAsState(emptyList())
    return Updates(manual, daily)
}

/**
 * Starts an update of the named stores (empty = all), asking for the
 * notification permission on the way. Without it (Android 13+) the update
 * still runs; its progress and the price alerts just stay out of the shade.
 */
@Composable
fun rememberStartUpdate(): (List<String>) -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return { stores ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        UpdateWorker.start(context, stores)
    }
}
