package cl.tablero.core.scrape

import cl.tablero.core.data.pyRegex
import cl.tablero.core.data.pyStrip
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor

/*
 * BeautifulSoup's semantics on a Jsoup tree, for the store parsers.
 *
 * The parsers were written against BeautifulSoup and their output is pinned by
 * the page fixtures, so the port keeps BS's rules where Jsoup's differ:
 *
 *  - find/find_all search descendants only. Jsoup's select() can match the
 *    element it is called on.
 *  - class matching is case-sensitive. Jsoup's hasClass() is not.
 *  - get_text(strip=True) strips each text node and joins them with nothing;
 *    Jsoup's text() collapses whitespace and joins with spaces. "$29.990"
 *    split across two spans reads "$29.990" in BS and "$29 .990" in Jsoup.
 *
 * Measured, not assumed: tests/fixtures replays real pages through both.
 */

private val CLASS_TOKEN = pyRegex("""[^\s]+""")

/** The class attribute as BeautifulSoup splits it. */
fun Element.classes(): List<String> = CLASS_TOKEN.findAll(attr("class")).map { it.value }.toList()

/**
 * BeautifulSoup's `class_=name`: one token equal, or all tokens re-joined with
 * single spaces equal -- which is how drjuegos's "price product-price" matches.
 */
fun Element.hasCls(name: String): Boolean = classes().let { name in it || it.joinToString(" ") == name }

private fun Element.matches(tags: Array<out String>, cls: String?, where: (Element) -> Boolean) =
    (tags.isEmpty() || normalName() in tags) && (cls == null || hasCls(cls)) && where(this)

/** `find_all(tags, class_=cls)`: matching descendants in document order, never the element itself. */
fun Element.findAll(vararg tags: String, cls: String? = null, where: (Element) -> Boolean = { true }): List<Element> =
    allElements.drop(1).filter { it.matches(tags, cls, where) }

/** `find(tags, class_=cls)`: the first matching descendant. */
fun Element.find(vararg tags: String, cls: String? = null, where: (Element) -> Boolean = { true }): Element? =
    allElements.asSequence().drop(1).firstOrNull { it.matches(tags, cls, where) }

/** `href=True`. */
val hasHref: (Element) -> Boolean = { it.hasAttr("href") }

// BeautifulSoup files the strings directly inside these under types that
// get_text skips. Jsoup already keeps script and style text out of TextNodes;
// template is the one that needs excluding by hand.
private val SKIPPED_PARENTS = setOf("script", "style", "template")

/** `_txt`: `el.get_text(strip=True)`, or null for no element. */
fun txt(el: Element?): String? {
    if (el == null) return null
    val out = StringBuilder()
    NodeTraversor.traverse({ node, _ ->
        if (node is TextNode && node.parent()?.normalName() !in SKIPPED_PARENTS) {
            out.append(node.wholeText.pyStrip())
        }
    }, el)
    return out.toString()
}

/** `_url`: the href, with `base` prepended when relative. Null when missing or empty. */
fun url(el: Element?, base: String = ""): String? {
    val href = el?.attr("href")?.takeIf { it.isNotEmpty() } ?: return null
    return if (href.startsWith("http")) href else base + href
}

// Lazy-loading themes put the real image in a data-* attribute and a
// placeholder in src, so src is read last. See parsers._img.
private val IMG_ATTRS = listOf("data-src", "data-lazy-src", "data-original", "data-srcset", "srcset", "src")

// Filled into Shopify's "{width}" URL templates: a phone-sized thumbnail.
private const val IMG_WIDTH = "360"

/** `_img`: URL of the first real image inside a card, or null. Only the URL, never the bytes. */
fun img(el: Element?, base: String = ""): String? {
    if (el == null) return null
    for (tag in el.findAll("img", "source")) {
        for (attr in IMG_ATTRS) {
            val raw = tag.attr(attr).takeIf { it.isNotEmpty() } ?: continue
            val url = raw.split(',')[0].pyStrip().split(' ')[0]
            if (url.isEmpty() || url.startsWith("data:")) continue
            val u = url.replace("{width}", IMG_WIDTH)
            return when {
                u.startsWith("//") -> "https:$u"
                u.startsWith("http") -> u
                base.isNotEmpty() -> base.trimEnd('/') + "/" + u.trimStart('/')
                else -> null
            }
        }
    }
    return null
}

/** Python truthiness for an optional string: null and "" are both false. */
fun String?.orNullIfEmpty(): String? = takeUnless { it.isNullOrEmpty() }
