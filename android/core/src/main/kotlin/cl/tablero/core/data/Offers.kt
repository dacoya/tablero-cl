package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection

/*
 * What an offer really costs and how old it is. Phone-only, no Python
 * counterpart. Stores publish no usable shipping data, so the cost is whatever
 * the user entered for that store; a store with none is compared on its price.
 */

/** Older than this, a price is shown as doubtful even if its store did not fail. */
const val OLD_PRICE_SECONDS = 3 * 86_400L

/** The user's shipping cost per store; 0 means free pickup. */
fun shippingCosts(conn: SQLiteConnection): Map<String, Double> =
    conn.query("SELECT store, cost FROM store_shipping").associate { it["store"] as String to (it["cost"] as Number).toDouble() }

/** Set a store's shipping cost, or forget it with null. */
fun shippingSet(conn: SQLiteConnection, store: String, cost: Double?) {
    if (cost == null) conn.exec("DELETE FROM store_shipping WHERE store = ?", store)
    else conn.exec("INSERT OR REPLACE INTO store_shipping(store, cost) VALUES (?,?)", store, cost)
}

/**
 * Offers cheapest first by price plus shipping, each with "shipping" (null when
 * unknown) and "total". Stable, and offers with no price stay last.
 */
fun offersByTotal(offers: List<SqlRow>, shipping: Map<String, Double>): List<SqlRow> = offers.map { offer ->
    val cost = shipping[offer["store"]]
    val price = (offer["price_eff"] as Number?)?.toDouble()
    offer + mapOf("shipping" to cost, "total" to price?.let { it + (cost ?: 0.0) })
}.sortedWith(compareBy(nullsLast()) { it["total"] as Double? })

/** When a store was last scraped successfully, and whether a later attempt failed. */
data class Freshness(val lastOk: Long?, val failed: Boolean) {
    fun dim(now: Long = System.currentTimeMillis() / 1000): Boolean =
        failed || lastOk == null || now - lastOk > OLD_PRICE_SECONDS
}

/** [Freshness] of every store with a recorded scrape. */
fun storeFreshness(conn: SQLiteConnection): Map<String, Freshness> = conn.query("""
    SELECT store, MAX(CASE WHEN success = 1 THEN ts END) AS last_ok, MAX(ts) AS last_try
      FROM scrape_run GROUP BY store
""").associate { row ->
    val lastOk = row["last_ok"] as Long?
    row["store"] as String to Freshness(lastOk, lastOk == null || (row["last_try"] as Long) > lastOk)
}

// Games per query in [withCheapest]: SQLite before 3.32 caps a statement at 999 variables.
private const val CHEAPEST_CHUNK = 500

/**
 * Each row with "cheapest": the lowest price among its game's visible, in-stock
 * listings, or null when fewer than two stores have it in stock, since one
 * store has nothing to be compared with.
 */
fun withCheapest(conn: SQLiteConnection, rows: List<SqlRow>): List<SqlRow> {
    val cheapest = rows.mapNotNull { it["game_id"] as Long? }.distinct().chunked(CHEAPEST_CHUNK).flatMap { ids ->
        conn.query("""
            SELECT p.game_id, MIN(p.price_eff) AS lo FROM product p
             WHERE p.game_id IN (${ids.joinToString { "?" }}) AND p.price_eff > 0
               AND COALESCE(p.in_stock, 1) != 0 AND $STALE_CLAUSE
             GROUP BY p.game_id HAVING COUNT(DISTINCT p.store) >= 2
        """, *ids.toTypedArray()).map { it["game_id"] as Long to (it["lo"] as Number).toDouble() }
    }.toMap()
    return rows.map { it + ("cheapest" to cheapest[it["game_id"]]) }
}
