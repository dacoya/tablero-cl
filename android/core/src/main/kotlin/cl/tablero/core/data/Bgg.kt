package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection

/*
 * Read side of scripts/bgg.py. The bgg table arrives with a snapshot from the
 * laptop, which imports BGG's ranks CSV and bgg_names_es.csv; the phone only
 * looks entries up.
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

private const val RANKS_CURSOR = "bgg_ranks"
private const val RANKS_BASE = "https://raw.githubusercontent.com/beefsack/bgg-ranking-historicals/master"

/**
 * beefsack/bgg-ranking-historicals publishes BGG's ranking once a day, a little
 * before 01:00 UTC. Newest first, so a run before it lands falls back to yesterday's.
 */
fun bggRanksUrls(today: java.time.LocalDate): List<String> =
    listOf(today, today.minusDays(1)).map { "$RANKS_BASE/$it.csv" }

/** One CSV line's fields; a field may be quoted, with "" for a quote inside. */
private fun csvFields(line: String): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { fields += field.toString(); field.clear() }
            else -> field.append(c)
        }
        i++
    }
    return fields + field.toString()
}

/**
 * Refresh rank, ratings, votes and year of the bgg entries the phone already
 * has from one of those daily files, and stamp the refresh. Returns rows read.
 *
 * ponytail: only existing ids. The file lacks is_expansion and the category
 * ranks, and a new entry would need matching to games, which only the laptop
 * does; `tablero snapshot` brings those. Port bgg.match_games to take new ids.
 */
fun refreshBggRanks(conn: SQLiteConnection, csv: String, now: Long = System.currentTimeMillis() / 1000): Int {
    val lines = csv.lineSequence().filter { it.isNotBlank() }.iterator()
    val head = if (lines.hasNext()) csvFields(lines.next()) else emptyList()
    val col = listOf("ID", "Year", "Rank", "Average", "Bayes average", "Users rated").associateWith(head::indexOf)
    require(col.values.none { it < 0 }) { "No es el CSV de rankings de BGG: faltan columnas." }
    // BGG writes 0 for "no rank" and "no year"; that is NULL here, as in bgg.py.
    val rows = lines.asSequence().mapNotNull { line ->
        val f = csvFields(line)
        val id = f.getOrNull(col.getValue("ID"))?.toLongOrNull() ?: return@mapNotNull null
        fun int(name: String) = f.getOrNull(col.getValue(name))?.toLongOrNull()?.takeIf { it != 0L }
        fun real(name: String) = f.getOrNull(col.getValue(name))?.toDoubleOrNull()?.takeIf { it != 0.0 }
        listOf(int("Year"), int("Rank"), real("Bayes average"), real("Average"), int("Users rated") ?: 0L, id)
    }.toList()
    require(rows.isNotEmpty()) { "El CSV de rankings de BGG viene vacío." }
    conn.transaction {
        conn.execMany(
            "UPDATE bgg SET yearpublished = ?, rank = ?, bayesaverage = ?, average = ?, usersrated = ? WHERE id = ?", rows)
        setCursor(conn, now, RANKS_CURSOR)
    }
    return rows.size
}
