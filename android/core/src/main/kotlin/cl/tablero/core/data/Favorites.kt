package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection

/*
 * Favourite stores, listed first in Tiendas. App-only: Python has the
 * table (schema parity) but never reads it.
 */

fun favStores(conn: SQLiteConnection): Set<String> =
    conn.query("SELECT store FROM store_fav").map { it["store"] as String }.toSet()

fun favSet(conn: SQLiteConnection, store: String, favourite: Boolean) {
    if (favourite) conn.exec("INSERT OR IGNORE INTO store_fav(store) VALUES (?)", store)
    else conn.exec("DELETE FROM store_fav WHERE store = ?", store)
}
