package cl.tablero.core.scrape

import cl.tablero.core.data.Scraped
import org.jsoup.nodes.Element

/*
 * Ports of the store-specific parsers in bespoke.py, in catalog order. Each
 * keeps its Python twin's quirks; the page fixtures hold them to it.
 */

private const val AGOTADO = "Agotado"
private const val OFERTA = "Oferta"

private fun cards(items: List<Element>, parse: (Element) -> Scraped?): List<Scraped> =
    items.mapNotNull { runCatching { parse(it) }.getOrNull() }

/** BeautifulSoup `class_=lambda c: c and needle in c`: called per token, so a substring of any token. */
private fun clsContains(needle: String): (Element) -> Boolean = { el -> el.classes().any { needle in it } }

/** WooCommerce/Woodmart. The stock span sits on the card's parent, not the card. */
fun updown(html: Element): List<Scraped> = cards(html.findAll("div", cls = "product-element-bottom")) { item ->
    val title = item.find("h3", cls = "wd-entities-title") ?: return@cards null
    val pc = item.find("span", cls = "price") ?: return@cards null
    val (orig, curr) = wooPrices(pc).let { (o, c) -> norm(o, c) }
    Scraped(
        title = txt(title), url = url(title.find("a", where = hasHref)),
        originalPrice = orig, currentPrice = curr,
        stockStatus = if (item.parent()?.find("span", cls = "out-of-stock") != null) AGOTADO else null,
        image = img(item.parent()), // the photo is in the card's sibling
    )
}

/** PrestaShop variant: article.product-container, stock from the availability div. */
fun drjuegos(html: Element): List<Scraped> = cards(html.findAll("article", cls = "product-container")) { item ->
    val title = item.find("h5", cls = "product-name") ?: return@cards null
    // 'price product-price' is a multi-class span, not the standard 'price'.
    val price = item.find("span", cls = "price product-price")
    val reg = item.find("span", cls = "regular-price")
    val (orig, curr) = norm(
        txt(reg).orNullIfEmpty() ?: txt(price),
        if (reg != null) txt(price) else null,
    )
    val avail = txt(item.find("div", cls = "product-availability")).orNullIfEmpty() ?: ""
    Scraped(
        title = txt(title), url = url(title.find("a", where = hasHref)),
        originalPrice = orig, currentPrice = curr,
        stockStatus = when {
            oos(avail) -> AGOTADO
            item.find("div", cls = "product-flags") != null -> OFERTA
            else -> null
        },
        image = img(item),
    )
}

/** Shopify (Dawn-like) grid with js-pagination-result cards. */
fun enroque(html: Element): List<Scraped> = cards(html.findAll("li", cls = "js-pagination-result")) { item ->
    val title = item.find("a", cls = "card-link") ?: return@cards null

    var orig: String? = null
    var curr: String? = null
    val pc = item.find("div", cls = "price__default")
    if (pc != null) {
        val was = txt(pc.find("s", cls = "price__was"))
        val cur = txt(pc.find("strong", cls = "price__current"))
        norm(was.orNullIfEmpty() ?: cur, if (was.orNullIfEmpty() != null) cur else null)
            .let { orig = it.first; curr = it.second }
    }

    val soldOut = item.find("span", cls = "product-label--sold-out") != null ||
        item.find("div", where = clsContains("price--sold-out")) != null
    Scraped(
        title = txt(title), url = url(title, "https://juegosenroque.cl"),
        originalPrice = orig, currentPrice = curr,
        stockStatus = when {
            soldOut -> AGOTADO
            curr.orNullIfEmpty() != null || item.find("span", cls = "product-label--sale") != null -> OFERTA
            else -> null
        },
        image = img(item, "https://juegosenroque.cl"),
    )
}

/** WooCommerce Blocks: wc-block-components-product-price. */
fun cartonespesados(html: Element): List<Scraped> = cards(html.findAll("li", cls = "wc-block-product")) { item ->
    val title = item.find("h3", cls = "wp-block-post-title") ?: return@cards null
    val (orig, curr) = wooPrices(item.find("div", cls = "wc-block-components-product-price"))
        .let { (o, c) -> norm(o, c) }
    val cls = item.classes()
    Scraped(
        title = txt(title), url = url(title.find("a", where = hasHref)),
        originalPrice = orig, currentPrice = curr,
        stockStatus = when {
            "outofstock" in cls -> AGOTADO
            "onsale" in cls || curr.orNullIfEmpty() != null -> OFERTA
            else -> null
        },
        image = img(item),
    )
}

