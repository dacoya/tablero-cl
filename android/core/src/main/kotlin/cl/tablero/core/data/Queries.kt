package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/*
 * Port of the read side of scripts/repo.py and analytics.smart_products.
 * SQL verbatim except where noted; ingest_scenario.json's "queries" section
 * pins every function here except search, which is the phone's own.
 *
 * One rewrite throughout: `x DESC NULLS LAST` becomes `(x IS NULL), x DESC`.
 * NULLS LAST needs SQLite 3.30 and Android 11 ships 3.28. Same order.
 */

/**
 * A product is stale when its store was scraped successfully more recently
 * than the product was last seen: delisted, or its URL changed. Hidden, never
 * deleted, so its history survives.
 */
const val STALE_CLAUSE = """
    p.last_seen >= COALESCE(
        (SELECT MAX(r.ts) FROM scrape_run r WHERE r.store = p.store AND r.success = 1),
        p.last_seen
    )
"""

private const val DISCOUNT_PCT_SQL = """
    CASE WHEN p.price_current IS NOT NULL AND p.price_original > 0
              AND p.price_original > p.price_current
         THEN ROUND((p.price_original - p.price_current) * 100.0 / p.price_original, 1)
    END
"""

private const val PRODUCT_COLS = """
    p.id AS product_id, p.store, p.title, p.url, p.game_id, p.kind,
    p.price_original, p.price_current, p.price_eff, p.in_stock, p.flag,
    p.image_url,
    $DISCOUNT_PCT_SQL AS discount_pct
"""

/** repo._ORDER_BY. Every order ends on p.id so paging is stable. */
val SORTS: Map<String, String> = linkedMapOf(
    "discount" to "(discount_pct IS NULL), discount_pct DESC, p.price_eff, p.id",
    "price" to "(p.price_eff IS NULL), p.price_eff, p.id",
    "price_desc" to "(p.price_eff IS NULL), p.price_eff DESC, p.id",
    "sale_desc" to "(discount_pct IS NULL), p.price_eff DESC, p.id",
    "savings" to "(discount_pct IS NULL), (p.price_original - p.price_current) DESC, p.id",
    "store" to "p.store, p.title, p.id",
    "title" to "p.title, p.id",
)

val SMART_SORTS = listOf("value", "scarcity", "volatility")

/** The bgg columns [Filters.bggCategory] accepts: its value goes into the SQL. */
val BGG_RANK_COLUMNS = setOf(
    "strategygames_rank", "familygames_rank", "partygames_rank", "thematic_rank",
    "abstracts_rank", "childrensgames_rank", "wargames_rank", "cgs_rank",
)

/**
 * repo.filter_clause's keyword arguments, then the phone's own: BGG category and
 * overall rank, the collection, and one row per game (the plain sorts only).
 */
data class Filters(
    val stores: List<String>? = null,
    val inStockOnly: Boolean = false,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val onSale: Boolean = false,
    val flag: String? = null,
    val kinds: List<String>? = null,
    val includeStale: Boolean = false,
    val minDiscount: Double? = null,
    val preorder: Boolean = false,
    val bggCategory: String? = null,
    val bggTop: Int? = null,
    val hideOwned: Boolean = false,
    val ownedExpansions: Boolean = false,
    val onePerGame: Boolean = false,
)

// Text.PREORDER_TAG as LIKE patterns: SQLite has no regex. LIKE ignores ASCII case,
// and brackets or parentheses around the word don't matter to a substring match.
private val PREORDER_LIKES = listOf("%preventa%", "%pre-venta%", "%pre venta%", "%preorder%",
    "%pre-order%", "%pre order%", "%preorden%", "%pre-orden%", "%reserva%")

// Few titles contain "pre" or "reserva" at all; checking that first skips the
// nine patterns on almost every row, which halves the scan.
private val PREORDER_SQL = "(p.title LIKE '%pre%' OR p.title LIKE '%reserva%') AND (" +
    PREORDER_LIKES.joinToString(" OR ") { "p.title LIKE ?" } + ")"

