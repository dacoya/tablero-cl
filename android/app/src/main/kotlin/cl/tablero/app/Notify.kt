package cl.tablero.app

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cl.tablero.app.ui.MainActivity

/** Notifications after an update: watched games at target, and new changes. */
object Notify {
    const val EXTRA_TAB = "tab"
    private const val TARGETS_ID = 10
    private const val CHANGES_ID = 11

    private fun open(context: Context, tab: String): PendingIntent = PendingIntent.getActivity(
        context, tab.hashCode(),
        Intent(context, MainActivity::class.java).putExtra(EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun post(context: Context, id: Int, title: String, text: String, tab: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val notification = NotificationCompat.Builder(context, TableroApp.ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open(context, tab))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    /**
     * `hits` are (title, price) of watched games at or under target. Only a
     * set different from the one last announced is posted, so a game that
     * stays on sale for a week is announced once.
     */
    fun targets(context: Context, prefs: Prefs, hits: List<Pair<String, Double>>) {
        val key = hits.map { "${it.first}=${it.second}" }.sorted().joinToString("|")
        if (hits.isEmpty() || key == prefs.notifiedHits || !prefs.notifyTargets.value) {
            if (hits.isEmpty()) prefs.notifiedHits = ""
            return
        }
        prefs.notifiedHits = key
        val text = hits.joinToString("\n") { "${it.first}: ${money(it.second)}" }
        post(context, TARGETS_ID, "Precio objetivo alcanzado (${hits.size})", text, "radar")
    }

    /** Announced when the count since the last review grew. */
    fun changes(context: Context, prefs: Prefs, count: Int) {
        if (count <= prefs.notifiedChanges || !prefs.notifyChanges.value) {
            if (count < prefs.notifiedChanges) prefs.notifiedChanges = count
            return
        }
        prefs.notifiedChanges = count
        post(context, CHANGES_ID, "Tablero", "$count cambios desde tu última revisión", "radar")
    }
}
