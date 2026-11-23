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

/** The published catalogue, already gunzipped. GitHub redirects to its CDN; https to https is followed. */
fun openCatalog(url: String = CATALOG_URL): InputStream {
    val http = URL(url).openConnection() as HttpURLConnection
    http.connectTimeout = TIMEOUT_MS
    http.readTimeout = TIMEOUT_MS
    if (http.responseCode != 200) {
        http.disconnect()
        throw java.io.IOException("Descarga del catálogo: HTTP ${http.responseCode}")
    }
    return GZIPInputStream(http.inputStream)
}

/** Newest scrape in a catalogue: what tells two downloads apart. */
fun catalogStamp(conn: SQLiteConnection): Long? =
    conn.query("SELECT MAX(ts) AS ts FROM scrape_run").first()["ts"] as Long?

/**
 * Copy the user's own tables from the database at `old` into `conn`, a freshly
 * downloaded catalogue that has none. The cron regroups games, so ids do not
 * survive between copies: a game is found again by its norm, else through any
 * of its products (store, url_canon); a picked cart offer by (store, url_canon).
 * Returns how many watchlist/owned/cart entries found no game and were dropped.
 */
fun carryUserState(conn: SQLiteConnection, old: File): Int {
    conn.exec("ATTACH DATABASE ? AS old", old.path)
    try {
        return conn.transaction {
            // The published copy has none; whatever a download brings is not the user's.
            USER_TABLES.forEach { conn.execSQL("DELETE FROM main.$it") }
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
 * Of `names`, the stores whose latest scrape in this database failed or that
 * were never scraped: after a sync, the ones the workflow could not reach,
 * which the phone then tries itself. Once the phone succeeds they drop out
 * until the next catalogue brings the workflow's failure back.
 */
fun unreachableStores(conn: SQLiteConnection, names: List<String>): List<String> {
    val latest = conn.query("""
        SELECT r.store, r.success FROM scrape_run r
         WHERE r.ts = (SELECT MAX(ts) FROM scrape_run WHERE store = r.store)""")
        .associate { it["store"] as String to it["success"] as Long }
    return names.filter { latest[it] != 1L }
}