/** repo.filter_clause: a WHERE fragment and its bound parameters. */
private fun Filters.where(): Pair<String, List<Any?>> {
    val clauses = mutableListOf("1=1")
    val params = mutableListOf<Any?>()
    if (!includeStale) clauses += STALE_CLAUSE
    if (!stores.isNullOrEmpty()) { clauses += "p.store IN (${stores.joinToString(",") { "?" }})"; params.addAll(stores) }
    if (!kinds.isNullOrEmpty()) { clauses += "p.kind IN (${kinds.joinToString(",") { "?" }})"; params.addAll(kinds) }
    if (inStockOnly) clauses += "p.in_stock = 1"
    if (minPrice != null) { clauses += "p.price_eff >= ?"; params += minPrice }
    if (maxPrice != null) { clauses += "p.price_eff <= ?"; params += maxPrice }
    if (onSale) clauses += "p.price_current IS NOT NULL AND p.price_original > p.price_current"
    if (flag != null) { clauses += "p.flag = ?"; params += flag }
    if (minDiscount != null) { clauses += "($DISCOUNT_PCT_SQL) >= ?"; params += minDiscount }
    if (preorder) { clauses += PREORDER_SQL; params.addAll(PREORDER_LIKES) }
    if (bggCategory != null) {
        require(bggCategory in BGG_RANK_COLUMNS) { "Unknown BGG category '$bggCategory'" }
        clauses += "p.game_id IN (SELECT g.id FROM game g JOIN bgg b ON b.id = g.bgg_id WHERE b.$bggCategory IS NOT NULL)"
    }
    if (bggTop != null) {
        clauses += "p.game_id IN (SELECT g.id FROM game g JOIN bgg b ON b.id = g.bgg_id WHERE b.rank <= ?)"
        params += bggTop
    }
    if (hideOwned) clauses += "(p.game_id IS NULL OR p.game_id NOT IN (SELECT game_id FROM owned))"
    if (ownedExpansions) {
        clauses += """p.game_id IN (SELECT e.id FROM owned o JOIN game b ON b.id = o.game_id JOIN game e ON $EXPANSION_OF_SQL)
            AND p.game_id NOT IN (SELECT game_id FROM owned)"""
    }
    return clauses.joinToString(" AND ") to params
}

private fun pageArgs(limit: Int?, offset: Int): Pair<String, List<Any?>> =
    if (limit == null) "" to emptyList() else " LIMIT ? OFFSET ?" to listOf(limit.toLong(), offset.toLong())

/** Filtered product rows, one of [SORTS]. A null limit means everything. */
fun products(conn: SQLiteConnection, sort: String = "discount", filters: Filters = Filters(),
             limit: Int? = null, offset: Int = 0): List<SqlRow> {
    val order = requireNotNull(SORTS[sort]) { "Unknown sort '$sort'. Valid: ${SORTS.keys.sorted().joinToString()}" }
    val (where, params) = filters.where()
    val (page, pageParams) = pageArgs(limit, offset)
    // The filters run first, so a game shows its cheapest listing among those that qualify.
    val from = if (!filters.onePerGame) "product p WHERE $where" else """(
        SELECT p.*, ROW_NUMBER() OVER (PARTITION BY COALESCE(p.game_id, -p.id)
                                       ORDER BY (COALESCE(p.price_eff, 0) = 0), p.price_eff, p.id) AS per_game
          FROM product p WHERE $where) p WHERE p.per_game = 1"""
    return conn.query("SELECT $PRODUCT_COLS FROM $from ORDER BY $order$page",
        *(params + pageParams).toTypedArray())
}

/** Row count under the same filters [products] applies. */
fun countProducts(conn: SQLiteConnection, filters: Filters = Filters()): Long {
    val (where, params) = filters.where()
    val rows = if (filters.onePerGame) "COUNT(DISTINCT COALESCE(p.game_id, -p.id))" else "COUNT(*)"
    return conn.query("SELECT $rows AS n FROM product p WHERE $where", *params.toTypedArray())
        .single()["n"] as Long
}

