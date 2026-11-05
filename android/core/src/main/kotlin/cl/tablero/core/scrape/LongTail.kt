package cl.tablero.core.scrape

import cl.tablero.core.data.Scraped
import cl.tablero.core.data.pyStrip
import org.jsoup.nodes.Element

/*
 * The remaining store-specific parsers in bespoke.py, the catalog's long tail.
 * Same rules as Bespoke.kt: each mirrors its Python twin, quirks included,
 * and the page fixtures hold it to that.
 */

private const val AGOTADO = "Agotado"
private const val OFERTA = "Oferta"

private fun cards(items: List<Element>, parse: (Element) -> Scraped?): List<Scraped> =
    items.mapNotNull { runCatching { parse(it) }.getOrNull() }

/** BeautifulSoup `class_=lambda c: c and needle in c`: a substring of any one token. */
private fun clsContains(needle: String): (Element) -> Boolean = { el -> el.classes().any { needle in it } }

private fun attrIs(name: String, value: String): (Element) -> Boolean = { it.attr(name) == value && it.hasAttr(name) }

fun saleOr(isOos: Boolean, curr: String?, extraSale: Boolean = false) = when {
    isOos -> AGOTADO
    curr.orNullIfEmpty() != null || extraSale -> OFERTA
    else -> null
}

fun elpatiogeek(html: Element): List<Scraped> = cards(html.findAll("div", cls = "grid-item")) { item ->
    val title = txt(item.find("p")).orNullIfEmpty() ?: return@cards null
    val pc = item.find("div", cls = "product-item--price")
    var pval: String? = null
    var isSale = false
    if (pc != null) {
        val small = pc.find("small")
        val h1 = pc.find("span", cls = "h1")
        if (small != null) {
            pval = txt(small)
        } else if (h1 != null) {
            val spans = h1.findAll("span", cls = "visually-hidden")
            pval = if (spans.isNotEmpty()) txt(spans.last()) else txt(h1)
        }
        val vh = pc.find("span", cls = "visually-hidden")
        if (vh != null && "venta" in (txt(vh) ?: "").lowercase()) isSale = true
    }
    val saleTag = item.find("div", cls = "sale-tag")
    val (orig, curr) = (if (isSale && saleTag != null) txt(saleTag) to pval else pval to null)
        .let { (o, c) -> norm(o, c) }
    val cls = item.classes()
    Scraped(
        title = title, url = url(item.find("a", cls = "product-grid-item"), "https://www.elpatiogeek.cl"),
        originalPrice = orig, currentPrice = curr,
        stockStatus = saleOr("sold-out" in cls, curr, "on-sale" in cls),
        image = img(item, "https://www.elpatiogeek.cl"),
    )
}

fun devir(html: Element): List<Scraped> = cards(html.findAll("li", cls = "product-item")) { item ->
    val title = item.find("a", cls = "product-item-link") ?: return@cards null
    var orig: String? = null
    var curr: String? = null
    val pc = item.find("div", cls = "price-box")
    if (pc != null) {
        val old = pc.find("span", cls = "old-price")
        val new = pc.find("span", cls = "special-price")
        val pair = if (old != null && new != null) {
            norm(txt(old.find("span", cls = "price")), txt(new.find("span", cls = "price")))
        } else {
            norm(txt(pc.find("span", cls = "price")), null)
        }
        orig = pair.first; curr = pair.second
    }
    val action = (txt(item.find("div", cls = "actions-primary")) ?: "").lowercase()
    val isOos = "avísame" in action || "avisame" in action || item.find("div", cls = "stock unavailable") != null
    Scraped(
        title = txt(title), url = url(title, "https://devir.cl"), originalPrice = orig, currentPrice = curr,
        stockStatus = saleOr(isOos, curr), image = img(item, "https://devir.cl"),
    )
}

