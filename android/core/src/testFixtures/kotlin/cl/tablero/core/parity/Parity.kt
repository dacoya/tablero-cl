package cl.tablero.core.parity

import androidx.sqlite.SQLiteConnection
import cl.tablero.core.data.IngestResult
import cl.tablero.core.data.ProductRow
import cl.tablero.core.data.Filters
import cl.tablero.core.data.STALE_CLAUSE
import cl.tablero.core.data.exec
import cl.tablero.core.data.gamePrices
import cl.tablero.core.data.gameTrends
import cl.tablero.core.data.watchAlerts
import cl.tablero.core.data.watchEntries
import cl.tablero.core.data.products
import cl.tablero.core.data.countProducts
import cl.tablero.core.data.smartProducts
import cl.tablero.core.data.stores
import cl.tablero.core.data.Scraped
import cl.tablero.core.scrape.Parser
import cl.tablero.core.scrape.buildUrl
import cl.tablero.core.data.check
import cl.tablero.core.data.derive
import cl.tablero.core.data.ingestStore
import cl.tablero.core.data.initDb
import cl.tablero.core.data.query
import cl.tablero.core.data.sinceLastReview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jsoup.Jsoup
import java.io.InputStream
import java.util.zip.GZIPInputStream

/*
 * Comparing Kotlin output against what Python wrote, shared by the JVM tests
 * and the on-device instrumented test. Same comparisons, different engines:
 * on the phone the regexes run on ICU and the SQL on the framework SQLite,
 * which is exactly what the JVM run cannot vouch for.
 *
 * Everything is turned into JSON first and compared there, so a mismatch
 * prints as the same shape on both sides.
 */

/** A string field, or null for missing, null or non-string -- Python's `text()` rule. */
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun scraped(json: JsonElement): Scraped = json.jsonObject.let {
    Scraped(
        title = it.str("title"),
        url = it.str("url"),
        originalPrice = it.str("original_price"),
        currentPrice = it.str("current_price"),
        stockStatus = it.str("stock_status"),
        flag = it.str("flag"),
        image = it.str("image"),
    )
}

fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k as String to toJson(v) })
    is List<*> -> JsonArray(value.map(::toJson))
    else -> error("no JSON form for ${value::class}")
}

/** Python's dict keys for a derived row. */
fun ProductRow.toPython(): Map<String, Any?> = mapOf(
    "store" to store, "url_canon" to urlCanon, "url" to url, "title_raw" to titleRaw,
    "title" to title, "norm" to norm, "kind" to kind, "price_original" to priceOriginal,
    "price_current" to priceCurrent, "price_eff" to priceEff, "in_stock" to if (inStock) 1 else 0,
    "flag" to flag, "image_url" to imageUrl,
)

fun IngestResult.toPython(): Map<String, Any?> = mapOf(
    "store" to store, "ingested" to ingested, "new" to new, "restock" to restock,
    "rejected" to rejected, "skipped" to skipped,
)

/** Structural equality where 40000 and 40000.0 are the same number, as they are to Python. */
fun same(a: JsonElement, b: JsonElement): Boolean = when {
    a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { (k, v) -> same(v, b.getValue(k)) }
    a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
    a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString &&
        a.doubleOrNull != null && b.doubleOrNull != null -> a.doubleOrNull == b.doubleOrNull
    else -> a == b
}

// ---------------------------------------------------------------------------
// The three corpora. Each returns human-readable mismatches; empty is a pass.
// ---------------------------------------------------------------------------

/** url_vectors.json through buildUrl. */
fun urlMismatches(vectors: JsonElement): List<String> = vectors.jsonArray.map { it.jsonObject }.mapNotNull { v ->
    val base = v.str("base_url")!!
    val style = v.str("pagination")!!
    val page = v.getValue("page").jsonPrimitive.int
    val got = buildUrl(base, style, page)
    if (got == v.str("url")) null else "$style p$page: $got != ${v.str("url")}"
}

