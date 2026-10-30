package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection

/*
 * Read side of scripts/bgg.py. The bgg table arrives with a snapshot from the
 * laptop, which imports BGG's ranks CSV; the phone only looks entries up.
 */

private const val CLEAR_WINNER = 10

/** bgg.MATCHED_KINDS: kinds BGG catalogues. */
internal val BGG_KINDS = setOf("game", "expansion")

/** bgg.lookup: BGG id for a norm, or null when absent or ambiguous. Aliases win. */
internal fun bggLookup(conn: SQLiteConnection, norm: String): Long? {
    conn.query("SELECT bgg_id FROM bgg_alias WHERE norm = ?", norm).firstOrNull()?.let { return it["bgg_id"] as Long }
    val top = conn.query(
        "SELECT id, usersrated FROM bgg WHERE norm = ? ORDER BY usersrated DESC, id LIMIT 2", norm,
    )
    if (top.isEmpty()) return null
    if (top.size == 2 && (top[0]["usersrated"] as Long) < CLEAR_WINNER * (top[1]["usersrated"] as Long)) return null
    return top[0]["id"] as Long
}

/** The game's BoardGameGeek entry, or null when it has none. */
fun gameBgg(conn: SQLiteConnection, gameId: Long): SqlRow? = conn.query(
    "SELECT b.* FROM game g JOIN bgg b ON b.id = g.bgg_id WHERE g.id = ?", gameId,
).firstOrNull()
