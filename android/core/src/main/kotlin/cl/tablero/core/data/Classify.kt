package cl.tablero.core.data

/*
 * Port of scripts/classify.py: product kind from the title. Patterns verbatim;
 * the cases that motivated each one are documented there.
 */

const val KIND_GAME = "game"
const val KIND_EXPANSION = "expansion"
const val KIND_TCG = "tcg"
const val KIND_PUZZLE = "puzzle"
const val KIND_ACCESSORY = "accessory"

/** Ordered by how a kind should rank in a game search. */
val KIND_ORDER = listOf(KIND_GAME, KIND_EXPANSION, KIND_TCG, KIND_PUZZLE, KIND_ACCESSORY)

private val ACCESSORY = pyRegex(
    """\b(""" +
        """fundas?|sleeves?|protectores?\s+de\s+cartas?""" +
        """|dice\s+(?:set|bag|tower|tray|cup)|set\s+de\s+dados|bolsa\s+de\s+dados""" +
        """|torre\s+de\s+dados|dados\s+poli[eé]dricos?|d\d+\s+dice|dados?\s+d\d+""" +
        """|playmat|play\s?mat|tapete|alfombrilla|mousepad|mouse\s?pad""" +
        """|deck\s?box|caja\s+de\s+mazo|porta\s?mazos?|organizador(?:es)?|insertos?""" +
        """|separadores?|dividers?|binder|carpeta\s+de\s+cartas""" +
        """|toploader|top\s?loader""" +
        """|pinturas?|pinceles?|aer[oó]grafo|imprimaci[oó]n""" +
        """|p[oó]ster|lienzo|llavero|polera|pol[eé]r[oó]n|taza\b|mochila""" +
        """|funko|figura\s+coleccionable|plush\s+toy|peluche\s+de\b""" +
        """)\b""",
    ignoreCase = true,
)

private val TCG = pyRegex(
    """\b(""" +
        """booster|display\b|starter\s+deck|mazo\s+(?:inicial|de\s+torneo)""" +
        """|collector\s+booster|set\s+booster|draft\s+booster|play\s+booster""" +
        """|caja\s+de\s+sobres|sobres?\s+(?:de|sellad)|carta\s+suelta""" +
        """|deck\s+(?:de\s+)?(?:tem[aá]tico|construido)""" +
        """|(?:magic\s+the\s+gathering|\bmtg\b|lorcana|yu-?gi-?oh|pok[eé]mon|digimon""" +
        """|flesh\s+and\s+blood|one\s+piece\s+card|vanguard)""" +
        """\s+(?:booster|sobre|display|deck|mazo|cartas?|tcg|single)""" +
        """)\b""",
    ignoreCase = true,
)

private val PUZZLE = pyRegex(
    """\b(puzzles?|rompecabezas|booknook|book\s?nook|3d\s+puzzle)\b""",
    ignoreCase = true,
)

private val EXPANSION = pyRegex(
    """\b(""" +
        """expansi[oó]n(?:es)?|expansions?|\bexp\.?\b""" +
        """|ampliaci[oó]n|mini\s?expansi[oó]n""" +
        """|paquete\s+de\s+(?:expansi[oó]n|batalla)""" +
        """|escenarios?\s+adicionales?""" +
        """)\b""",
    ignoreCase = true,
)

private val GAME_NAME_GUARD = pyRegex(
    """\b(""" +
        """dice\s+game|juego\s+de\s+dados""" +
        """|dice\s+throne|dice\s+forge|dice\s+realms|troyes\s+dice|sagrada""" +
        """|king\s+of\s+tokyo|rey\s+de\s+los\s+dados|roll\s+player""" +
        """|qwixx|quixx|yahtzee|generala|marbles?\b""" +
        """|sleeping\s+gods|puzzle\s+strike""" +
        """)\b""",
    ignoreCase = true,
)

private val RULES = listOf(
    ACCESSORY to KIND_ACCESSORY,
    TCG to KIND_TCG,
    PUZZLE to KIND_PUZZLE,
    EXPANSION to KIND_EXPANSION,
)

/** Kind for a product title; anything unrecognised is a game. */
fun classify(title: String?): String {
    if (title == null || title.pyStrip().isEmpty()) return KIND_GAME
    if (GAME_NAME_GUARD.containsMatchIn(title)) return KIND_GAME
    return RULES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(title) }?.second ?: KIND_GAME
}

/** Sort key: lower ranks first, unknown kinds last. */
fun kindRank(kind: String): Int = KIND_ORDER.indexOf(kind).let { if (it < 0) KIND_ORDER.size else it }
