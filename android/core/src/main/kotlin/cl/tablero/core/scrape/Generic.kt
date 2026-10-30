package cl.tablero.core.scrape

import cl.tablero.core.data.Scraped
import cl.tablero.core.data.pyStrip
import org.jsoup.nodes.Element

/*
 * Port of the shared helpers and the six generic parsers in parsers.py.
 * Each mirrors its Python counterpart line for line; the store-specific
 * reasons behind them are documented there.
 */

typealias Parser = (Element) -> List<Scraped>

private val OOS_WORDS = listOf("agotado", "sin stock", "out of stock", "fuera de stock", "no disponible")

private const val AGOTADO = "Agotado"
private const val OFERTA = "Oferta"

/** `_norm`: drop a current price equal to the original; a lone current becomes the original. */
fun norm(orig: String?, curr: String?): Pair<String?, String?> = when {
    orig == null && curr != null -> curr to null
    orig == curr -> orig to null
    else -> orig to curr
}

/** `_oos`: the text mentions an out-of-stock phrase. */
fun oos(text: String?): Boolean = (text ?: "").lowercase().let { t -> OOS_WORDS.any { it in t } }

/** Parse each card; a card that throws is skipped, as Python's per-item try/except does. */
private fun cards(items: List<Element>, parse: (Element) -> Scraped?): List<Scraped> =
    items.mapNotNull { runCatching { parse(it) }.getOrNull() }

// ── Price and stock helpers ───────────────────────────────────────────────────

/** `_woo_prices`: <del> original + <ins> sale, else <bdi> or the raw text. */
fun wooPrices(container: Element?): Pair<String?, String?> {
    if (container == null) return null to null
    val del = container.find("del")
    val ins = container.find("ins")
    if (del != null && ins != null) return txt(del) to txt(ins)
    val raw = txt(container.find("bdi")).orNullIfEmpty()
        ?: txt(container)!!.replace("IVA INC", "").pyStrip().orNullIfEmpty()
    return raw to null
}

/** `_presta_prices`: span.regular-price original + span.price sale. */
fun prestaPrices(item: Element): Pair<String?, String?> {
    val reg = txt(item.find("span", cls = "regular-price"))
    val price = txt(item.find("span", cls = "price"))
    return norm(reg.orNullIfEmpty() ?: price, if (reg.orNullIfEmpty() != null) price else null)
}

/** `_stock_flags`: PrestaShop ul.product-flags. */
fun stockFlags(item: Element, oosCls: String = "out_of_stock", discountCls: String = "discount",
               curr: String? = null): String? {
    val flags = item.find("ul", cls = "product-flags") ?: return null
    if (flags.find("li", cls = oosCls) != null) return AGOTADO
    if (flags.find("li", cls = discountCls) != null || curr.orNullIfEmpty() != null) return OFERTA
    return null
}

/** `_stock_cls`: WooCommerce item classes. */
fun stockCls(cls: List<String>, curr: String?, saleCls: String = "sale", oosCls: String = "outofstock",
             extraSale: Boolean = false): String? = when {
    oosCls in cls -> AGOTADO
    saleCls in cls || curr.orNullIfEmpty() != null || extraSale -> OFERTA
    else -> null
}

// ── Generic parsers ───────────────────────────────────────────────────────────

/** `_parse_woo_li`: WooCommerce <li class="product"> grids. */
fun parseWooLi(
    html: Element,
    linkCls: String? = "woocommerce-LoopProduct-link",
    extraOos: ((Element) -> Boolean)? = null,
    extraSale: ((Element) -> Boolean)? = null,
): List<Scraped> = cards(html.findAll("li", cls = "product")) { item ->
    val title = item.find("h2", cls = "woocommerce-loop-product__title") ?: return@cards null
    val (orig, curr) = wooPrices(item.find("span", cls = "price")).let { (o, c) -> norm(o, c) }
    val cls = item.classes()
    val isOos = "outofstock" in cls || (extraOos?.invoke(item) ?: false)
    val sale = extraSale?.invoke(item) ?: false
    val link = (if (linkCls != null) item.find("a", cls = linkCls, where = hasHref) else null)
        ?: item.find("a", where = hasHref)
    Scraped(
        title = txt(title), url = url(link), originalPrice = orig, currentPrice = curr,
        stockStatus = if (isOos) AGOTADO else stockCls(cls, curr, extraSale = sale),
        image = img(item),
    )
}

