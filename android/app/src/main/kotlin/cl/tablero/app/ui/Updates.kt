package cl.tablero.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import java.util.UUID
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cl.tablero.app.UpdateWorker

/** The update in progress or last finished: a catalogue sync or one store's scrape. */
class Updates(val runs: List<WorkInfo>) {
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
 * Starts an update -- the catalogue sync, or one store scraped here -- asking
 * for the notification permission on the way. Without it (Android 13+) the update
 * still runs; its progress and the price alerts just stay out of the shade.
 */
@Composable
fun rememberStartUpdate(): (store: String?) -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return { store ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        UpdateWorker.start(context, store)
    }
}

class PullSync(val refreshing: Boolean, val start: () -> Unit)

/**
 * Pull to refresh, and what every catalogue sync seen running (pulled, on
 * opening the app, or Sincronizar) says once it ends. A sync that found
 * nothing new stays quiet unless it was pulled.
 */
@Composable
fun rememberPullSync(notify: (String) -> Unit, warn: (String) -> Unit): PullSync {
    val updates = rememberUpdates()
    val start = rememberStartUpdate()
    var pulled by remember { mutableStateOf(false) }
    val seen = remember { mutableSetOf<UUID>() }
    LaunchedEffect(updates.runs) {
        updates.runs.forEach { run ->
            if (!run.state.isFinished) seen += run.id
            else if (seen.remove(run.id)) {
                if (UpdateWorker.TAG_SYNC in run.tags) outcome(run, pulled)?.let { (text, warning) ->
                    if (warning) warn(text) else notify(text)
                }
                pulled = false
            }
        }
    }
    // A run too quick to be seen going would leave the spinner up.
    LaunchedEffect(pulled) {
        if (!pulled) return@LaunchedEffect
        delay(10_000)
        if (seen.isEmpty()) pulled = false
    }
    return PullSync(pulled) { pulled = true; start(null) }
}

/** One line on how a finished sync went, and whether it is a warning; null when there is nothing to say. */
private fun outcome(info: WorkInfo, pulled: Boolean): Pair<String, Boolean>? {
    val out = info.outputData
    val failed = out.getStringArray(UpdateWorker.KEY_FAILED).orEmpty().filterNotNull()
    val text = when {
        info.state == WorkInfo.State.CANCELLED -> "Actualización detenida."
        out.getBoolean(UpdateWorker.KEY_SKIPPED, false) -> "Había otra actualización en curso."
        out.getBoolean(UpdateWorker.KEY_CURRENT, false) -> "El catálogo ya estaba al día."
        info.state == WorkInfo.State.FAILED -> "No se pudo sincronizar" + (failed.firstOrNull()?.let { ": $it" } ?: ".")
        out.getInt(UpdateWorker.KEY_BROKEN_STORES, 0) > 0 -> return SYNC_WARNING to true
        else -> return "Catálogo actualizado." to false
    }
    return if (pulled) text to false else null
}

const val SYNC_WARNING = "Algunas tiendas fallaron en sincronizar."