fun darkhobbies(html: Element): List<Scraped> = cards(html.findAll("product-card")) { item ->
    val title = item.find("h3", cls = "h4") ?: item.find("a", where = attrIs("ref", "productTitleLink"))
        ?: return@cards null
    var orig: String? = null
    var curr: String? = null
    val pc = item.find("product-price")
    if (pc != null) {
        norm(txt(pc.find("span", cls = "compare-at-price")), txt(pc.find("span", cls = "price")))
            .let { orig = it.first; curr = it.second }
    }
    val button = item.find("button", where = attrIs("name", "add"))
    val badges = item.findAll(cls = "product-badges__badge").joinToString(" ") { txt(it)!! }
    val isOos = (button != null && button.hasAttr("disabled")) || oos(badges) || oos(txt(button))
    Scraped(
        title = txt(title), url = url(item.find("a", cls = "product-card__link"), "https://www.darkhobbies.cl"),
        originalPrice = orig, currentPrice = curr, stockStatus = saleOr(isOos, curr),
        image = img(item, "https://www.darkhobbies.cl"),
    )
}

/** Shopify with inverted naming: price__regular holds the sale price, price__sale the original. */
fun piedrabruja(html: Element): List<Scraped> = cards(html.findAll("div", cls = "product-card")) { item ->
    val title = item.find("a", cls = "product-card__title") ?: return@cards null
    val pc = item.find("div", cls = "price")
    val reg = pc?.find("span", cls = "price__regular")
    val sale = pc?.find("span", cls = "price__sale")
    val (orig, curr) = norm(if (sale != null) txt(sale) else txt(reg), if (sale != null) txt(reg) else null)
    val buttonText = item.find("button", cls = "cowlendar-add-to-cart")?.find("span", cls = "hidden md:block")
    val isOos = buttonText != null && oos(txt(buttonText))
    val onSaleBadge = item.find("div", cls = "badges")?.find("span", cls = "badge--onsale") != null
    Scraped(
        title = txt(title), url = url(title, "https://piedrabruja.cl"), originalPrice = orig, currentPrice = curr,
        stockStatus = saleOr(isOos, curr, onSaleBadge), image = img(item, "https://piedrabruja.cl"),
    )
}

fun manahouse(html: Element): List<Scraped> = cards(html.findAll("div", cls = "product-grid-item")) { item ->
    val title = item.find("a", cls = "product-grid-item__title") ?: return@cards null
    var orig: String? = null
    var curr: String? = null
    val pc = item.find(cls = "product-grid-item__price")
    if (pc != null) {
        val was = pc.find("s", "del")
        val pair = if (was != null) {
            val origText = txt(was)
            was.remove() // what is left of the block is the sale price
            norm(origText, txt(pc))
        } else {
            norm(txt(pc), null)
        }
        orig = pair.first; curr = pair.second
    }
    val button = item.find("button", where = attrIs("name", "add"))
    val badges = item.findAll(where = { el -> el.classes().any { "badge" in it.lowercase() || "label" in it.lowercase() } })
    val isOos = (button != null && button.hasAttr("disabled")) || badges.any { oos(txt(it)) }
    Scraped(
        title = txt(title), url = url(title, "https://manahouse.cl"), originalPrice = orig, currentPrice = curr,
        stockStatus = saleOr(isOos, curr), image = img(item, "https://manahouse.cl"),
    )
}

/** A sale shows `span.price` (offer) and `span.compare-at-price` (list), each behind a hidden label. */
fun tentami(html: Element): List<Scraped> = cards(html.findAll("product-card")) { item ->
    val title = item.find("h3", cls = "h4") ?: return@cards null
    val pc = item.find("product-price")
    pc?.findAll("span", cls = "visually-hidden")?.forEach { it.remove() }
    val (orig, curr) = if (pc != null) {
        norm(txt(pc.find("span", cls = "compare-at-price")), txt(pc.find("span", cls = "price")))
    } else null to null
    val link = item.find("a", where = hasHref)
    Scraped(
        title = txt(title), url = if (link != null) url(link, "https://tentami.cl") else null,
        originalPrice = orig, currentPrice = curr, stockStatus = null, image = img(item),
    )
}

fun kaiojuegos(html: Element): List<Scraped> = cards(html.findAll("div", cls = "thumbnail-container")) { item ->
    val title = item.find("h3", cls = "product-title") ?: return@cards null
    val pc = item.find("div", cls = "product-price-and-shipping")
    val reg = pc?.let { txt(it.find("span", cls = "regular-price")) }
    val price = pc?.let { txt(it.find("span", cls = "price")) }
    val (orig, curr) = norm(reg.orNullIfEmpty() ?: price, if (reg.orNullIfEmpty() != null) price else null)
    val flags = txt(item.find("ul", cls = "product-flags")).orNullIfEmpty() ?: ""
    val button = item.find("button", cls = "add-to-cart")
    val isOos = oos(flags) || (button != null && button.hasAttr("disabled"))
    Scraped(
        title = txt(title), url = url(title.find("a", where = hasHref), "https://kaiojuegos.cl"),
        originalPrice = orig, currentPrice = curr,
        stockStatus = saleOr(isOos, curr, "oferta" in flags.lowercase()), image = img(item, "https://kaiojuegos.cl"),
    )
}