/**
 * derive_vectors.json through derive and check: clean_title, normalize,
 * classify, parse_price, canonical_url, slug_name and validation.
 */
fun deriveMismatches(vectors: JsonElement): List<String> = vectors.jsonArray.map { it.jsonObject }.mapNotNull { v ->
    val store = v.str("store")!!
    val row = derive(store, scraped(v.getValue("record")))
    val got = JsonObject(mapOf("row" to toJson(row?.toPython()), "rejected" to toJson(row?.let(::check))))
    val want = JsonObject(mapOf("row" to v.getValue("row"), "rejected" to v.getValue("rejected")))
    if (same(got, want)) null else "[$store] ${v["record"]}\n  python: $want\n  kotlin: $got"
}

// Same queries as fixtures._snapshot: natural keys, never ids.
private fun snapshot(conn: SQLiteConnection) = toJson(mapOf(
    "products" to conn.query("""
        SELECT p.url_canon, p.url, g.norm AS game_norm, p.title_raw, p.title,
               p.norm, p.kind, p.price_original, p.price_current, p.price_eff,
               p.in_stock, p.flag, p.first_seen, p.last_seen, p.image_url,
               CASE WHEN $STALE_CLAUSE THEN 1 ELSE 0 END AS live
          FROM product p LEFT JOIN game g ON g.id = p.game_id
         ORDER BY p.url_canon"""),
    "games" to conn.query("SELECT norm, title, kind, created_at FROM game ORDER BY norm"),
    "price_obs" to conn.query("""
        SELECT p.url_canon, o.ts, o.price, o.in_stock, o.price_original
          FROM price_obs o JOIN product p ON p.id = o.product_id
         ORDER BY p.url_canon, o.ts"""),
    "scrape_runs" to conn.query("SELECT store, ts, n_products, success FROM scrape_run ORDER BY ts"),
))

/**
 * ingest_scenario.json replayed into `conn`, which must be an empty database.
 * After every step the tables must equal what Python left, and the changes
 * view at the end must report the same rows.
 */
fun ingestMismatches(conn: SQLiteConnection, scenario: JsonElement): List<String> {
    val sc = scenario.jsonObject
    val store = sc.str("store")!!
    val out = mutableListOf<String>()
    fun compare(want: JsonElement, got: JsonElement, what: String) {
        if (!same(want, got)) out += "$what differs\n  python: $want\n  kotlin: $got"
    }

    initDb(conn)
    for (step in sc.getValue("steps").jsonArray.map { it.jsonObject }) {
        val ts = step.getValue("ts").jsonPrimitive.long
        val result = ingestStore(conn, store, step.getValue("records").jsonArray.map(::scraped), ts)
        compare(step.getValue("result"), toJson(result.toPython()), "result at ts=$ts")
        compare(step.getValue("state"), snapshot(conn), "tables at ts=$ts")
    }

    val changes = sc.getValue("changes").jsonObject
    val since = changes.getValue("since").jsonPrimitive.long
    val rows = sinceLastReview(conn, since = since, limit = null).map { it - "product_id" - "game_id" }
    compare(changes.getValue("rows"), toJson(rows), "changes since $since")

    queryMismatches(conn, sc.getValue("queries").jsonObject).forEach { out += it }
    return out
}

private fun List<Map<String, Any?>>.withoutIds() = map { it - "product_id" - "game_id" }

/** The fixtures._QUERY_FILTERS keyword arguments as Kotlin [Filters]. */
private fun filtersOf(json: JsonObject) = Filters(
    stores = json["store"]?.let { if (it is JsonArray) it.map { s -> s.jsonPrimitive.content } else listOf(it.jsonPrimitive.content) },
    inStockOnly = json["in_stock_only"]?.jsonPrimitive?.content == "true",
    minPrice = json["min_price"]?.jsonPrimitive?.content?.toDouble(),
    maxPrice = json["max_price"]?.jsonPrimitive?.content?.toDouble(),
    onSale = json["on_sale"]?.jsonPrimitive?.content == "true",
    flag = json.str("flag"),
    kinds = json["kind"]?.jsonArray?.map { it.jsonPrimitive.content },
    includeStale = json["include_stale"]?.jsonPrimitive?.content == "true",
    minDiscount = json["min_discount"]?.jsonPrimitive?.content?.toDouble(),
)

