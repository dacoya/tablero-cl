package cl.tablero.core.data

/**
 * Python `re` and `str` semantics, where Kotlin's quietly differ.
 *
 * The title and price logic was tuned against Python, and the port has to give
 * byte-identical answers or a re-scrape on the phone would look like a
 * catalog-wide change. Three differences matter:
 *
 *  - Python's `\w`, `\s`, `\d` and `\b` are Unicode-aware; Java's are ASCII.
 *    "Ampliación" would stop matching `\bampliaci[oó]n\b` at the accent.
 *  - The usual fix, the `(?U)` flag, is not supported by Android's ICU-backed
 *    regex engine. So instead of a flag, [pyRegex] rewrites those four escapes
 *    into explicit Unicode classes that both engines read the same way.
 *  - `str.strip()` strips Python's whitespace set, which is not Kotlin's
 *    `isWhitespace` (U+0085 differs).
 *
 * Keeping the Python pattern text verbatim and translating it here means the
 * two sources can be compared side by side.
 */

// Python `\w`: letters, digits and numerics, underscore. Not combining marks.
private const val WORD = """\p{L}\p{N}_"""

// Python `\s` / str.isspace().
private const val SPACE =
    """\t\n\u000B\f\r\u001C-\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000"""

private const val DIGIT = """\p{Nd}"""

private const val BOUNDARY = "(?:(?<=[$WORD])(?![$WORD])|(?<![$WORD])(?=[$WORD]))"

/** Compile a pattern written for Python's `re`, with Python's meaning. */
fun pyRegex(pattern: String, ignoreCase: Boolean = false): Regex {
    val out = StringBuilder()
    var inClass = false
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        if (c == '\\' && i + 1 < pattern.length) {
            val next = pattern[i + 1]
            out.append(
                when (next) {
                    'w' -> if (inClass) WORD else "[$WORD]"
                    's' -> if (inClass) SPACE else "[$SPACE]"
                    'd' -> if (inClass) DIGIT else "[$DIGIT]"
                    'b' -> {
                        require(!inClass) { "\\b inside a class means backspace in Python" }
                        BOUNDARY
                    }
                    'W', 'S', 'D', 'B' -> throw IllegalArgumentException("\\$next is not translated")
                    else -> "\\$next"
                }
            )
            i += 2
            continue
        }
        if (c == '[' && !inClass) inClass = true else if (c == ']' && inClass) inClass = false
        out.append(c)
        i++
    }
    // Kotlin adds UNICODE_CASE to IGNORE_CASE on the JVM; Android always has it.
    return if (ignoreCase) Regex(out.toString(), RegexOption.IGNORE_CASE) else Regex(out.toString())
}

fun isPySpace(c: Char): Boolean =
    c in '\t'..'\r' || c in '\u001C'..' ' || c == '\u0085' || c == '\u00A0' ||
        c == '\u1680' || c in '\u2000'..'\u200A' || c == '\u2028' || c == '\u2029' ||
        c == '\u202F' || c == '\u205F' || c == '\u3000'

/** `str.strip()` with no arguments. */
fun String.pyStrip(): String = trim(::isPySpace)

/** `str.rstrip()` with no arguments. */
fun String.pyRstrip(): String = trimEnd(::isPySpace)

/** `len(str)`: code points, not UTF-16 units. */
val String.pyLen: Int get() = codePointCount(0, length)

/** `re.match`: a match that starts at index 0, not anywhere. */
fun Regex.matchesAtStart(input: CharSequence): Boolean = toPattern().matcher(input).lookingAt()
