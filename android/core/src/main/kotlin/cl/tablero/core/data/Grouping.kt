package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection

/*
 * Port of the incremental half of scripts/grouping.py: fold a scrape's unseen
 * norms into a near-identical known game. The full `regroup` stays on the
 * desktop; snapshots imported from there carry its result.
 *
 * rapidfuzz is not available here, so ratio and token_sort_ratio are
 * reimplemented with its exact formula (normalized Indel similarity).
 */

private const val THRESHOLD = 95.0
private const val TYPO_THRESHOLD = 80.0
private const val ONE_LETTER_MIN_WORD = 5
private const val ONE_LETTER_MIN_WORDS = 2

private val VARIANT_WORDS = setOf(
    "aniversario", "anniversary", "plus", "deluxe", "junior", "kids", "mini",
    "duelo", "duel", "cartas", "dados", "expansion", "ampliacion", "viaje",
    "legacy", "big", "box", "pack",
)

private val NUMBER = pyRegex("""\d+|\b(i{1,3}|iv|vi{0,3}|ix|x)\b""")

private fun numbers(norm: String): List<String> = NUMBER.findAll(norm).map { it.value }.sorted().toList()

/** rapidfuzz fuzz.ratio: 100 * (1 - indel / (len a + len b)), over code points. */
internal fun indelRatio(a: String, b: String): Double {
    val x = a.codePoints().toArray()
    val y = b.codePoints().toArray()
    if (x.isEmpty() && y.isEmpty()) return 100.0
    var prev = IntArray(y.size + 1)
    for (i in x.indices) {
        val cur = IntArray(y.size + 1)
        for (j in y.indices) {
            cur[j + 1] = if (x[i] == y[j]) prev[j] + 1 else maxOf(prev[j + 1], cur[j])
        }
        prev = cur
    }
    val lcs = prev[y.size]
    val total = x.size + y.size
    return 100.0 * (1.0 - (total - 2 * lcs).toDouble() / total)
}

private fun sortTokens(s: String): String = s.split(' ').filter { it.isNotEmpty() }.sorted().joinToString(" ")

internal fun tokenSortRatio(a: String, b: String): Double = indelRatio(sortTokens(a), sortTokens(b))

/** grouping._one_edit: exactly one character changed, inserted or deleted. */
private fun oneEdit(x: String, y: String): Boolean {
    var a = x.codePoints().toArray()
    var b = y.codePoints().toArray()
    if (kotlin.math.abs(a.size - b.size) > 1 || a.contentEquals(b)) return false
    if (a.size > b.size) a = b.also { b = a }
    var i = 0
    while (i < a.size && a[i] == b[i]) i++
    val rest = if (a.size == b.size) i + 1 else i
    return a.copyOfRange(minOf(rest, a.size), a.size).contentEquals(b.copyOfRange(minOf(i + 1, b.size), b.size))
}

private fun genderSwap(a: String, b: String): Boolean =
    a.dropLast(1) == b.dropLast(1) && setOf(a.last(), b.last()) == setOf('o', 'a')

/** grouping._one_letter_apart. */
private fun oneLetterApart(words: Int, onlyA: List<String>, onlyB: List<String>): Boolean {
    if (words < ONE_LETTER_MIN_WORDS || onlyA.size != 1 || onlyB.size != 1) return false
    val a = onlyA[0]
    val b = onlyB[0]
    return minOf(a.pyLen, b.pyLen) >= ONE_LETTER_MIN_WORD && oneEdit(a, b) && !genderSwap(a, b)
}

/** grouping._min_score: lowest score a one-letter typo of an n-letter norm can reach. */
private fun minScore(n: Int): Double = minOf(THRESHOLD, 100 * (1 - 2.0 / maxOf(2 * n - 1, 2)))

/** grouping.same_game. */
internal fun sameGame(a: String, b: String): Boolean {
    if (numbers(a) != numbers(b)) return false
    val ta = a.split(' ')
    val tb = b.split(' ')
    val onlyA = ta.filter { it !in tb }
    val onlyB = tb.filter { it !in ta }
    if (tokenSortRatio(a, b) < THRESHOLD && !oneLetterApart(ta.size, onlyA, onlyB)) return false
    if (onlyA.size != onlyB.size) return false
    for (word in onlyA) {
        val partner = onlyB.maxBy { indelRatio(word, it) }
        if (indelRatio(word, partner) < TYPO_THRESHOLD) return false
        if ((word in VARIANT_WORDS) != (partner in VARIANT_WORDS)) return false
    }
    return true
}

private fun lengthWindow(n: Int): IntRange {
    val slack = (100 - THRESHOLD) / 100
    val lo = (n * (1 - slack) / (1 + slack)).toInt()
    val hi = (n * (1 + slack) / (1 - slack)).toInt() + 1
    // A one-letter typo moves the length by at most one.
    return minOf(lo, n - 1)..maxOf(hi, n + 1)
}

/** grouping.fold_map: {norm: canonical} for each norm that folds into another. */
internal fun foldMap(norms: List<String>, known: Set<String>): Map<String, String> {
    val canon = known.toMutableSet()
    val byLen = mutableMapOf<Int, MutableList<String>>()
    for (norm in known.sorted()) byLen.getOrPut(norm.pyLen) { mutableListOf() } += norm
    val remap = mutableMapOf<String, String>()
    for (norm in norms) {
        if (norm in canon || norm in remap) continue
        val pool = lengthWindow(norm.pyLen).flatMap { byLen[it].orEmpty() }
        // process.extract: score descending, pool order on ties.
        val hits = pool.map { it to tokenSortRatio(norm, it) }
            .filter { it.second >= minScore(norm.pyLen) }
            .sortedByDescending { it.second }
        val target = hits.firstOrNull { sameGame(norm, it.first) }?.first
        if (target == null) {
            canon += norm
            byLen.getOrPut(norm.pyLen) { mutableListOf() } += norm
        } else {
            remap[norm] = target
        }
    }
    return remap
}

/** grouping.merge_similar_norms: rows with an unseen norm moved onto a known one. */
internal fun mergeSimilarNorms(conn: SQLiteConnection, rows: List<ProductRow>): List<ProductRow> {
    val known = conn.query("SELECT norm FROM game").map { it["norm"] as String }.toSet()
    val remap = foldMap(rows.map { it.norm }.toSet().minus(known).sorted(), known)
    return rows.map { r -> remap[r.norm]?.let { r.copy(norm = it) } ?: r }
}