/** `_parse_presta`: PrestaShop <article class="product-miniature"> grids. */
fun parsePresta(
    html: Element,
    titleTag: String = "h2",
    titleCls: String = "product-title",
    itemCls: String = "product-miniature",
    oosFlag: String = "out_of_stock",
    extraOos: ((Element) -> Boolean)? = null,
): List<Scraped> = cards(html.findAll("article", cls = itemCls)) { item ->
    val title = item.find(titleTag, cls = titleCls) ?: return@cards null
    val (orig, curr) = prestaPrices(item)
    Scraped(
        title = txt(title), url = url(title.find("a", where = hasHref)),
        originalPrice = orig, currentPrice = curr,
        stockStatus = if (extraOos?.invoke(item) == true) AGOTADO else stockFlags(item, oosCls = oosFlag, curr = curr),
        image = img(item),
    )
}

/** `_parse_bs`: Bootic-style "bs-collection" grids. */
fun parseBs(html: Element, itemTag: String = "section", itemCls: String = "grid__item",
            baseUrl: String = ""): List<Scraped> = cards(html.findAll(itemTag, cls = itemCls)) { item ->
    val title = item.find("h3", cls = "bs-collection__product-title") ?: return@cards null

    var orig: String? = null
    var curr: String? = null
    val pc = item.find("div", "section", cls = "bs-collection__product-price")
    if (pc != null) {
        val was = pc.find("del", where = { el -> el.classes().any { "old-price" in it } })
        val fin = pc.find("div", cls = "bs-collection__product-final-price")
        if (was != null) {
            orig = txt(was); curr = txt(fin)
        } else {
            orig = txt(fin)
        }
    }
    norm(orig, curr).let { orig = it.first; curr = it.second }

    val wrapper = item.find("div", cls = "bs-collection__product")
    val cls = item.classes() + (wrapper?.classes() ?: emptyList())
    val check = listOf(
        txt(item.find("div", cls = "bs-collection__product-notice")),
        txt(item.find("div", cls = "bs-stock")),
        txt(item.find("div", cls = "bs-collection__stock")),
    ).filter { !it.isNullOrEmpty() }.joinToString(" ")
    val isOos = oos(check) || "out-of-stock" in cls || "outStock" in cls
    val link = title.find("a", where = hasHref) ?: item.find("a", where = hasHref)
    Scraped(
        title = txt(title), url = url(link, baseUrl), originalPrice = orig, currentPrice = curr,
        stockStatus = when {
            isOos -> AGOTADO
            curr.orNullIfEmpty() != null || "has-discount" in cls -> OFERTA
            else -> null
        },
        image = img(item, baseUrl),
    )
}

/** `_parse_product_block`: Jumpseller-style product-block cards. */
fun parseProductBlock(
    html: Element,
    itemTag: String = "article",
    itemCls: String = "product-block",
    nameCls: String = "product-block__name",
    pricingCls: String? = "product-block__pricing",
    oldCls: String = "product-block__price--old",
    newCls: String = "product-block__price--new",
    singleCls: String = "product-block__price",
    labelCls: String? = "product-block__label--status",
    baseUrl: String = "",
): List<Scraped> = cards(html.findAll(itemTag, cls = itemCls)) { item ->
    val title = item.find("a", cls = nameCls) ?: return@cards null

    var origTxt: String? = null
    var currTxt: String? = null
    val pc = if (pricingCls != null) item.find(cls = pricingCls) else item
    if (pc != null) {
        val old = pc.find(cls = oldCls)
        val new = pc.find(cls = newCls)
        if (old != null && new != null) {
            origTxt = txt(old); currTxt = txt(new)
        } else {
            currTxt = txt(pc.find(cls = singleCls)).orNullIfEmpty() ?: txt(pc)
        }
    }
    val (orig, curr) = norm(origTxt, currTxt)

    val label = if (labelCls != null) txt(item.find(cls = labelCls)) else null
    val button = item.find("button", cls = "product-block__button--add-to-cart")
    val isOos = oos(label) ||
        item.findAll("div", cls = "product-block__label").any { oos(txt(it)) } ||
        (button != null && button.hasAttr("disabled"))
    Scraped(
        title = txt(title), url = url(title, baseUrl), originalPrice = orig, currentPrice = curr,
        stockStatus = if (isOos) AGOTADO else if (curr.orNullIfEmpty() != null) OFERTA else null,
        image = img(item, baseUrl),
    )
}