/**
 * [countProducts] for several filter sets in one pass over the table, as
 * Home's tiles need: one scan instead of one per tile. The staleness check,
 * the costly part of each row, then runs once per row instead of once per tile.
 */
fun countProductsEach(conn: SQLiteConnection, filters: List<Filters>): List<Long> {
    if (filters.isEmpty()) return emptyList()
    val includeStale = filters.first().includeStale
    require(filters.all { it.includeStale == includeStale }) { "Mixed includeStale" }
    val wheres = filters.map { it.copy(includeStale = true).where() }
    val (stale, staleParams) = Filters(includeStale = includeStale).where()
    val sums = wheres.indices.joinToString(", ") { "COALESCE(SUM(CASE WHEN ${wheres[it].first} THEN 1 ELSE 0 END), 0) AS n$it" }
    val row = conn.query("SELECT $sums FROM product p WHERE $stale",
        *(wheres.flatMap { it.second } + staleParams).toTypedArray()).single()
    return wheres.indices.map { row["n$it"] as Long }
}

/** Per-store offers for one game, cheapest first. */
fun gamePrices(conn: SQLiteConnection, gameId: Long, includeStale: Boolean = false): List<SqlRow> {
    val stale = if (includeStale) "" else " AND $STALE_CLAUSE"
    return conn.query("""
        SELECT $PRODUCT_COLS
          FROM product p
         WHERE p.game_id = ?$stale
         ORDER BY (p.price_eff IS NULL), p.price_eff, p.id
    """, gameId)
}

/** Every store with its product counts and last scrape time. */
fun stores(conn: SQLiteConnection, activeOnly: Boolean = false): List<SqlRow> = conn.query("""
    SELECT s.name, s.base_url, s.city, s.active,
           COUNT(p.id) AS n_products,
           SUM(COALESCE(p.in_stock, 0)) AS n_in_stock,
           (SELECT MAX(ts) FROM scrape_run r WHERE r.store = s.name) AS last_scrape,
           (SELECT r.success FROM scrape_run r WHERE r.store = s.name
             ORDER BY r.ts DESC LIMIT 1)                     AS last_success
      FROM store s
      LEFT JOIN product p ON p.store = s.name
    ${if (activeOnly) " WHERE s.active = 1" else ""}
     GROUP BY s.name ORDER BY s.name
""")

// ---------------------------------------------------------------------------
// Smart sorts (analytics.smart_products)
// ---------------------------------------------------------------------------

/*
 * One rewrite here: the per-game median and spread are kept in a temp table
 * instead of recomputed inline, and computed in one pass here rather than by
 * analytics.MEDIAN_CTE (SQLite has no median aggregate; the CTE sorts every
 * product twice through window functions, ~95 ms of a cold Home). Rows come
 * in idx_product_game_price order, so each game's prices arrive sorted. The
 * table is rebuilt whenever this connection has written since (total_changes),
 * so it is never staler than the inline CTE would be. Same rows, same order.
 */
private val statsBuiltAt = java.util.WeakHashMap<SQLiteConnection, Long>()

private fun totalChanges(conn: SQLiteConnection): Long =
    conn.query("SELECT total_changes() AS n").single()["n"] as Long

/** The CTE's median: the middle price, or the mean of the two middle ones. */
private fun median(sorted: List<Double>): Double =
    (sorted[(sorted.size + 1) / 2 - 1] + sorted[(sorted.size + 2) / 2 - 1]) / 2

