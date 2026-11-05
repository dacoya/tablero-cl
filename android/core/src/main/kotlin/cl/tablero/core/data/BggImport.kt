package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection

/*
 * The phone's own BGG import: boardgames_ranks_es.csv, which is BGG's ranks
 * CSV with three columns added (name_es, variants_es, name_es_source), or the
 * plain ranks CSV. Port of bgg.import_csv, bgg.load_aliases and
 * bgg.match_games, without match_games' fuzzy pass.
 */

/** bgg.MIN_VOTES: under it, an entry is kept only with an unambiguous name of two words or more. */
private const val MIN_VOTES = 5

/** bgg._PACK_WORDS. */
private val PACK_WORDS = pyRegex("""\b(hero|scenario|villain|expansion) pack\b|\bexpansion\b|\b(card|board) game\b(?= [^ ])""")

/** bgg._KIND_SUBTITLES. */
private val KIND_SUBTITLES = setOf("card game", "board game", "dice game")

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
    val parsed = mutableListOf<List<Any?>>()
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
        val norm = key(name)
        if (norm.isEmpty()) continue
        parsed += listOf(id, name, norm) + INT_COLUMNS.map { int(get(it)) } +
            listOf(real(get("bayesaverage")), real(get("average")), votes, if (get("is_expansion")?.trim() == "1") 1L else 0L)
    }
    val entries = kept(parsed)
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
        BggImported(entries.size, if (hasNames) aliases.size else -1, matchGames(conn, known, DerivedIndex(conn, entries)))
    }
}

private val VOTES = BGG_COLUMNS.indexOf("usersrated")
private val IS_EXPANSION = BGG_COLUMNS.indexOf("is_expansion")

/** bgg._kept: rows with MIN_VOTES, and those under it whose name is unambiguous. */
private fun kept(rows: List<List<Any?>>): List<List<Any?>> {
    val names = rows.groupingBy { it[2] as String }.eachCount()
    return rows.filter { r ->
        val norm = r[2] as String
        r[VOTES] as Long >= MIN_VOTES || (norm.split(' ').size >= 2 && names[norm] == 1)
    }
}

/** bgg._DerivedIndex: BGG ids by name without pack words, and before the colon. Voted entries only. */
private class DerivedIndex(conn: SQLiteConnection, entries: List<List<Any?>>) {
    val taken: Set<String> = entries.mapTo(HashSet()) { it[2] as String } +
        conn.query("SELECT norm FROM bgg_alias").map { it["norm"] as String }
    val pack = HashMap<String, MutableSet<Long>>()
    val prefix = HashMap<String, MutableSet<Long>>()
    val entry = HashMap<Long, Pair<Long, Boolean>>()

    init {
        for (r in entries) {
            val votes = r[VOTES] as Long
            if (votes < MIN_VOTES) continue
            val id = r[0] as Long
            val name = r[1] as String
            val norm = r[2] as String
            entry[id] = votes to (r[IS_EXPANSION] == 1L)
            val packless = PACK_WORDS.replace(norm, " ").split(' ').filter { it.isNotEmpty() }.joinToString(" ")
            if (packless.isNotEmpty() && packless != norm) pack.getOrPut(packless) { mutableSetOf() } += id
            if (':' in name && normalize(name.substringAfter(':')) !in KIND_SUBTITLES) {
                val head = key(name.substringBefore(':'))
                if (head.isNotEmpty() && head != norm) prefix.getOrPut(head) { mutableSetOf() } += id
            }
        }
    }

    /** The most voted id, when it has CLEAR_WINNER times the votes of the next. */
    fun winner(ids: Collection<Long>): Long? {
        val top = ids.sortedWith(compareBy({ -entry.getValue(it).first }, { it }))
        if (top.isEmpty() || (top.size > 1 && entry.getValue(top[0]).first < CLEAR_WINNER * entry.getValue(top[1]).first)) return null
        return top[0]
    }

    /** bgg.derived_lookup. */
    fun lookup(norm: String, kind: String): Long? {
        if (norm in taken) return null
        pack[norm]?.let { return winner(it) }
        return winner(prefix[norm].orEmpty().filter { kind == "expansion" || !entry.getValue(it).second })
    }

    /** The norm, then the norm without edition words, as bgg.match_games tries them. */
    fun lookupWithEdition(norm: String, kind: String): Long? =
        lookup(norm, kind) ?: withoutEdition(norm).takeIf { it.isNotEmpty() && it != norm }?.let { lookup(it, kind) }
}

/**
 * bgg.match_games without its fuzzy pass: each game gets the entry of its own
 * norm or, failing that, any of its products' norms, by name and then by
 * derived name. A game nothing matches
 * keeps its entry when the CSV still has it, which is how the laptop's fuzzy
 * matches survive. Returns games matched.
 */
private class Candidate(val kind: String, val old: Long?, val norms: MutableList<String> = mutableListOf())

private fun matchGames(conn: SQLiteConnection, known: Set<Long>, derived: DerivedIndex): Int {
    val kinds = BGG_KINDS.joinToString { "?" }
    val candidates = linkedMapOf<Long, Candidate>()
    conn.query("""
        SELECT g.id, g.kind, g.bgg_id, p.norm, p.norm = g.norm AS own FROM game g JOIN product p ON p.game_id = g.id
         WHERE g.kind IN ($kinds) GROUP BY g.id, p.norm ORDER BY g.id, own DESC, p.norm
    """, *BGG_KINDS.toTypedArray()).forEach { r ->
        candidates.getOrPut(r["id"] as Long) { Candidate(r["kind"] as String, r["bgg_id"] as Long?) }.norms += r["norm"] as String
    }
    val matches = candidates.mapNotNull { (gid, c) ->
        (c.norms.firstNotNullOfOrNull { bggLookup(conn, it) }
            ?: c.norms.firstNotNullOfOrNull { derived.lookupWithEdition(it, c.kind) }
            ?: c.old?.takeIf { it in known })?.let { listOf(it, gid) }
    }
    conn.exec("UPDATE game SET bgg_id = NULL")
    conn.execMany("UPDATE game SET bgg_id = ? WHERE id = ?", matches)
    return matches.size
}
