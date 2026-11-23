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
import cl.tablero.app.UpdateWorker

/** The update in progress or last finished: a catalogue sync or one store's scrape. */
class Updates(runs: List<WorkInfo>) {
    /** What to show: a run still going first, else the last one that finished. */
    val shown: WorkInfo? = runs.maxByOrNull { if (it.state.isFinished) 0 else 1 }

    /** A run queued (waiting for its network) or going. */
    val active: WorkInfo? = runs.firstOrNull { !it.state.isFinished }

    /** A run actually working right now. */
    val running: Boolean = runs.any { it.state == WorkInfo.State.RUNNING }

    fun stop(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(UpdateWorker.WORK_NAME)
}

@Composable
fun rememberUpdates(): Updates {
    val context = LocalContext.current
    val work = remember { WorkManager.getInstance(context) }
    val runs by remember { work.getWorkInfosForUniqueWorkFlow(UpdateWorker.WORK_NAME) }.collectAsState(emptyList())
    return Updates(runs)
}

/**
 * Starts an update -- the named stores scraped here, or (empty) the catalogue
 * sync -- asking for the
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