/** Ensure temp.game_stats (game_id, median, lo, hi, n_stores) matches the product table. */
private fun gameStats(conn: SQLiteConnection) = synchronized(statsBuiltAt) {
    if (statsBuiltAt[conn] == totalChanges(conn)) return@synchronized
    conn.execSQL("DROP TABLE IF EXISTS temp.game_stats")
    conn.execSQL("""CREATE TEMP TABLE game_stats (
        game_id INTEGER PRIMARY KEY, median REAL, lo REAL, hi REAL, n_stores INTEGER)""")
    val rows = mutableListOf<List<Any?>>()
    var game: Long? = null
    val prices = mutableListOf<Double>()
    val stores = mutableSetOf<String>()
    fun flush() {
        game?.let { rows += listOf(it, median(prices), prices.first(), prices.last(), stores.size.toLong()) }
        prices.clear(); stores.clear()
    }
    conn.prepare("""SELECT game_id, price_eff, store FROM product
        WHERE game_id IS NOT NULL AND price_eff > 0 ORDER BY game_id, price_eff""").use { st ->
        while (st.step()) {
            val id = st.getLong(0)
            if (id != game) { flush(); game = id }
            prices += st.getDouble(1)
            stores += st.getText(2)
        }
    }
    flush()
    // One savepoint around the inserts: autocommitting 10k rows one by one costs more than computing them.
    conn.execSQL("SAVEPOINT game_stats")
    conn.execMany("INSERT INTO temp.game_stats VALUES (?,?,?,?,?)", rows)
    conn.execSQL("RELEASE game_stats")
    statsBuiltAt[conn] = totalChanges(conn)
}

// analytics.DEAL_INDEX_SQL: the deal index. The reasoning lives there.
private const val DEAL_D0 = 0.05
private const val DEAL_P0 = 20000
private const val DEAL_REF = """(CASE WHEN s.n_stores >= 2
                     THEN MIN(COALESCE(p.price_original, p.price_eff), MAX(m.median, p.price_eff))
                     ELSE COALESCE(p.price_original, p.price_eff) END)"""
private const val DEAL_SAVING = "($DEAL_REF - p.price_eff)"
private const val DEAL_INDEX_SQL = """(CASE WHEN $DEAL_SAVING > 0
     THEN $DEAL_SAVING * $DEAL_SAVING / ($DEAL_SAVING + $DEAL_D0 * $DEAL_REF)
          * $DEAL_REF / ($DEAL_REF + $DEAL_P0)
     ELSE 0 END)"""

private val SCORE = mapOf(
    "value" to DEAL_INDEX_SQL,
    "scarcity" to "-s.n_stores",
    "volatility" to "(s.hi - s.lo) / m.median",
)

/** analytics.smart_products: products ranked by a cross-store metric, under the same filters. */
fun smartProducts(conn: SQLiteConnection, by: String = "value", filters: Filters = Filters(),
                  limit: Int? = null, offset: Int = 0): List<SqlRow> {
    val score = requireNotNull(SCORE[by]) { "Unknown smart sort '$by'. Options: ${SMART_SORTS.joinToString()}" }
    val (where, params) = filters.where()
    val (page, pageParams) = pageArgs(limit, offset)
    gameStats(conn)
    return conn.query("""
        SELECT p.id AS product_id, p.store, p.title, p.url, p.game_id, p.kind,
               p.price_original, p.price_current, p.price_eff, p.in_stock, p.flag,
               p.image_url, s.n_stores, m.median,
               $DISCOUNT_PCT_SQL AS discount_pct,
               ROUND($score, 4) AS score
          FROM product p
          JOIN temp.game_stats m ON m.game_id = p.game_id
          JOIN temp.game_stats s ON s.game_id = p.game_id
         WHERE m.median > 0 AND $where
         ORDER BY score DESC, p.id$page
    """, *(params + pageParams).toTypedArray())
}

/** One page of rows under any sort, plain or smart, without counting the total. */
fun browseRows(conn: SQLiteConnection, sort: String = "discount", filters: Filters = Filters(),
               limit: Int? = null, offset: Int = 0): List<SqlRow> =
    if (sort in SMART_SORTS) smartProducts(conn, sort, filters, limit, offset)
    else products(conn, sort, filters, limit, offset)

/** analytics.browse: one page of rows plus the total under the same filters. */
fun browse(conn: SQLiteConnection, sort: String = "discount", filters: Filters = Filters(),
           limit: Int? = null, offset: Int = 0): Pair<List<SqlRow>, Long> =
    // The smart sorts also drop games with no usable median: an upper bound there.
    browseRows(conn, sort, filters, limit, offset) to countProducts(conn, filters)