/** Custom CMS. The discounted price is an <i> inside the price span; the rest is the original. */
fun lafortalezapuq(html: Element): List<Scraped> = cards(html.findAll("figure", cls = "product")) { item ->
    val title = item.find("h5") ?: return@cards null
    val dp = item.find("span", cls = "product-price-discount")
    val (orig, curr) = if (dp != null) {
        val sale = dp.find("i")
        val current = txt(sale)
        sale?.remove() // what is left of the span is the original price
        norm(txt(dp), current)
    } else {
        norm(txt(item.find("span", cls = "product-price")), null)
    }
    Scraped(
        title = txt(title), url = url(item.find("a", where = hasHref), "https://www.lafortalezapuq.cl"),
        originalPrice = orig, currentPrice = curr, stockStatus = null,
        image = img(item, "https://www.lafortalezapuq.cl"),
    )
}

/** PrestaShop 1.6 grid. The title is the link's title attribute when it has one. */
fun shivano(html: Element): List<Scraped> = cards(html.findAll("li", cls = "ajax_block_product")) { item ->
    val link = item.find("a", cls = "product-name") ?: return@cards null
    val title = link.attr("title").orNullIfEmpty() ?: txt(link.find("span", cls = "list-name"))

    var orig: String? = null
    var curr: String? = null
    val pc = item.find("div", cls = "content_price")
    if (pc != null) {
        norm(txt(pc.find("span", cls = "old-price")), txt(pc.find("span", cls = "price")))
            .let { orig = it.first; curr = it.second }
    }

    val button = item.find("a", cls = "ajax_add_to_cart_button")
    val isOos = button == null || oos(txt(item.find("span", cls = "availability")))
    Scraped(
        title = title, url = url(link, "https://shivano.cl"),
        originalPrice = orig, currentPrice = curr,
        stockStatus = if (isOos) AGOTADO else if (curr.orNullIfEmpty() != null) OFERTA else null,
        image = img(item, "https://shivano.cl"),
    )
}

private const val CALABOZO = "https://www.calabozotienda.cl"

/** Bootstrap cards: struck-through span is the list price, `font-color` the sale one. */
fun calabozotienda(html: Element): List<Scraped> = cards(html.findAll("div", cls = "card mb-3 box-shadow")) { item ->
    val title = item.find("p", cls = "card-text")?.find("span") ?: return@cards null
    val was = txt(item.find("span", where = { it.attr("style").contains("line-through") })).orNullIfEmpty()
    val now = txt(item.find("span", cls = "font-color")).orNullIfEmpty()
    val (orig, curr) = norm(was ?: now, if (was != null) now else null)
    val stock = when {
        item.find("span", cls = "badge-danger") != null -> AGOTADO
        curr != null || item.find("span", cls = "burbuja-descuento") != null -> OFERTA
        else -> null
    }
    Scraped(
        title = txt(title), url = url(item.find("a", where = hasHref), CALABOZO),
        originalPrice = orig, currentPrice = curr, stockStatus = stock, image = img(item, CALABOZO),
    )
}

private const val ARAUCANIA = "https://araucaniagaming.cl"

/** WooCommerce, but a lone price is the whole `price` span's text, not its <bdi>. */
fun araucania(html: Element): List<Scraped> = cards(html.findAll("li", cls = "product")) { item ->
    val title = item.find("h3", cls = "woocommerce-loop-product__title") ?: return@cards null
    var was: String? = null
    var now: String? = null
    item.find("span", cls = "price")?.let { pc ->
        val del = pc.find("del")
        val ins = pc.find("ins")
        if (del != null && ins != null) { was = txt(del); now = txt(ins) } else now = txt(pc)
    }
    val (orig, curr) = norm(was, now)
    val soldOut = "outofstock" in item.classes() || oos(txt(item.find("span", cls = "stock-label")))
    Scraped(
        title = txt(title), url = url(title.find("a"), ARAUCANIA),
        originalPrice = orig, currentPrice = curr, stockStatus = saleOr(soldOut, curr, false),
        image = img(item, ARAUCANIA),
    )
}