fun magicsur(html: Element): List<Scraped> = cards(html.findAll("article", cls = "product-miniature")) { item ->
    val title = item.find("h2", cls = "product-title") ?: return@cards null
    val pc = item.find("div", cls = "product-price-and-shipping")
    val origTxt = pc?.let { txt(it.find("span", cls = "regular-price")) }
    val currTxt = pc?.let { txt(it.find("span", cls = "product-price") ?: it.find("span", cls = "price")) }
    val (orig, curr) = norm(origTxt, currTxt)
    val check = listOf(
        txt(item.find("div", cls = "product-availability")),
        txt(item.find("ul", cls = "product-flags")),
    ).filter { !it.isNullOrEmpty() }.joinToString(" ")
    Scraped(
        title = txt(title), url = url(title.find("a", where = hasHref), "https://www.magicsur.cl"),
        originalPrice = orig, currentPrice = curr, stockStatus = saleOr(oos(check), curr),
        image = img(item, "https://www.magicsur.cl"),
    )
}

fun guildreams(html: Element): List<Scraped> = cards(html.findAll("div", cls = "bs-product")) { item ->
    val title = item.find("h2") ?: return@cards null
    var origTxt: String? = null
    var currTxt: String? = null
    val pc = item.find("div", cls = "bs-product-price")
    if (pc != null) {
        origTxt = txt(pc.find("div", cls = "bs-product-old-price"))
        currTxt = txt(pc.find("div", cls = "bs-product-final-price"))
    }
    val (orig, curr) = norm(origTxt, currTxt)
    val button = item.find("button", where = attrIs("data-bs", "cart.add.collection"))
    Scraped(
        title = txt(title), url = url(item.find("a", where = hasHref), "https://www.guildreams.com"),
        originalPrice = orig, currentPrice = curr, stockStatus = saleOr(oos(txt(item)) || button == null, curr),
        image = img(item, "https://www.guildreams.com"),
    )
}

/** WooCommerce/Woodmart, like updown but with the card class on the whole item. */
fun ludipuerto(html: Element): List<Scraped> = cards(html.findAll("div", cls = "product-grid-item")) { item ->
    val title = item.find("h3", cls = "wd-entities-title") ?: return@cards null
    val (orig, curr) = wooPrices(item.find("span", cls = "price")).let { (o, c) -> norm(o, c) }
    Scraped(
        title = txt(title), url = url(title.find("a", where = hasHref)), originalPrice = orig, currentPrice = curr,
        stockStatus = stockCls(item.classes(), curr), image = img(item),
    )
}

fun cafe2d6(html: Element): List<Scraped> = cards(html.findAll("li", cls = "item")) { item ->
    val title = item.find("h3") ?: return@cards null
    var orig: String? = null
    var curr: String? = null
    val pc = item.find("p", cls = "price")
    if (pc != null) {
        val span = pc.find("span")
        if (span != null) {
            orig = txt(span)
            span.remove() // what is left of the paragraph is the sale price
            curr = txt(pc)
        } else {
            orig = txt(pc)
        }
    }
    norm(orig, curr).let { orig = it.first; curr = it.second }
    val cls = item.classes()
    val isOos = "sold-out" in cls || item.find(cls = "add-to-agotado") != null
    Scraped(
        title = txt(title), url = url(item.find("a", where = hasHref), "https://www.cafe2d6.cl"),
        originalPrice = orig, currentPrice = curr, stockStatus = saleOr(isOos, curr, "on-sale" in cls),
        image = img(item, "https://www.cafe2d6.cl"),
    )
}

