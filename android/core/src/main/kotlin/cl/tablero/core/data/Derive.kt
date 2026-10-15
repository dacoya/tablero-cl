package cl.tablero.core.data

/*
 * Port of scripts/derive.py and scripts/validation.py: one scraped record into
 * one database row, and the sanity checks that keep impossible prices out.
 */

/** What a store parser produces: the store's own strings, unparsed. */
data class Scraped(
    val title: String?,
    val url: String?,
    val originalPrice: String? = null,
    val currentPrice: String? = null,
    val stockStatus: String? = null,
    val flag: String? = null,
)

/** A product row as ingest writes it. */
data class ProductRow(
    val store: String,
    val urlCanon: String,
    val url: String,
    val titleRaw: String,
    val title: String,
    val norm: String,
    val kind: String,
    val priceOriginal: Double?,
    val priceCurrent: Double?,
    val priceEff: Double?,
    val inStock: Boolean,
    val flag: String?,
)

private const val OUT_OF_STOCK = "agotado"
private val VALID_FLAGS = setOf("new", "restock")

private val SCHEME = Regex("^https?://")
private val WWW = Regex("""^www\.""")

/** Scheme, www, query, fragment and trailing slash dropped. See derive.canonical_url. */
fun canonicalUrl(url: String?): String {
    if (url == null) return ""
    val u = WWW.replace(SCHEME.replace(url.pyStrip().lowercase(), ""), "")
    return u.substringBefore('?').substringBefore('#').trimEnd('/')
}

private fun text(value: String?): String? = value?.pyStrip()?.ifEmpty { null }

private fun inStock(status: String?): Boolean = (status ?: "").pyStrip().lowercase() != OUT_OF_STOCK

private fun isTruncated(title: String): Boolean = title.pyRstrip().let { it.endsWith("...") || it.endsWith("…") }

private val PAGE_EXT = pyRegex("""\.(html?|php|aspx)$""", ignoreCase = true)
private val LEADING_ID = pyRegex("""^\d+[-_]""")
private val TRAILING_ID = pyRegex("""[-_]p?\d+$""")

/** Product name recovered from a URL slug. See derive.slug_name. */
fun slugName(url: String?): String {
    if (url.isNullOrEmpty()) return ""
    var tail = url.trimEnd('/').substringAfterLast('/')
    tail = PAGE_EXT.replace(tail, "")
    tail = LEADING_ID.replace(tail, "")
    tail = TRAILING_ID.replace(tail, "")
    return tail.replace('-', ' ').replace('_', ' ').pyStrip()
}

/** One scraped record as a row, or null when it has no usable title. See derive.derive. */
fun derive(store: String, record: Scraped): ProductRow? {
    val titleRaw = text(record.title) ?: return null
    val title = cleanTitle(titleRaw)!!
    var norm = normalize(title)
    if (norm.isEmpty()) return null

    val url = text(record.url) ?: ""

    // A truncated title is not a reliable identity: match on the slug instead.
    if (isTruncated(titleRaw)) {
        val fromSlug = normalize(cleanTitle(slugName(url)))
        if (fromSlug.isNotEmpty() && fromSlug.startsWith(norm) && fromSlug != norm) norm = fromSlug
    }

    val original = parsePrice(record.originalPrice)
    val current = parsePrice(record.currentPrice)
    return ProductRow(
        store = store,
        urlCanon = canonicalUrl(url).ifEmpty { "urn:tablero:$store:$norm" },
        url = url,
        titleRaw = titleRaw,
        title = title,
        norm = norm,
        kind = classify(title),
        priceOriginal = original,
        priceCurrent = current,
        priceEff = current ?: original,
        inStock = inStock(record.stockStatus),
        flag = record.flag?.takeIf { it in VALID_FLAGS },
    )
}

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

const val MAX_PLAUSIBLE_DISCOUNT = 0.90

/** Rejection reason for a row, or null when sane. See validation.check. */
fun check(row: ProductRow): String? {
    val original = row.priceOriginal
    val current = row.priceCurrent
    return when {
        original != null && original <= 0 -> "nonpositive_original"
        current != null && current <= 0 -> "nonpositive_current"
        original == null && current == null -> "no_price"
        original != null && current != null && current > original -> "offer_above_original"
        original != null && current != null && original > 0 &&
            (original - current) / original > MAX_PLAUSIBLE_DISCOUNT -> "discount_over_90pct"
        else -> null
    }
}

/** Rows split into (clean, reason -> count). See validation.partition. */
fun partition(rows: List<ProductRow>): Pair<List<ProductRow>, Map<String, Int>> {
    val clean = mutableListOf<ProductRow>()
    val reasons = linkedMapOf<String, Int>()
    for (row in rows) {
        val reason = check(row)
        if (reason == null) clean += row else reasons.merge(reason, 1, Int::plus)
    }
    return clean to reasons
}
