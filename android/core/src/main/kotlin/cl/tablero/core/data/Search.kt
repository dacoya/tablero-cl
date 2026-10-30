package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection

/*
 * Game search. The only search now that the laptop CLI is gone.
 * No FTS5 in Android's SQLite and no rapidfuzz,
 * so candidates come from LIKE on the persisted match key, ranked by a boost
 * for the exact title or prefix and a kind penalty.
 *
 * Typos: when prefix matching finds fewer than FALLBACK_THRESHOLD games,
 * every game norm is compared by edit distance over word windows.
 */

/** Per-game cheapest price, store count, stock and flags. */
private fun gameAgg(includeStale: Boolean): String {
    val stale = if (includeStale) "" else " AND $STALE_CLAUSE"
    return """
    SELECT g.id            AS game_id,
           g.norm          AS norm,
           g.title         AS title,
           g.kind          AS kind,
           COUNT(DISTINCT p.store)                        AS n_stores,
           MIN(NULLIF(p.price_eff, 0))                    AS min_price,
           MAX(p.in_stock)                                AS in_stock,
           MAX(CASE WHEN p.flag = 'new' THEN 1 ELSE 0 END)     AS is_new,
           MAX(CASE WHEN p.flag = 'restock' THEN 1 ELSE 0 END) AS is_restock,
           MIN(p.image_url)                               AS image_url
      FROM game g
      JOIN product p ON p.game_id = g.id$stale
    """
}

// Sinks an accessory below a real game of equal relevance.
private const val KIND_PENALTY = 8.0

/** The exact title first, then titles that start with the query. */
private fun boost(query: String, norm: String): Double = when {
    norm == query -> 100.0
    norm.startsWith("$query ") -> 12.0
    query in norm.split(' ') -> 6.0
    else -> 0.0
}

internal fun likeEscape(token: String) = token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

// Typo fallback: when to try it, and how close a match must be (0-1).
private const val FALLBACK_THRESHOLD = 5
private const val FALLBACK_CUTOFF = 0.8
private const val FALLBACK_POOL = 60

/** Levenshtein distance, two rows of memory. */
private fun distance(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) {
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        prev = cur.also { cur = prev }
    }
    return prev[b.length]
}

private fun ratio(a: String, b: String): Double = 1.0 - distance(a, b).toDouble() / maxOf(a.length, b.length, 1)

/**
 * How close `query` is to the best run of as many words in `norm`: "pandemc"
 * against "pandemic legacy" is judged on "pandemic", not on the whole title.
 */
fun closeness(query: String, norm: String): Double {
    val q = query.split(' ')
    val n = norm.split(' ')
    if (n.size <= q.size) return ratio(query, norm)
    return (0..n.size - q.size).maxOf { ratio(query, n.subList(it, it + q.size).joinToString(" ")) }
}

/**
 * Games where some store's listing contains every query word as the start of
 * a word -- the same prefix semantics FTS gave the terminal ("terra" finds
 * Terraforming Mars, "mars" finds it too, "erra" does not).
 *
 * Matching product norms rather than game norms keeps a game findable when
 * only one store spells it in full.
 */
private fun prefixMatches(conn: SQLiteConnection, tokens: List<String>, includeStale: Boolean): List<SqlRow> {
    val match = tokens.joinToString(" AND ") { "(' ' || p2.norm) LIKE ? ESCAPE '\\'" }
    return conn.query("""
        ${gameAgg(includeStale)}
         WHERE g.id IN (
               SELECT p2.game_id FROM product p2
                WHERE p2.game_id IS NOT NULL AND $match
         )
         GROUP BY g.id
    """, *tokens.map { "% ${likeEscape(it)}%" }.toTypedArray())
}

/** The typo fallback: the closest game norms by edit distance, minus those already found. */
private fun typoMatches(conn: SQLiteConnection, q: String, seen: Set<Any?>, includeStale: Boolean): List<SqlRow> {
    val close = conn.query("SELECT id, norm FROM game")
        .map { it["id"] as Long to closeness(q, it["norm"] as String) }
        .filter { (id, score) -> score >= FALLBACK_CUTOFF && id !in seen }
        .sortedByDescending { it.second }
        .take(FALLBACK_POOL)
        .map { it.first }
    if (close.isEmpty()) return emptyList()
    return conn.query(
        "${gameAgg(includeStale)} WHERE g.id IN (${close.joinToString(",") { "?" }}) GROUP BY g.id",
        *close.toTypedArray(),
    )
}

/**
 * Ranking: [boost] plus a relevance term, minus the kind penalty.
 *
 * Coverage stands in for the fuzzy score on a prefix match: "catan" covers
 * all of "catan" and a fifth of "catan ciudades y caballeros expansion". A
 * typo match is scored on how close it is instead.
 */
private fun rank(q: String, tokens: List<String>, row: SqlRow): Double {
    val norm = row["norm"] as String
    val coverage = q.length.toDouble() / maxOf(norm.length, q.length)
    val relevance = if (tokens.all { (" $norm").contains(" $it") }) 40 * coverage else 30 * closeness(q, norm)
    return boost(q, norm) + relevance - KIND_PENALTY * kindRank(row["kind"] as String)
}

/** Ranked games for a query; accessories hidden unless asked for. Ties: more stores, shorter title. */
fun searchGames(
    conn: SQLiteConnection,
    query: String,
    limit: Int = 50,
    includeAccessories: Boolean = false,
    includeStale: Boolean = false,
): List<SqlRow> {
    val q = normalize(query)
    if (q.isEmpty()) return emptyList()
    val tokens = q.split(' ')

    var rows = prefixMatches(conn, tokens, includeStale)
    if (rows.size < FALLBACK_THRESHOLD) {
        rows = rows + typoMatches(conn, q, rows.map { it["game_id"] }.toSet(), includeStale)
    }
    return rows
        .filter { includeAccessories || it["kind"] != KIND_ACCESSORY }
        .map { it to rank(q, tokens, it) }
        .sortedWith(
            compareByDescending<Pair<SqlRow, Double>> { it.second }
                .thenByDescending { it.first["n_stores"] as Long }
                .thenBy { (it.first["title"] as String).length },
        )
        .take(limit)
        .map { it.first }
}