fun buho(html: Element): List<Scraped> = cards(html.findAll("a", cls = "product-card")) { item ->
    val title = item.find("div", cls = "product-card__name") ?: return@cards null
    var orig: String? = null
    var curr: String? = null
    val pc = item.find("div", cls = "product-card__price")
    if (pc != null) {
        pc.findAll("span", cls = "visually-hidden").forEach { it.remove() }
        val was = pc.find("s")
        val pair = if (was != null) {
            val origVal = txt(was)
            was.remove()
            norm(origVal, txt(pc))
        } else {
            norm(txt(pc), null)
        }
        orig = pair.first; curr = pair.second
    }
    val check = (listOf(txt(item.find("span", cls = "product-card__overlay-btn"))) +
        item.findAll("div", cls = "product-tag").map { txt(it) })
        .filter { !it.isNullOrEmpty() }.joinToString(" ")
    Scraped(
        title = txt(title), url = url(item, "https://buhojuegosdemesa.cl"), originalPrice = orig, currentPrice = curr,
        stockStatus = saleOr(oos(check), curr), image = img(item, "https://buhojuegosdemesa.cl"),
    )
}

fun thirdimpact(html: Element): List<Scraped> = cards(html.findAll("section", cls = "grid__item")) { item ->
    val title = item.find("h3", cls = "bs-collection__product-title") ?: return@cards null
    var orig: String? = null
    var curr: String? = null
    val pc = item.find("div", cls = "bs-collection__product-price")
    if (pc != null) {
        val was = pc.find("del", cls = "bs-collection__old-price")
        val fin = pc.find("div", where = clsContains("bs-collection__product-final-price"))
        norm(txt(was).orNullIfEmpty() ?: txt(fin), if (was != null) txt(fin) else null)
            .let { orig = it.first; curr = it.second }
    }
    val button = item.find("button", where = attrIs("data-bs", "cart.add.collection"))
    Scraped(
        title = txt(title), url = url(item.find("a", where = hasHref), "https://www.thirdimpact.cl"),
        originalPrice = orig, currentPrice = curr,
        stockStatus = saleOr(button == null || button.hasAttr("disabled"), curr),
        image = img(item, "https://www.thirdimpact.cl"),
    )
}

fun jugones(html: Element): List<Scraped> = cards(html.findAll("div", cls = "producto")) { item ->
    val title = item.find("a", cls = "modelo") ?: return@cards null
    var origTxt: String? = null
    var currTxt: String? = null
    var isSale = false
    val pc = item.find("a", cls = "precio")
    if (pc != null) {
        isSale = "oferta" in pc.classes()
        val small = pc.find("small")
        if (small != null) {
            if (isSale) origTxt = (txt(small).orNullIfEmpty() ?: "").replace("Antes:", "").pyStrip().orNullIfEmpty()
            small.remove()
        }
        currTxt = txt(pc)
    }
    var (orig, curr) = norm(origTxt, currTxt)
    if (!isSale) { orig = orig.orNullIfEmpty() ?: curr; curr = null }
    Scraped(
        title = txt(title), url = url(title, "https://www.jugones.cl"), originalPrice = orig, currentPrice = curr,
        stockStatus = saleOr(oos(txt(item)), curr), image = img(item, "https://www.jugones.cl"),
    )
}

fun lautarojuegos(html: Element): List<Scraped> = cards(html.findAll("div", cls = "product-block")) { item ->
    val heading = item.find("h4") ?: return@cards null
    val link = heading.find("a") ?: return@cards null
    var origTxt: String? = null
    var currTxt: String? = null
    val pc = item.find("div", cls = "list-price")
    if (pc != null) {
        val o = pc.find("span", cls = "product-block-discount")
        val c = pc.find("span", cls = "product-block-normal")
        if (o != null && c != null) {
            origTxt = txt(o); currTxt = txt(c)
        } else {
            currTxt = txt(pc)
        }
    }
    val (orig, curr) = norm(origTxt, currTxt)
    val button = item.find("button", cls = "adc")
    val check = (listOf(txt(button)) + item.findAll("span", cls = "status-tag").map { txt(it) })
        .filter { !it.isNullOrEmpty() }.joinToString(" ")
    Scraped(
        title = txt(link), url = url(link, "https://www.lautarojuegos.cl"), originalPrice = orig, currentPrice = curr,
        stockStatus = saleOr(oos(check) || button == null, curr), image = img(item, "https://www.lautarojuegos.cl"),
    )
}

