package cl.tablero.app

/** Chilean pesos, dot thousands, no decimals. "-" for nothing. */
fun money(value: Any?): String {
    val v = (value as? Number)?.toDouble() ?: return "-"
    return "$" + "%,.0f".format(java.util.Locale.ROOT, v).replace(',', '.')
}

/** A count with Chilean thousands separators: 4.891. */
fun count(n: Any?): String = "%,d".format(java.util.Locale.ROOT, (n as? Number)?.toLong() ?: 0L).replace(',', '.')

/** "15 oct 2026" for a unix timestamp. */
fun date(ts: Any?): String {
    val t = (ts as? Number)?.toLong() ?: return "-"
    return java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.forLanguageTag("es-CL")).format(java.util.Date(t * 1000))
}

/**
 * Store links come from scraped pages, so only plain web links are opened:
 * an intent:, javascript: or file: URL there would be the page, not us,
 * deciding what the phone does.
 */
fun isWebUrl(url: Any?): Boolean = (url as? String)?.let {
    it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
} == true

/** A discount: "-25%". */
fun pct(value: Any?): String? = (value as? Number)?.let { "-%.0f%%".format(java.util.Locale.ROOT, it.toDouble()) }

/** "hace 3 h" style age of a unix timestamp. */
fun ago(ts: Any?): String {
    val t = (ts as? Number)?.toLong() ?: return "nunca"
    val s = System.currentTimeMillis() / 1000 - t
    return when {
        s < 3600 -> "hace ${maxOf(1, s / 60)} min"
        s < 86_400 -> "hace ${s / 3600} h"
        else -> "hace ${s / 86_400} d"
    }
}

val KIND_LABELS = mapOf(
    "expansion" to "expansión", "tcg" to "TCG", "puzzle" to "puzzle", "accessory" to "accesorio",
)
