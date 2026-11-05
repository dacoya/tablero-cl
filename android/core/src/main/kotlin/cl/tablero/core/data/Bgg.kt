package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection

/*
 * Read side of scripts/bgg.py. The bgg table arrives with a snapshot from the
 * laptop, which imports BGG's ranks CSV and bgg_names_es.csv; the phone only
 * looks entries up. Ranks are as fresh as the last imported snapshot.
 */

/** bgg.CLEAR_WINNER. */
internal const val CLEAR_WINNER = 3

/** bgg.MATCHED_KINDS: kinds BGG catalogues. */
internal val BGG_KINDS = setOf("game", "expansion")

/** bgg.EDITION_WORDS: norm words that name a printing of a game, not another game. */
private val EDITION_WORDS = pyRegex(
    """\b(deluxe|kickstarter|ks|collectors?|coleccionista|retail|premium|limitada|limited|especial""" +
        """|special|lujo|definitiva|definitive|aniversario|anniversary|all in|big box)\b""",
)

/** bgg.without_edition: the norm without EDITION_WORDS, or "" when they are all it has. */
internal fun withoutEdition(norm: String): String =
    EDITION_WORDS.replace(norm, " ").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

/**
 * bgg.lookup: BGG id for a norm, or for it without edition words. Aliases win.
 * ponytail: no derived-name or fuzzy pass for games new on the phone; the next CSV import or snapshot brings them.
 */
internal fun bggLookup(conn: SQLiteConnection, norm: String): Long? {
    exactLookup(conn, norm)?.let { return it }
    val base = withoutEdition(norm)
    return if (base.isEmpty() || base == norm) null else exactLookup(conn, base)
}

private fun exactLookup(conn: SQLiteConnection, norm: String): Long? {
    conn.query("SELECT bgg_id FROM bgg_alias WHERE norm = ?", norm).firstOrNull()?.let { return it["bgg_id"] as Long }
    val top = conn.query(
        "SELECT id, usersrated FROM bgg WHERE norm = ? ORDER BY usersrated DESC, id LIMIT 2", norm,
    )
    if (top.isEmpty()) return null
    if (top.size == 2 && (top[0]["usersrated"] as Long) < CLEAR_WINNER * (top[1]["usersrated"] as Long)) return null
    return top[0]["id"] as Long
}

/** The game's BoardGameGeek entry with its facts, or null when it has none. Facts are null when bgg_names_es.csv lacks them. */
fun gameBgg(conn: SQLiteConnection, gameId: Long): SqlRow? = conn.query(
    """SELECT b.*, f.bgg_id AS facts_id, f.min_players, f.max_players, f.play_time, f.min_age, f.complexity,
              (SELECT group_concat(mechanic, ' · ') FROM
                 (SELECT mechanic FROM bgg_mechanic WHERE bgg_id = b.id ORDER BY mechanic)) AS mechanics
         FROM game g JOIN bgg b ON b.id = g.bgg_id LEFT JOIN bgg_facts f ON f.bgg_id = b.id
        WHERE g.id = ?""", gameId,
).firstOrNull()

/** Mechanics of the games in the catalogue, the most common first. */
fun bggMechanics(conn: SQLiteConnection): List<String> = conn.query(
    """SELECT m.mechanic FROM bgg_mechanic m JOIN game g ON g.bgg_id = m.bgg_id
        GROUP BY m.mechanic ORDER BY COUNT(*) DESC, m.mechanic""",
).map { it["mechanic"] as String }
