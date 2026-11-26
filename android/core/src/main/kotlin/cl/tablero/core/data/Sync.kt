package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** The catalogue the scrape workflow publishes daily (.github/workflows/scrape.yml). */
const val CATALOG_URL = "https://github.com/dacoya/tablero-cl/releases/download/data/tablero.db.gz"

/** Cursor holding the newest scrape of the catalogue last applied, so an unchanged one is skipped. */
const val CATALOG_CURSOR = "catalog"

/** db.USER_TABLES: personal state the catalogue never carries. */
val USER_TABLES = listOf("watchlist", "owned", "store_shipping", "store_fav", "cart", "cursor")

private const val TIMEOUT_MS = 30_000

/** Cursor holding the Last-Modified (unix seconds) of the copy last fetched, so an unchanged one is not downloaded again. */
const val CATALOG_HTTP_CURSOR = "catalog_http"

/** A fetched catalogue, already gunzipped, and when GitHub last replaced it (unix seconds). */
class Download(val stream: InputStream, val modified: Long)

/**
 * The published catalogue, or null when it has not changed since `since`
 * (unix seconds): the If-Modified-Since check costs one request, not the
 * download. GitHub redirects to its CDN; https to https is followed, header kept.
 */
fun openCatalog(since: Long? = null, url: String = CATALOG_URL): Download? {
    val http = URL(url).openConnection() as HttpURLConnection
    http.connectTimeout = TIMEOUT_MS
    http.readTimeout = TIMEOUT_MS
    if (since != null) http.ifModifiedSince = since * 1000
    when (http.responseCode) {
        200 -> return Download(GZIPInputStream(http.inputStream), http.lastModified / 1000)
        304 -> { http.disconnect(); return null }
        else -> {
            http.disconnect()
            throw java.io.IOException("Descarga del catálogo: HTTP ${http.responseCode}")
        }
    }
}

/** Newest scrape in a catalogue: what tells two downloads apart. */
fun catalogStamp(conn: SQLiteConnection): Long? =
    conn.query("SELECT MAX(ts) AS ts FROM scrape_run").first()["ts"] as Long?

/**
 * Copy the user's own tables from the database at `old` into `conn`, a freshly
 * downloaded catalogue that has none. The cron regroups games, so ids do not
 * survive between copies: a game is found again by its norm, else through any
 * of its products (store, url_canon); a picked cart offer by (store, url_canon).
 * Stores the old copy scraped more recently than the download (phone
 * scrapes of stores the workflow cannot reach) keep the old copy's rows.
 * Returns how many watchlist/owned/cart entries found no game and were dropped.
 */
fun carryUserState(conn: SQLiteConnection, old: File): Int {
    conn.exec("ATTACH DATABASE ? AS old", old.path)
    try {
        return conn.transaction {
            // The published copy has none; whatever a download brings is not the user's.
            USER_TABLES.forEach { conn.execSQL("DELETE FROM main.$it") }
            keepNewerStores(conn)
            conn.execSQL("DROP TABLE IF EXISTS temp.game_map")
            conn.execSQL("""
                CREATE TEMP TABLE game_map AS
                SELECT og.id AS old_id, COALESCE(
                    (SELECT g.id FROM main.game g WHERE g.norm = og.norm),
                    (SELECT np.game_id FROM old.product op
                       JOIN main.product np ON np.store = op.store AND np.url_canon = op.url_canon
                      WHERE op.game_id = og.id AND np.game_id IS NOT NULL LIMIT 1)
                ) AS new_id
                FROM old.game og
                WHERE og.id IN (SELECT game_id FROM old.watchlist UNION SELECT game_id FROM old.owned
                                UNION SELECT game_id FROM old.cart)""")
            conn.execSQL("""
                INSERT OR IGNORE INTO main.watchlist(game_id, target, note, added_at)
                SELECT m.new_id, w.target, w.note, w.added_at FROM old.watchlist w
                  JOIN temp.game_map m ON m.old_id = w.game_id WHERE m.new_id IS NOT NULL""")
            conn.execSQL("""
                INSERT OR IGNORE INTO main.owned(game_id, added_at)
                SELECT m.new_id, o.added_at FROM old.owned o
                  JOIN temp.game_map m ON m.old_id = o.game_id WHERE m.new_id IS NOT NULL""")
            conn.execSQL("""
                INSERT OR IGNORE INTO main.cart(game_id, qty, added_at, product_id)
                SELECT m.new_id, c.qty, c.added_at, np.id FROM old.cart c
                  JOIN temp.game_map m ON m.old_id = c.game_id
                  LEFT JOIN old.product op ON op.id = c.product_id
                  LEFT JOIN main.product np ON np.store = op.store AND np.url_canon = op.url_canon
                 WHERE m.new_id IS NOT NULL""")
            for (table in listOf("store_shipping", "store_fav", "cursor")) {
                conn.execSQL("INSERT OR REPLACE INTO main.$table SELECT * FROM old.$table")
            }
            val lost = conn.query("""
                SELECT (SELECT COUNT(*) FROM old.watchlist) + (SELECT COUNT(*) FROM old.owned)
                     + (SELECT COUNT(*) FROM old.cart) - (SELECT COUNT(*) FROM main.watchlist)
                     - (SELECT COUNT(*) FROM main.owned) - (SELECT COUNT(*) FROM main.cart) AS n""")
                .first()["n"] as Long
            conn.execSQL("DROP TABLE temp.game_map")
            lost.toInt()
        }
    } finally {
        conn.execSQL("DETACH DATABASE old")
    }
}

