package cl.tablero.core.scrape

/*
 * Which parser reads which store.
 *
 * Name, base URL and pagination come from scrape.py through sites.tsv, which
 * `tablero fixtures` regenerates; only the name -> parser binding lives here.
 * A store without a binding is simply not scraped on the phone yet.
 */

data class Site(val name: String, val baseUrl: String, val pagination: String, val parser: Parser)

private fun bsGrid(baseUrl: String): Parser = { parseBs(it, baseUrl = baseUrl) }

private val woo: Parser = { parseWooLi(it, linkCls = "woocommerce-LoopProduct-link") }
private val wooAstra: Parser = { parseWooLi(it, linkCls = "ast-loop-product__link") }
private val presta: Parser = { parsePresta(it) }

/** Mirrors the "Parser bindings" section of scrape.py. */
val PARSERS: Map<String, Parser> = mapOf(
    // WooCommerce
    "cartonazo" to woo,
    "griffingames" to woo,
    "laloseta" to woo,
    "lamadriguera" to woo,
    "tertulia" to woo,
    "mangaigames" to wooAstra,
    "mirzu" to wooAstra,
    "playcenter" to wooAstra,
    "gatoarcano" to { html ->
        parseWooLi(html, linkCls = null, extraOos = { it.find("span", cls = "now_sold") != null })
    },
    "revaruk" to { html ->
        parseWooLi(
            html, linkCls = "ast-loop-product__link",
            extraOos = { item -> item.find("span", cls = "ast-shop-product-out-of-stock")?.let { oos(txt(it)) } ?: false },
            extraSale = { it.find("span", cls = "ast-onsale-card") != null },
        )
    },
    "labovedadelmago" to { html ->
        parseWooLi(html, extraSale = { it.find("span", cls = "onsale") != null })
    },

    // PrestaShop
    "aldeajuegos" to presta,
    "lamesadevaras" to presta,
    "planetaloz" to { parsePresta(it, titleTag = "h1", titleCls = "product-title") },
    "dementegames" to { parsePresta(it, oosFlag = "out-of-stock") },
    "diverti" to { html ->
        parsePresta(html, titleTag = "h3", extraOos = { oos(txt(it.find("div", cls = "ago"))) })
    },

    // bs-collection grids
    "top8" to bsGrid("https://www.top8.cl"),
    "gameofmagictienda" to bsGrid("https://www.gameofmagictienda.cl"),
    "peakgames" to bsGrid("https://www.peakgames.cl"),
    "wargaming" to bsGrid("https://www.wargaming.cl"),
    "laguaridadeldragon" to bsGrid("https://www.laguaridadeldragon.cl"),
    "winterland" to bsGrid("https://www.winterland.cl"),

    // product-block cards
    "vudugaming" to {
        parseProductBlock(it, pricingCls = null, labelCls = null, baseUrl = "https://www.vudugaming.cl")
    },
    "zonaxgamers" to {
        parseProductBlock(
            it, itemTag = "div", itemCls = "product-block__wrapper", pricingCls = null,
            labelCls = null, baseUrl = "https://zonaxgamers.cl",
        )
    },
    "boardkingdom" to { parseProductBlock(it, baseUrl = "https://www.boardkingdom.cl") },
    "playkingdom" to { parseProductBlockSimple(it, "https://playkingdom.cl") },

    // Shopify Debut-family grids
    "flexo" to { parseShopifyCard(it, baseUrl = "https://www.flexogames.cl") },
    "chileboardgames" to { parseShopifyCard(it, baseUrl = "https://chileboardgames.com") },

    // Shopify Dawn grids
    "area52" to { parseDawn(it, baseUrl = "https://area52.cl") },
    "ovniplay" to { parseDawn(it, baseUrl = "https://ovniplay.cl") },
    "juegosgori" to { parseDawn(it, baseUrl = "https://juegosgori.cl") },

    // Store-specific parsers (Bespoke.kt)
    "updown" to ::updown,
    "drjuegos" to ::drjuegos,
    "enroque" to ::enroque,
    "cartonespesados" to ::cartonespesados,
    "lafortalezapuq" to ::lafortalezapuq,
    "shivano" to ::shivano,
    "calabozotienda" to ::calabozotienda,
    "araucania" to ::araucania,

    // The long tail (LongTail.kt)
    "elpatiogeek" to ::elpatiogeek,
    "devir" to ::devir,
    "darkhobbies" to ::darkhobbies,
    "piedrabruja" to ::piedrabruja,
    "lautarojuegos" to ::lautarojuegos,
    "manahouse" to ::manahouse,
    "tentami" to ::tentami,
    "kaiojuegos" to ::kaiojuegos,
    "magicsur" to ::magicsur,
    "guildreams" to ::guildreams,
    "ludipuerto" to ::ludipuerto,
    "jugones" to ::jugones,
    "cafe2d6" to ::cafe2d6,
    "buho" to ::buho,
    "thirdimpact_asmodee" to ::thirdimpact,
    "thirdimpact_devir" to ::thirdimpact,
    "geekz" to ::geekz,
    "ludoteka" to ::ludoteka,
    "fillerplace" to ::fillerplace,
    "spells" to woo,
    "ludipacha" to woo,
    "mundoimaginario" to woo,
    "ruta12club" to ::ruta12club,
    "ludopia" to ::ludopia,
)

/** Every row of sites.tsv, in registry order: (name, base url, pagination). */
fun registry(): List<Triple<String, String, String>> =
    checkNotNull(Site::class.java.getResource("/cl/tablero/core/sites.tsv")) { "sites.tsv missing from resources" }
        .readText()
        .lineSequence()
        .filter { it.isNotBlank() }
        .map { line -> line.split('\t').let { Triple(it[0], it[1], it[2]) } }
        .toList()

/** The stores the phone can scrape: registry rows that have a parser. */
fun sites(): List<Site> = registry().mapNotNull { (name, base, pagination) ->
    PARSERS[name]?.let { Site(name, base, pagination, it) }
}