/** The scenario's "queries" section: browse, paging, smart sorts, prices, stores. */
private fun queryMismatches(conn: SQLiteConnection, q: JsonObject): List<String> {
    val out = mutableListOf<String>()
    fun compare(want: JsonElement, got: Any?, what: String) {
        val g = toJson(got)
        if (!same(want, g)) out += "$what differs\n  python: $want\n  kotlin: $g"
    }
    for ((name, spec) in q.getValue("browse").jsonObject) {
        val filters = filtersOf(spec.jsonObject.getValue("filters").jsonObject)
        compare(spec.jsonObject.getValue("count"), countProducts(conn, filters), "count[$name]")
        for ((sort, rows) in spec.jsonObject.getValue("sorts").jsonObject) {
            compare(rows, products(conn, sort, filters).withoutIds(), "products[$name, $sort]")
        }
    }
    compare(q.getValue("page"), products(conn, "price", limit = 3, offset = 3).withoutIds(), "page")
    for ((by, rows) in q.getValue("smart").jsonObject) {
        compare(rows, smartProducts(conn, by).withoutIds(), "smart[$by]")
    }
    val ids = conn.query("SELECT id, norm FROM game").associate { it["norm"] as String to it["id"] as Long }
    for ((norm, rows) in q.getValue("game_prices").jsonObject) {
        compare(rows, gamePrices(conn, ids.getValue(norm)).withoutIds(), "game_prices[$norm]")
    }
    compare(q.getValue("stores"), stores(conn), "stores")

    // Same rows the Python side inserted, at the same fixed times.
    for (w in q.getValue("watch_setup").jsonArray) {
        val (norm, target, at) = w.jsonArray.map { it.jsonPrimitive.content }
        conn.exec("INSERT INTO watchlist(game_id, target, note, added_at) VALUES (?,?,NULL,?)",
            ids.getValue(norm), target.toDouble(), at.toLong())
    }
    compare(q.getValue("watchlist"), watchEntries(conn).withoutIds(), "watchlist")
    compare(q.getValue("alerts"), watchAlerts(conn).map { it - "game_id" }, "alerts")
    for ((norm, trends) in q.getValue("trends").jsonObject) {
        compare(trends, gameTrends(conn, ids.getValue(norm)), "trends[$norm]")
    }
    return out
}

// ---------------------------------------------------------------------------
// Page fixtures
// ---------------------------------------------------------------------------

/** Python's dict keys for a parsed record. Parsers never set `flag`. */
fun Scraped.toPython(): Map<String, Any?> = mapOf(
    "title" to title, "original_price" to originalPrice, "current_price" to currentPrice,
    "stock_status" to stockStatus, "url" to url, "image" to image,
)

/** A pageN.html.gz fixture, decoded the way fixtures.load reads it. */
fun readGzipText(input: InputStream): String = GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { it.readText() }

/**
 * One saved page through a Kotlin parser against the records Python got out
 * of the same bytes. Parsed with Jsoup's HTML parser, as Http.fetchHtml does.
 */
fun pageMismatches(label: String, parser: Parser, html: String, expectedJson: String): List<String> {
    val want = Json.parseToJsonElement(expectedJson).jsonArray
    val got = parser(Jsoup.parse(html)).map { toJson(it.toPython()) }
    if (got.size != want.size) {
        val missing = want.filter { w -> got.none { same(it, w) } }.take(3)
        val extra = got.filter { g -> want.none { same(it, g) } }.take(3)
        return listOf("$label: ${got.size} records, python ${want.size}\n  missing: $missing\n  extra: $extra")
    }
    return want.indices.filterNot { same(want[it], got[it]) }.take(3)
        .map { "$label #$it\n  python: ${want[it]}\n  kotlin: ${got[it]}" }
}
