package cl.tablero.app.ui

import android.content.Context
import android.util.Log
import androidx.sqlite.SQLiteConnection
import cl.tablero.core.data.SqlRow
import cl.tablero.core.data.browseRows
import cl.tablero.core.data.countProductsEach
import cl.tablero.core.data.productRev
import cl.tablero.core.data.query
import cl.tablero.core.data.withCheapest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/*
 * The Home feed is computed once per update and kept on disk, so opening the
 * app or coming back to Inicio runs no shelf queries. The update worker
 * builds it right after scraping; Home only reads it, and builds it itself
 * only when the file is missing or answers an older database.
 */

private const val SHELF_SIZE = 12
private const val TAG = "HomeFeed"

/** What the feed shows: tile counts and each shelf's rows (null while loading). */
internal data class FeedData(
    val counts: List<Long> = emptyList(),
    val shelves: List<List<SqlRow>?> = List(SHELVES.size) { null },
)

/**
 * What the feed depends on: the products, and what the user owns (one shelf
 * is the expansions of owned games, the others hide owned ones). Cheap.
 */
internal fun feedKey(conn: SQLiteConnection): String {
    val owned = conn.query("SELECT COUNT(*) AS n, COALESCE(MAX(added_at), 0) AS t FROM owned").single()
    return "${productRev(conn)}:${owned["n"]}:${owned["t"]}"
}

internal fun buildFeed(conn: SQLiteConnection): FeedData = FeedData(
    counts = countProductsEach(conn, HOME_TILES.map { it.browse.filters }),
    shelves = SHELVES.map { withCheapest(conn, browseRows(conn, it.browse.sort, it.browse.filters, limit = SHELF_SIZE)) },
)

internal fun feedFile(context: Context) = File(context.cacheDir, "home_feed.json")

/** Build the feed and keep it; the update worker's last step. */
internal fun saveFeed(file: File, conn: SQLiteConnection): Pair<String, FeedData> {
    val saved = feedKey(conn) to buildFeed(conn)
    try {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(encode(saved.first, saved.second))
        if (!tmp.renameTo(file)) Log.w(TAG, "could not replace $file")
    } catch (e: java.io.IOException) {
        // Only a cache: Home builds the feed itself next time.
        Log.w(TAG, "could not save the feed", e)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "could not encode the feed", e)
    }
    return saved
}

/** The kept feed and the key it answers, or null when there is none or it does not parse. */
internal fun loadFeed(file: File): Pair<String, FeedData>? = try {
    if (file.exists()) decode(file.readText()) else null
} catch (e: Exception) {
    // A cache from another version or a cut-off write: rebuild it, never crash Home.
    if (e !is JSONException && e !is java.io.IOException && e !is ClassCastException && e !is IllegalArgumentException) throw e
    Log.w(TAG, "discarding the kept feed", e)
    null
}

// org.json reads every number back as Int, Long or Double by its digits, and
// the screens cast product_id and prices exactly. So each value keeps its type.
private fun encodeValue(v: Any?): Any = when (v) {
    null -> JSONObject.NULL
    is Long -> "l:$v"
    is Double -> "d:$v"
    is String -> "s:$v"
    else -> throw IllegalArgumentException("unexpected ${v::class.simpleName} in a feed row")
}

private fun decodeValue(v: Any?): Any? = when {
    v == null || v == JSONObject.NULL -> null
    v !is String || v.length < 2 || v[1] != ':' -> throw IllegalArgumentException("bad feed value")
    v[0] == 'l' -> v.substring(2).toLong()
    v[0] == 'd' -> v.substring(2).toDouble()
    v[0] == 's' -> v.substring(2)
    else -> throw IllegalArgumentException("bad feed value")
}

internal fun encode(key: String, feed: FeedData): String = JSONObject()
    .put("key", key)
    .put("counts", JSONArray(feed.counts))
    .put("shelves", JSONArray(feed.shelves.map { rows ->
        JSONArray(rows.orEmpty().map { row -> JSONObject().apply { row.forEach { (k, v) -> put(k, encodeValue(v)) } } })
    }))
    .toString()

internal fun decode(text: String): Pair<String, FeedData> {
    val json = JSONObject(text)
    val counts = json.getJSONArray("counts").let { a -> List(a.length()) { a.getLong(it) } }
    val shelves = json.getJSONArray("shelves").let { a ->
        List(a.length()) { i ->
            val rows = a.getJSONArray(i)
            List(rows.length()) { r ->
                val o = rows.getJSONObject(r)
                o.keys().asSequence().associateWith { decodeValue(o.get(it)) }
            }
        }
    }
    // A feed kept by a version with other shelves is not this one's.
    require(counts.size == HOME_TILES.size && shelves.size == SHELVES.size) { "feed shape changed" }
    return json.getString("key") to FeedData(counts, shelves)
}
