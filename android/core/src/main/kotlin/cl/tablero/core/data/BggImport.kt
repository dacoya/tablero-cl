package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection

/*
 * The phone's own BGG import: boardgames_ranks_es.csv, which is BGG's ranks
 * CSV with three columns added (name_es, variants_es, name_es_source), or the
 * plain ranks CSV. Port of bgg.import_csv, bgg.load_aliases and
 * bgg.match_games, without match_games' fuzzy pass.
 */

/** bgg.MIN_VOTES. */
private const val MIN_VOTES = 5

/** bgg.LIST_SEP: between the names of variants_es. */
private const val LIST_SEP = '|'

/** bgg._INT_COLUMNS. */
private val INT_COLUMNS = listOf(
    "yearpublished", "rank", "abstracts_rank", "cgs_rank", "childrensgames_rank", "familygames_rank",
    "partygames_rank", "strategygames_rank", "thematic_rank", "wargames_rank",
)

private val BGG_COLUMNS = listOf("id", "name", "norm") + INT_COLUMNS +
    listOf("bayesaverage", "average", "usersrated", "is_expansion")

/** What an import kept: BGG entries, Spanish names, and catalogue games now matched. */
data class BggImported(val entries: Int, val aliases: Int, val matched: Int)

/** One CSV line's fields; a field may be quoted, with "" for a quote inside. */
internal fun csvFields(line: String): List<String> {
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

// bgg._int and bgg._float: BGG writes 0 for "no rank" and "no year", which is NULL here.
private fun int(value: String?): Long? = value?.trim()?.toLongOrNull()?.takeIf { it != 0L }
private fun real(value: String?): Double? = value?.trim()?.toDoubleOrNull()?.takeIf { it != 0.0 }

private fun key(name: String): String = normalize(cleanTitle(name))

/**
 * Replace the bgg table with the CSV's entries and, when it has name_es, the
 * Spanish names with its own, then match the catalogue again. Returns what
 * was kept. Fails, changing nothing, on a file that is not a BGG ranks CSV.
 */
fun importBggCsv(conn: SQLiteConnection, lines: Sequence<String>): BggImported {
    val rows = lines.filter { it.isNotBlank() }.iterator()
    val head = if (rows.hasNext()) csvFields(rows.next()).map { it.trim().removePrefix("﻿") } else emptyList()
    val col = head.withIndex().associate { (i, name) -> name to i }
    require(listOf("id", "name", "usersrated").all { it in col }) {
        "No es el CSV de BGG: faltan las columnas id, name o usersrated."
    }
    val hasNames = "name_es" in col
    val entries = mutableListOf<List<Any?>>()
    val claims = mutableMapOf<String, MutableSet<Long>>()
    for (line in rows) {
        val f = csvFields(line)
        fun get(name: String) = col[name]?.let(f::getOrNull)
        val id = int(get("id")) ?: continue
        if (hasNames) {
            val names = listOfNotNull(get("name_es")) + get("variants_es").orEmpty().split(LIST_SEP)
            names.map { it.trim() }.filter { it.isNotEmpty() }.forEach { claims.getOrPut(key(it)) { mutableSetOf() } += id }
        }
        val votes = int(get("usersrated")) ?: 0L
        val name = get("name")?.trim().orEmpty()
        val norm = if (votes < MIN_VOTES) "" else key(name)
        if (norm.isEmpty()) continue
        entries += listOf(id, name, norm) + INT_COLUMNS.map { int(get(it)) } +
            listOf(real(get("bayesaverage")), real(get("average")), votes, if (get("is_expansion")?.trim() == "1") 1L else 0L)
    }
    require(entries.isNotEmpty()) { "El CSV de BGG no trae juegos." }
    val known = entries.mapTo(HashSet()) { it[0] as Long }
    // An alias two entries claim cannot say which game it means.
    val aliases = claims.filter { (norm, ids) -> norm.isNotEmpty() && ids.size == 1 && ids.single() in known }
        .map { (norm, ids) -> listOf(norm, ids.single()) }
    return conn.transaction {
        conn.exec("DELETE FROM bgg")
        conn.execMany("INSERT OR REPLACE INTO bgg(${BGG_COLUMNS.joinToString()}) VALUES (${BGG_COLUMNS.joinToString { "?" }})", entries)
        if (hasNames) {
            conn.exec("DELETE FROM bgg_alias")
            conn.execMany("INSERT INTO bgg_alias(norm, bgg_id) VALUES (?, ?)", aliases)
        }
        BggImported(entries.size, if (hasNames) aliases.size else -1, matchGames(conn, known))
    }
}

/**
 * bgg.match_games without its fuzzy pass: each game gets the entry of its own
 * norm or, failing that, any of its products' norms. A game nothing matches
 * keeps its entry when the CSV still has it, which is how the laptop's fuzzy
 * matches survive. Returns games matched.
 */
private fun matchGames(conn: SQLiteConnection, known: Set<Long>): Int {
    val kinds = BGG_KINDS.joinToString { "?" }
    val candidates = linkedMapOf<Long, Pair<Long?, MutableList<String>>>()
    conn.query("""
        SELECT g.id, g.bgg_id, p.norm, p.norm = g.norm AS own FROM game g JOIN product p ON p.game_id = g.id
         WHERE g.kind IN ($kinds) GROUP BY g.id, p.norm ORDER BY g.id, own DESC, p.norm
    """, *BGG_KINDS.toTypedArray()).forEach { r ->
        candidates.getOrPut(r["id"] as Long) { (r["bgg_id"] as Long?) to mutableListOf() }.second += r["norm"] as String
    }
    val matches = candidates.mapNotNull { (gid, c) ->
        val (old, norms) = c
        (norms.firstNotNullOfOrNull { bggLookup(conn, it) } ?: old?.takeIf { it in known })?.let { listOf(it, gid) }
    }
    conn.exec("UPDATE game SET bgg_id = NULL")
    conn.execMany("UPDATE game SET bgg_id = ? WHERE id = ?", matches)
    return matches.size
}