/** `_parse_shopify_card`: Shopify Debut-family grids. */
fun parseShopifyCard(html: Element, baseUrl: String = ""): List<Scraped> =
    cards(html.findAll("li", cls = "grid__item")) { item ->
        val title = item.find("div", cls = "grid-view-item__title") ?: return@cards null

        // The struck-through figure is the only unambiguous sign of a sale.
        val was = item.find("s", cls = "price-item--regular")
        val (orig, curr) = if (was != null && txt(was).orNullIfEmpty() != null) {
            norm(txt(was), txt(item.find("span", cls = "price-item--sale")))
        } else {
            val regular = item.find("div", cls = "price__regular")
            norm(txt(regular?.find("span", cls = "price-item--regular")), null)
        }

        val dl = item.find("dl", cls = "price")
        Scraped(
            title = txt(title), url = url(item.find("a", cls = "grid-view-item__link"), baseUrl),
            originalPrice = orig, currentPrice = curr,
            stockStatus = if (dl != null && "price--sold-out" in dl.classes()) AGOTADO else null,
            image = img(item, baseUrl),
        )
    }

/** `_parse_dawn`: Shopify Dawn grid. The <s> is on every card; equal pairs mean no sale. */
fun parseDawn(html: Element, baseUrl: String = ""): List<Scraped> =
    cards(html.findAll("li", cls = "grid__item")) { item ->
        val title = item.find("h3", cls = "card__heading") ?: return@cards null

        val was = item.find("s", cls = "price-item--regular")
        val (orig, curr) = if (was != null && txt(was).orNullIfEmpty() != null) {
            norm(txt(was), txt(item.find("span", cls = "price-item--sale")))
        } else {
            val regular = item.find("div", cls = "price__regular")
            norm(txt(regular?.find("span", cls = "price-item--regular")), null)
        }

        val price = item.find("div", cls = "price")
        Scraped(
            title = txt(title), url = url(title.find("a"), baseUrl),
            originalPrice = orig, currentPrice = curr,
            stockStatus = if (price != null && "price--sold-out" in price.classes()) AGOTADO else null,
            image = img(item, baseUrl),
        )
    }

/** `_parse_product_block_simple`: the stripped-down product-block grid. */
fun parseProductBlockSimple(html: Element, baseUrl: String): List<Scraped> =
    cards(html.findAll("article", cls = "product-block")) { item ->
        val title = item.find("a", cls = "product-block__name") ?: return@cards null

        var origTxt: String? = null
        var currTxt: String? = null
        val pc = item.find("div", cls = "product-block__pricing")
        if (pc != null) {
            val old = pc.find("div", cls = "product-block__price--old")
            val new = pc.find("div", cls = "product-block__price--new")
            if (old != null && new != null) {
                origTxt = txt(old); currTxt = txt(new)
            } else {
                currTxt = txt(pc.find("div", cls = "product-block__price"))
            }
        }
        val (orig, curr) = norm(origTxt, currTxt)

        val label = txt(item.find("div", cls = "product-block__label--status"))
        Scraped(
            title = txt(title), url = url(title, baseUrl), originalPrice = orig, currentPrice = curr,
            stockStatus = if (oos(label)) AGOTADO else if (curr.orNullIfEmpty() != null) OFERTA else null,
            image = img(item, baseUrl),
        )
    }