/** Odoo website_sale. The <strike> list price is hidden with `d-none` when there is no discount. */
fun geekz(html: Element): List<Scraped> = cards(html.findAll("form", cls = "oe_product_cart")) { item ->
    val title = item.find("h6", where = attrIs("itemprop", "name")) ?: return@cards null
    val pc = item.find("div", cls = "product_price")
    val now = pc?.find("span", cls = "text-delta")
    val was = pc?.find("strike")?.takeUnless { "d-none" in it.classes() }
    val (orig, curr) = norm(txt(was), txt(now))
    Scraped(
        title = txt(title), url = url(item.find("a", where = attrIs("itemprop", "url")), "https://geekz.cl"),
        originalPrice = orig, currentPrice = curr, stockStatus = saleOr(false, curr),
        image = img(item, "https://geekz.cl"),
    )
}

/** `_shown`: the element unless an inline style hides it. */
private fun shown(el: Element?): Element? = el?.takeUnless { "display: none" in it.attr("style") }

/** Three cards per product, two hidden by inline style; only the visible one is read. */
fun ludoteka(html: Element): List<Scraped> = cards(html.findAll("div", cls = "ltk-card-col")) { item ->
    if (shown(item) == null) return@cards null
    val title = item.find("h5", cls = "ltk-card-title") ?: return@cards null
    val old = shown(item.find("span", cls = "ltk-price-old"))
    val now = item.findAll("div", cls = "ltk-price-now").firstOrNull { shown(it) != null }
    val (orig, curr) = norm(txt(old), txt(now))
    Scraped(
        title = txt(title), url = url(item.find("a", cls = "ltk-card-link"), "https://www.ludoteka.cl"),
        originalPrice = orig, currentPrice = curr, stockStatus = saleOr(false, curr),
        image = img(item, "https://www.ludoteka.cl"),
    )
}

/** WooCommerce on a LA-Studio theme; the h2 also wraps price and excerpt, so the name comes from its link. */
fun fillerplace(html: Element): List<Scraped> = cards(html.findAll("li", cls = "product_item")) { item ->
    val link = item.find("h2", cls = "product_item--title")?.find("a", where = hasHref) ?: return@cards null
    val (orig, curr) = wooPrices(item.find("span", cls = "price")).let { (o, c) -> norm(o, c) }
    Scraped(
        title = txt(link), url = url(link),
        originalPrice = orig, currentPrice = curr, stockStatus = stockCls(item.classes(), curr),
        image = img(item),
    )
}

/** Jumpseller, post-prev theme: <del>list</del> <strong>offer</strong>, a lone <strong> is the list price. */
fun ruta12club(html: Element): List<Scraped> = cards(html.findAll("div", cls = "product-block")) { item ->
    val link = item.find("div", cls = "post-prev-title")?.find("a", where = hasHref) ?: return@cards null
    val prices = item.find("div", cls = "post-prev-text")
    val old = prices?.find("del")
    val now = prices?.find("strong")
    val (orig, curr) = if (old != null) norm(txt(old), txt(now)) else txt(now) to null
    Scraped(
        title = txt(link), url = url(link, "https://www.ruta12club.cl"),
        originalPrice = orig, currentPrice = curr,
        stockStatus = saleOr(item.find("span", cls = "unavailable") != null, curr),
        image = img(item, "https://www.ruta12club.cl"),
    )
}

/** Jumpseller product-block without button or label: sold out is product-block__disabled, offer span first. */
fun ludopia(html: Element): List<Scraped> = cards(html.findAll("div", cls = "product-block")) { item ->
    val link = item.find("a", cls = "product-block__name") ?: return@cards null
    val price = item.find("div", cls = "product-block__price")
    val spans = price?.findAll("span").orEmpty()
    val (orig, curr) = if (price != null && "product-block__price--discount" in price.classes() && spans.size >= 2) {
        norm(txt(spans[1]), txt(spans[0]))
    } else {
        txt(price) to null
    }
    Scraped(
        title = txt(link), url = url(link, "https://www.ludopia.cl"),
        originalPrice = orig, currentPrice = curr,
        stockStatus = saleOr(item.find("div", cls = "product-block__disabled") != null, curr),
        image = img(item, "https://www.ludopia.cl"),
    )
}
