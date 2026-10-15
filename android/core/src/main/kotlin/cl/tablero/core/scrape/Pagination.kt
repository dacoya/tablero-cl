package cl.tablero.core.scrape

/**
 * URL of one catalog page. Port of `scrape.build_url`, pinned by
 * tests/fixtures/url_vectors.json.
 *
 * Order matters and mirrors Python: the two styles that put the page in the
 * URL even for page 1 are checked before the page-1 shortcut, and an unknown
 * style only fails past page 1.
 */
fun buildUrl(baseUrl: String, style: String, page: Int): String {
    val sep = if ('?' in baseUrl) '&' else '?'
    return when {
        style == "gatoarcano" -> "$baseUrl/?jsf=epro-archive-products&pagenum=$page"
        style == "calabozo" -> "$baseUrl/$page"
        page == 1 -> baseUrl
        style == "shopify" || style == "page_param" -> "$baseUrl?page=$page"
        style == "p" -> "$baseUrl?p=$page"
        style == "woo" -> "$baseUrl/page/$page/"
        style == "product-page" -> "$baseUrl${sep}product-page=$page"
        else -> throw IllegalArgumentException("Unknown pagination style: $style")
    }
}
