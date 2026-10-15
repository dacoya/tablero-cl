package cl.tablero.core.data

import java.text.Normalizer

/*
 * Port of scripts/utils.py: title normalization and price parsing.
 *
 * The reasoning behind each pattern lives in utils.py and is not repeated
 * here; the patterns themselves are copied verbatim so the two files can be
 * diffed. tests/fixtures/derive_vectors.json pins every function in this file.
 */

// ---------------------------------------------------------------------------
// Match key
// ---------------------------------------------------------------------------

private val LANG_TAG = pyRegex(
    """[\(\[]?\b(en\s+)?(espa[nñ]ol|ingl[eé]s|ingles|english|castellano)\b[\)\]]?""",
    ignoreCase = true,
)

private val EDITION_TAG = pyRegex(
    """\b\d+[aª]?\s*(edici[oó]n|edition)\b|\b(edici[oó]n|edition)\b""",
    ignoreCase = true,
)

private val BASE_QUALIFIER = pyRegex(
    """[\(\[]?\b(juego\s+)?base\b[\)\]]?\s*$""",
    ignoreCase = true,
)

private val EXPANSION_QUALIFIER = pyRegex(
    """\b(mini\s?)?expansi[oó]n(es)?\b(?=[\s:,\-]+\w)""",
    ignoreCase = true,
)

private val NOT_WORD_OR_SPACE = pyRegex("""[^\w\s]""")
private val SPACES = pyRegex("""\s+""")

/**
 * `unicodedata.combining(ch)` is "canonical combining class != 0", which the
 * JDK does not expose. Non-spacing marks are the same set for every accent
 * that occurs in Latin, Greek and Cyrillic titles.
 *
 * ponytail: category Mn approximates ccc != 0; a few Indic/Tibetan marks
 * differ. Use ICU's UCharacter.getCombiningClass if such titles ever appear.
 */
private fun stripMarks(s: String): String = buildString {
    s.codePoints().forEach {
        if (Character.getType(it) != Character.NON_SPACING_MARK.toInt()) appendCodePoint(it)
    }
}

/** Lowercase, accent-free, punctuation-free match key. See utils.normalize. */
fun normalize(text: String?): String {
    if (text == null) return ""
    var t = text.lowercase()
    t = LANG_TAG.replace(t, " ")
    t = EDITION_TAG.replace(t, " ")
    t = EXPANSION_QUALIFIER.replace(t, " ")
    t = BASE_QUALIFIER.replace(t.pyStrip(), " ")
    t = stripMarks(Normalizer.normalize(t, Normalizer.Form.NFKD))
    t = NOT_WORD_OR_SPACE.replace(t, " ")
    return SPACES.replace(t, " ").pyStrip()
}

// ---------------------------------------------------------------------------
// Display title
// ---------------------------------------------------------------------------

private const val NOISE_PHRASE =
    """juegos?\s+de\s+mesa|board\s+game|juego\s+familiar|juego\s+educativo|juego\s+cooperativo""" +
        """|juego\s+de\s+estrategia|party\s+game"""

private const val NOISE_WORD = """cooperativo|familiar|educativo|estrategia|competitivo|abstracto"""

private val TITLE_NOISE = pyRegex(
    """(?:^|(?<=[\s\-\|,;:]))(?:\b(?:el|la|los|las)\b\s+)?(?:""" + NOISE_PHRASE + """)\s*[\-\|,;:]?""",
    ignoreCase = true,
)

private val TITLE_NOISE_CONCAT = pyRegex(
    """(?<=[A-Za-záéíóúüñÁÉÍÓÚÜÑ])(?:""" + NOISE_PHRASE + """)(?:\s*[\-\|,;:].*)?$""",
    ignoreCase = true,
)

private val TRAILING_NOISE = pyRegex(
    """(?:[\-\|,;:]\s*|\s+[\-\|,;:]\s*)(?:""" + NOISE_WORD + """)""" +
        """(?=(?:[\-\|,;:]\s*|\s+[\-\|,;:]\s*)(?:""" + NOISE_WORD + """)|\s*$)""",
    ignoreCase = true,
)

private val ARTICLE_PREFIX = pyRegex("""\b(?:el|la|los|las)\s*$""", ignoreCase = true)
private val SUBTITLE_TAIL = pyRegex("""\b(?:el|la|los|las)\s+juego\s+de\s*$""", ignoreCase = true)
private val ALWAYS_NOISE = pyRegex("""juego\s+de\s+mesa|board\s+game""", ignoreCase = true)
private val ARTICLE_LEAD = pyRegex("""[\-\|,;:\s]*\b(?:el|la|los|las)\b""", ignoreCase = true)
private val MULTI_SPACE = pyRegex("""\s{2,}""")
private val ORPHAN_ARTICLE = pyRegex("""\s+\b(?:el|la|los|las)\b\s*$""", ignoreCase = true)

private const val EDGE_PUNCT = " –—-|,:;"

/** Pass-1 callback. See utils._strip_unless_subtitle. */
private fun stripUnlessSubtitle(m: MatchResult, input: String): String {
    val phrase = m.value
    if (ALWAYS_NOISE.containsMatchIn(phrase)) return " "
    val articleInPhrase = ARTICLE_LEAD.matchesAtStart(phrase)
    val articleBefore = ARTICLE_PREFIX.containsMatchIn(input.substring(0, m.range.first))
    return if (articleInPhrase || articleBefore) phrase else " "
}

/** Store title with category noise removed, casing kept. See utils.clean_title. */
fun cleanTitle(text: String?): String? {
    if (text == null) return null
    val pass1 = TITLE_NOISE.replace(text) { stripUnlessSubtitle(it, text) }
    val pass2 = TITLE_NOISE_CONCAT.replace(pass1, "")
    val pass3 = TRAILING_NOISE.replace(pass2) { m ->
        if (SUBTITLE_TAIL.containsMatchIn(pass2.substring(0, m.range.first))) m.value else ""
    }
    val pass4 = ORPHAN_ARTICLE.replace(MULTI_SPACE.replace(pass3, " "), "")
    return pass4.trim { it in EDGE_PUNCT }
}

// ---------------------------------------------------------------------------
// Prices
// ---------------------------------------------------------------------------

private val PRICE = pyRegex("""[\d.,]+""")

/**
 * Numeric price from a Chilean price string. See utils.parse_price.
 *
 * "$69.990" -> 69990.0, "$69.990,50" -> 69990.5, "$49,000" -> 49000.0,
 * "9,5" -> 9.5. A group of exactly three digits after the only separator
 * means thousands; anything else is a decimal.
 */
fun parsePrice(text: String?): Double? {
    if (text == null || text.pyStrip().isEmpty()) return null
    val raw = PRICE.find(text)?.value ?: return null

    val hasDot = '.' in raw
    val hasComma = ',' in raw
    val plain = when {
        hasDot && hasComma ->
            if (raw.lastIndexOf('.') > raw.lastIndexOf(',')) raw.replace(",", "")
            else raw.replace(".", "").replace(',', '.')
        hasComma ->
            if (raw.substringAfterLast(',').length == 3) raw.replace(",", "") else raw.replace(',', '.')
        hasDot ->
            if (raw.substringAfterLast('.').length == 3) raw.replace(".", "") else raw
        else -> raw
    }
    return plain.toDoubleOrNull()
}
