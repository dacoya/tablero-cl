package cl.tablero.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/** Holds the one [Db] and [Prefs] for the process; the screens and the worker share them. */
class TableroApp : Application() {
    lateinit var db: Db
        private set
    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        db = Db(getDatabasePath("tablero.db"))
        prefs = Prefs(this)
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(UPDATE_CHANNEL, "Actualización en curso", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(ALERT_CHANNEL, "Avisos de precio y cambios", NotificationManager.IMPORTANCE_DEFAULT),
        ))
        Schedule.apply(this, prefs)
    }

    companion object {
        const val UPDATE_CHANNEL = "update"
        const val ALERT_CHANNEL = "alerts"
    }
}
