package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection

/*
 * Favourite stores and the update cadence they get. App-only: Python has the
 * table (schema parity) but never reads it.
 */

fun favStores(conn: SQLiteConnection): Set<String> =
    conn.query("SELECT store FROM store_fav").map { it["store"] as String }.toSet()

fun favSet(conn: SQLiteConnection, store: String, favourite: Boolean) {
    if (favourite) conn.exec("INSERT OR IGNORE INTO store_fav(store) VALUES (?)", store)
    else conn.exec("DELETE FROM store_fav WHERE store = ?", store)
}

/** Each store's last successful scrape, unix seconds. Stores never scraped are absent. */
fun lastSuccess(conn: SQLiteConnection): Map<String, Long> =
    conn.query("SELECT store, MAX(ts) AS ts FROM scrape_run WHERE success = 1 GROUP BY store")
        .associate { it["store"] as String to it["ts"] as Long }

private const val DAY = 86_400L

/**
 * Slack against the scheduled run drifting: the daily job at 4:00 may start a
 * little earlier than yesterday's did, and a store scraped 23h58m ago must
 * still count as a day old.
 */
private const val SLACK = 3 * 3_600L

/** When a store is next due, unix seconds; 0 when it has never been scraped (due now). */
fun dueAt(store: String, favourites: Set<String>, last: Map<String, Long>, favDays: Int, otherDays: Int): Long {
    val ts = last[store] ?: return 0L
    val days = if (store in favourites) favDays else otherDays
    return ts + days * DAY - SLACK
}

/**
 * The stores the scheduled run should scrape now: favourites every `favDays`,
 * the rest every `otherDays`. A failed scrape records no success, so that
 * store stays due and is retried on the next run.
 */
fun dueStores(
    stores: List<String>, favourites: Set<String>, last: Map<String, Long>,
    favDays: Int, otherDays: Int, now: Long = System.currentTimeMillis() / 1000,
): List<String> = stores.filter { dueAt(it, favourites, last, favDays, otherDays) <= now }
