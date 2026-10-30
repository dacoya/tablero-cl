package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection

/*
 * The phone's own features, no Python counterpart: games the user owns, and
 * which base game an expansion belongs to.
 *
 * Nothing records that link, so it is read off the normalized titles: an
 * expansion belongs to the game whose whole norm starts its own. The longest
 * match wins, so "Skull King Pack" goes to Skull King and not to Skull.
 */

private const val MIN_BASE_NORM = 3

/** SQL: e (an expansion) belongs to b. The range keeps the longer-match check on the norm index. */
internal const val EXPANSION_OF_SQL = """
    e.kind = 'expansion' AND b.kind = 'game' AND LENGTH(b.norm) >= $MIN_BASE_NORM
    AND SUBSTR(e.norm, 1, LENGTH(b.norm) + 1) = b.norm || ' '
    AND NOT EXISTS (
        SELECT 1 FROM game longer
         WHERE longer.kind = 'game' AND longer.norm > b.norm AND longer.norm < e.norm
           AND SUBSTR(e.norm, 1, LENGTH(longer.norm) + 1) = longer.norm || ' ')
"""

private const val GAME_CARD_COLS = """
    g.id AS game_id, g.title, g.kind,
    MIN(NULLIF(p.price_eff, 0)) AS min_price,
    COUNT(DISTINCT p.store)     AS n_stores,
    MAX(p.in_stock)             AS in_stock,
    MIN(p.image_url)            AS image_url
"""

/** A base game's expansions that some store lists, cheapest first. */
fun gameExpansions(conn: SQLiteConnection, gameId: Long): List<SqlRow> = conn.query("""
    SELECT $GAME_CARD_COLS
      FROM game b
      JOIN game e ON $EXPANSION_OF_SQL
      JOIN game g ON g.id = e.id
      JOIN product p ON p.game_id = g.id AND $STALE_CLAUSE
     WHERE b.id = ?
     GROUP BY g.id
     ORDER BY (min_price IS NULL), min_price, g.id
""", gameId)

/** The base game of an expansion, or null when the game is not one or has none. */
fun gameBase(conn: SQLiteConnection, gameId: Long): SqlRow? = conn.query("""
    SELECT b.id AS game_id, b.title
      FROM game e
      JOIN game b ON $EXPANSION_OF_SQL
     WHERE e.id = ?
""", gameId).firstOrNull()

fun ownedAdd(conn: SQLiteConnection, gameId: Long, now: Long = System.currentTimeMillis() / 1000) =
    conn.exec("INSERT OR IGNORE INTO owned(game_id, added_at) VALUES (?,?)", gameId, now)

fun ownedRemove(conn: SQLiteConnection, gameId: Long) = conn.exec("DELETE FROM owned WHERE game_id = ?", gameId)

fun isOwned(conn: SQLiteConnection, gameId: Long): Boolean =
    conn.query("SELECT 1 AS x FROM owned WHERE game_id = ?", gameId).isNotEmpty()

/** The collection, latest first. A game no store lists any more stays, without a price. */
fun ownedEntries(conn: SQLiteConnection): List<SqlRow> = conn.query("""
    SELECT $GAME_CARD_COLS
      FROM owned o
      JOIN game g ON g.id = o.game_id
      LEFT JOIN product p ON p.game_id = g.id AND $STALE_CLAUSE
     GROUP BY g.id
     ORDER BY o.added_at DESC, g.id
""")
