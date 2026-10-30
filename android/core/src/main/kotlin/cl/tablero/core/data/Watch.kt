package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection

/*
 * Ports of watchlist.py, alerts.from_watchlist and history.game_trends.
 * ingest_scenario.json's "queries" section pins all three.
 */

/** Add or update a watch. False when the game does not exist. */
fun watchAdd(conn: SQLiteConnection, gameId: Long, target: Double?, note: String? = null,
             now: Long = System.currentTimeMillis() / 1000): Boolean {
    if (conn.query("SELECT 1 AS x FROM game WHERE id = ?", gameId).isEmpty()) return false
    conn.exec(
        """INSERT INTO watchlist(game_id, target, note, added_at) VALUES (?,?,?,?)
           ON CONFLICT(game_id) DO UPDATE SET target=excluded.target, note=excluded.note""",
        gameId, target, note, now,
    )
    return true
}

fun watchRemove(conn: SQLiteConnection, gameId: Long) = conn.exec("DELETE FROM watchlist WHERE game_id = ?", gameId)

/** The stored target for a watched game; absent when not watched, null target when watched without one. */
fun watchOf(conn: SQLiteConnection, gameId: Long): SqlRow? =
    conn.query("SELECT target, note FROM watchlist WHERE game_id = ?", gameId).firstOrNull()

/**
 * watchlist.entries: watched games with their cheapest live price and stock.
 * Delisted products are joined out, so a dropped offer cannot report a hit.
 */
fun watchEntries(conn: SQLiteConnection): List<SqlRow> = conn.query("""
    SELECT w.game_id, w.target, w.note, w.added_at,
           g.title, g.kind,
           MIN(NULLIF(p.price_eff, 0)) AS min_price,
           COUNT(DISTINCT p.store)     AS n_stores,
           MAX(p.in_stock)             AS in_stock,
           MIN(p.image_url)            AS image_url
      FROM watchlist w
      JOIN game g    ON g.id = w.game_id
      LEFT JOIN product p ON p.game_id = g.id AND $STALE_CLAUSE
     GROUP BY w.game_id
     ORDER BY w.added_at, w.game_id
""").map { row ->
    val target = (row["target"] as Number?)?.toDouble()
    val price = (row["min_price"] as Number?)?.toDouble()
    row + ("hit" to (target != null && target != 0.0 && price != null && price != 0.0 && price <= target))
}

/** alerts.from_watchlist: the cheapest live offer of each watched game at or under its target. */
fun watchAlerts(conn: SQLiteConnection, inStockOnly: Boolean = false): List<SqlRow> =
    watchEntries(conn).mapNotNull { entry ->
        val target = (entry["target"] as Number?)?.toDouble()?.takeIf { it != 0.0 } ?: return@mapNotNull null
        val offer = gamePrices(conn, entry["game_id"] as Long).firstOrNull { o ->
            val price = (o["price_eff"] as Number?)?.toDouble()
            price != null && price > 0 && (!inStockOnly || (o["in_stock"] as Long?)?.let { it != 0L } == true)
        } ?: return@mapNotNull null
        val price = (offer["price_eff"] as Number).toDouble()
        if (price > target) return@mapNotNull null
        mapOf(
            "query" to entry["title"], "matched_title" to entry["title"], "game_id" to entry["game_id"],
            "store" to offer["store"], "price" to price, "threshold" to target, "url" to offer["url"],
            "in_stock" to ((offer["in_stock"] as Long?) ?: 0L).let { it != 0L },
        )
    }

private const val SPARK_BLOCKS = "▁▂▃▄▅▆▇█"

/** history.sparkline. */
fun sparkline(values: List<Double>): String {
    if (values.isEmpty()) return ""
    val lo = values.min()
    val hi = values.max()
    if (hi == lo) return SPARK_BLOCKS[0].toString().repeat(values.size)
    return values.joinToString("") { SPARK_BLOCKS[((it - lo) / (hi - lo) * (SPARK_BLOCKS.length - 1)).toInt()].toString() }
}

/**
 * history.game_trends: one price series per listing, most-moved first. A
 * store with several listings of the game gets them labelled apart.
 */
fun gameTrends(conn: SQLiteConnection, gameId: Long, minPoints: Int = 1): List<SqlRow> {
    data class Series(val store: String, val title: String, val points: MutableList<Map<String, Any?>>)
    val series = linkedMapOf<Long, Series>()
    for (row in conn.query("""
        SELECT p.id AS product_id, p.store, p.title, o.ts, o.price
          FROM price_obs o
          JOIN product p ON p.id = o.product_id
         WHERE p.game_id = ?
         ORDER BY p.store, p.id, o.ts
    """, gameId)) {
        series.getOrPut(row["product_id"] as Long) {
            Series(row["store"] as String, row["title"] as String, mutableListOf())
        }.points += mapOf("ts" to row["ts"], "price" to row["price"])
    }
    val perStore = series.values.groupingBy { it.store }.eachCount()
    return series.values.filter { it.points.size >= minPoints }.map { s ->
        val prices = s.points.map { (it["price"] as Number).toDouble() }
        val first = prices.first()
        val last = prices.last()
        mapOf(
            "store" to s.store,
            "label" to if (perStore.getValue(s.store) == 1) s.store else "${s.store} · ${s.title}",
            "first" to first, "last" to last, "min" to prices.min(), "max" to prices.max(),
            "change_pct" to if (first != 0.0) (last - first) / first * 100 else 0.0,
            "n" to prices.size, "spark" to sparkline(prices), "points" to s.points.toList(),
        )
    }.sortedByDescending { kotlin.math.abs(it["change_pct"] as Double) } // stable, like Python's sort
}
