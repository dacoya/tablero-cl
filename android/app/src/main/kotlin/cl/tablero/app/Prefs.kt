package cl.tablero.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * User settings. SharedPreferences rather than DataStore: a handful of
 * booleans read on the main thread at start-up, no migration story needed.
 *
 * Each setting is also a StateFlow so a screen redraws when it changes.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("tablero", Context.MODE_PRIVATE)

    inner class Flag(private val key: String, default: Boolean) {
        private val state = MutableStateFlow(sp.getBoolean(key, default))
        val flow: StateFlow<Boolean> = state
        var value: Boolean
            get() = state.value
            set(v) { sp.edit { putBoolean(key, v) }; state.value = v }
    }

    /** Update every store once a day in the background. */
    val autoUpdate = Flag("auto_update", true)

    /** Never use mobile data for scraping: a full run moves ~150 MB. */
    val wifiOnly = Flag("wifi_only", true)

    /** The daily run waits for the charger, so it costs no battery. */
    val chargingOnly = Flag("charging_only", true)

    /** Notify when a watched game reaches its target price. */
    val notifyTargets = Flag("notify_targets", true)

    /** Notify when there is something new under Mi radar. */
    val notifyChanges = Flag("notify_changes", true)

    /** Search shows sleeves, dice and other accessories from the start. */
    val accessories = Flag("accessories", false)

    /** Ofertas starts with "Disponibles" on. */
    val inStockOnly = Flag("in_stock_only", false)

    /** The catalogue's last sort and filters (CatalogScreen's JSON), so they survive a restart. */
    var catalog: String
        get() = sp.getString("catalog", "") ?: ""
        set(v) = sp.edit { putString("catalog", v) }

    /** What was last notified, so the same hit is not announced every day. */
    var notifiedHits: String
        get() = sp.getString("notified_hits", "") ?: ""
        set(v) = sp.edit { putString("notified_hits", v) }

    var notifiedChanges: Int
        get() = sp.getInt("notified_changes", 0)
        set(v) = sp.edit { putInt("notified_changes", v) }
}
