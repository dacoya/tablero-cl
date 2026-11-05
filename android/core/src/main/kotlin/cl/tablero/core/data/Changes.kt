package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection

/*
 * Port of scripts/changes.py: what changed since the last review, each row
 * saying what moved. SQL verbatim; the reasoning lives in changes.py.
 * tests/fixtures/ingest_scenario.json pins the output.
 */

const val DEFAULT_CURSOR = "last_reviewed"

/** Ordered most notable first; each row reports the first that applies. */
val CHANGE_KINDS = listOf("nuevo", "restock", "agotado", "oferta", "fin_oferta", "baja", "alza")

fun getCursor(conn: SQLiteConnection, name: String = DEFAULT_CURSOR): Long? =
    conn.query("SELECT ts FROM cursor WHERE name = ?", name).firstOrNull()?.get("ts") as Long?

fun setCursor(conn: SQLiteConnection, ts: Long = System.currentTimeMillis() / 1000,
              name: String = DEFAULT_CURSOR): Long {
    conn.exec(
        "INSERT INTO cursor(name, ts) VALUES (?,?) ON CONFLICT(name) DO UPDATE SET ts=excluded.ts",
        name, ts,
    )
    return ts
}

private fun side(op: String, only: String = "") = """
    SELECT product_id, ts, price, in_stock,
           (price_original IS NOT NULL AND price < price_original) AS on_sale,
           price_original IS NULL AS unknown_sale,
           in_stock       IS NULL AS unknown_stock
      FROM (SELECT product_id, MAX(ts) AS ts, price, in_stock, price_original
              FROM price_obs WHERE ts $op ?$only GROUP BY product_id)
"""

// changes._ONLY_MOVED: the before side only for products seen after the cutoff.
private const val ONLY_MOVED = " AND product_id IN (SELECT product_id FROM price_obs WHERE ts >= ?)"

private val SINCE_SQL = """
    WITH after AS (${side(">=")}),
         before AS (${side("<", ONLY_MOVED)})
    SELECT p.id AS product_id, p.store, p.title, p.url, p.game_id, p.kind,
           p.in_stock, p.price_eff, $IMAGE_SQL,
           b.price AS old_price,
           COALESCE(a.price, p.price_eff) AS new_price,
           COALESCE(a.ts, p.first_seen)   AS seen_at,
           CASE WHEN b.price > 0 AND a.price IS NOT NULL
                THEN ROUND((b.price - a.price) * 100.0 / b.price, 1) END AS drop_pct,
           CASE
             WHEN p.first_seen >= ? AND b.product_id IS NULL            THEN 'nuevo'
             WHEN b.unknown_stock = 0 AND b.in_stock = 0
                                      AND a.in_stock = 1                THEN 'restock'
             WHEN b.unknown_stock = 0 AND b.in_stock = 1
                                      AND a.in_stock = 0                THEN 'agotado'
             WHEN b.unknown_sale = 0 AND b.on_sale = 0 AND a.on_sale = 1 THEN 'oferta'
             WHEN b.unknown_sale = 0 AND b.on_sale = 1 AND a.on_sale = 0 THEN 'fin_oferta'
             WHEN a.price < b.price                                     THEN 'baja'
             WHEN a.price > b.price                                     THEN 'alza'
           END AS change
      FROM product p
      LEFT JOIN after  a ON a.product_id = p.id
      LEFT JOIN before b ON b.product_id = p.id
     WHERE p.first_seen >= ? OR (a.product_id IS NOT NULL AND b.product_id IS NOT NULL)
"""

private val ORDER = CHANGE_KINDS.withIndex().joinToString(" ") { (i, k) -> "WHEN '$k' THEN $i" }

/**
 * Everything that moved since `since` (default: the named cursor).
 *
 * `minPct` applies only to the pure price rows, 'baja' and 'alza'. A null
 * `limit` means no limit.
 */
fun sinceLastReview(
    conn: SQLiteConnection,
    since: Long? = null,
    name: String = DEFAULT_CURSOR,
    minPct: Double = 0.0,
    kinds: List<String>? = null,
    limit: Int? = 100,
): List<SqlRow> {
    val cutoff = since ?: getCursor(conn, name) ?: return emptyList()
    val wanted = kinds?.takeIf { it.isNotEmpty() } ?: CHANGE_KINDS
    val bad = wanted - CHANGE_KINDS.toSet()
    require(bad.isEmpty()) {
        "Unknown change kind(s): ${bad.sorted().joinToString()}. Options: ${CHANGE_KINDS.joinToString()}"
    }

    val sql = """
        SELECT * FROM ($SINCE_SQL) c
         WHERE c.change IN (${wanted.joinToString(",") { "?" }})
           AND (c.change NOT IN ('baja', 'alza')
                OR ABS(COALESCE(c.drop_pct, 0)) >= ?)
         ORDER BY CASE c.change $ORDER END,
                  ABS(COALESCE(c.drop_pct, 0)) DESC,
                  c.seen_at DESC
         LIMIT ?
    """
    // Five `since` binds inside SINCE_SQL, in source order.
    val args: List<Any?> = listOf(cutoff, cutoff, cutoff, cutoff, cutoff) + wanted + minPct + (limit?.toLong() ?: -1L)
    return conn.query(sql, *args.toTypedArray())
}