/**
 * For every store whose latest successful scrape in `old` is newer than in
 * the download, swap the download's products, prices and runs for the old
 * copy's. Without this a sync puts back the workflow's stale rows for a store
 * the phone just scraped, and the next phone scrape reports all of it as
 * changed again. Ids are new on this side: games are found by norm (and
 * created when missing), products by (store, url_canon).
 */
private fun keepNewerStores(conn: SQLiteConnection) {
    conn.execSQL("DROP TABLE IF EXISTS temp.kept")
    conn.execSQL("""
        CREATE TEMP TABLE kept AS
        SELECT o.store FROM (SELECT store, MAX(ts) AS ts FROM old.scrape_run WHERE success = 1 GROUP BY store) o
          LEFT JOIN (SELECT store, MAX(ts) AS ts FROM main.scrape_run WHERE success = 1 GROUP BY store) n
            ON n.store = o.store
         WHERE n.ts IS NULL OR o.ts > n.ts""")
    val inKept = "IN (SELECT store FROM temp.kept)"
    conn.execSQL("INSERT OR IGNORE INTO main.store SELECT * FROM old.store WHERE name $inKept")
    conn.execSQL("DELETE FROM main.price_obs WHERE product_id IN (SELECT id FROM main.product WHERE store $inKept)")
    conn.execSQL("DELETE FROM main.product WHERE store $inKept")
    conn.execSQL("DELETE FROM main.scrape_run WHERE store $inKept")
    // A game only the phone has seen. Its BGG match only if the download still carries that row.
    conn.execSQL("""
        INSERT OR IGNORE INTO main.game(norm, title, kind, created_at, bgg_id)
        SELECT g.norm, g.title, g.kind, g.created_at, (SELECT b.id FROM main.bgg b WHERE b.id = g.bgg_id)
          FROM old.game g WHERE g.id IN (SELECT game_id FROM old.product WHERE store $inKept)""")
    conn.execSQL("""
        INSERT INTO main.product(store, url_canon, url, game_id, title_raw, title, norm, kind, price_original,
                                 price_current, price_eff, in_stock, flag, first_seen, last_seen, image_url)
        SELECT p.store, p.url_canon, p.url,
               (SELECT g.id FROM main.game g JOIN old.game og ON og.norm = g.norm WHERE og.id = p.game_id),
               p.title_raw, p.title, p.norm, p.kind, p.price_original,
               p.price_current, p.price_eff, p.in_stock, p.flag, p.first_seen, p.last_seen, p.image_url
          FROM old.product p WHERE p.store $inKept""")
    conn.execSQL("""
        INSERT INTO main.price_obs(product_id, ts, price, in_stock, price_original)
        SELECT np.id, o.ts, o.price, o.in_stock, o.price_original FROM old.price_obs o
          JOIN old.product op ON op.id = o.product_id
          JOIN main.product np ON np.store = op.store AND np.url_canon = op.url_canon
         WHERE op.store $inKept""")
    conn.execSQL("INSERT INTO main.scrape_run SELECT * FROM old.scrape_run WHERE store $inKept")
    conn.execSQL("DROP TABLE temp.kept")
}
