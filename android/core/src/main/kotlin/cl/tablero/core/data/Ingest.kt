package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection

/*
 * Port of scripts/ingest.py: write one store's scrape into the database.
 * tests/fixtures/ingest_scenario.json pins the resulting tables.
 *
 * Per store, so a partial or failed scrape never touches another store's data.
 */

data class IngestResult(
    val store: String,
    val ingested: Int,
    val new: Int,
    val restock: Int,
    val rejected: Map<String, Int>,
    val skipped: Boolean,
)

private class Existing(val id: Long, val inStock: Long?)

private fun now(): Long = System.currentTimeMillis() / 1000

/** {url_canon: row} for a store's current products. */
private fun existing(conn: SQLiteConnection, store: String): Map<String, Existing> =
    conn.query("SELECT id, url_canon, in_stock FROM product WHERE store = ?", store)
        .associate { it["url_canon"] as String to Existing(it["id"] as Long, it["in_stock"] as Long?) }

/**
 * Create game rows for unseen norms; return {norm: game_id}.
 *
 * Shortest title wins as the cluster's display name, first one on a tie,
 * matching ingest._ensure_games. Looked up one norm at a time rather than
 * through the temp table Python uses: a prepared single-variable statement
 * has no 999-variable cap to work around.
 */
private fun ensureGames(conn: SQLiteConnection, rows: List<ProductRow>, ts: Long): Map<String, Long> {
    val best = linkedMapOf<String, ProductRow>()
    for (r in rows) {
        val cur = best[r.norm]
        if (cur == null || r.title.pyLen < cur.title.pyLen) best[r.norm] = r
    }
    conn.execMany(
        "INSERT OR IGNORE INTO game(norm, title, kind, created_at) VALUES (?,?,?,?)",
        best.map { (norm, r) -> listOf(norm, r.title, r.kind, ts) },
    )
    return conn.prepare("SELECT id FROM game WHERE norm = ?").use { st ->
        best.keys.associateWith { norm ->
            st.bindText(1, norm)
            st.step()
            st.getLong(0).also { st.reset() }
        }
    }
}

/** 'new' for an unseen URL, 'restock' when a known one came back into stock. */
private fun flagFor(prev: Existing?, row: ProductRow): String? = when {
    prev == null -> "new"
    (prev.inStock == null || prev.inStock == 0L) && row.inStock -> "restock"
    else -> null
}

/**
 * Upsert one store's scraped records.
 *
 * An empty `records` -- or one where nothing survives derive and validation --
 * is a failed scrape and leaves existing data alone.
 */
fun ingestStore(conn: SQLiteConnection, store: String, records: List<Scraped>, ts: Long = now()): IngestResult {
    val (clean, rejected) = partition(records.mapNotNull { derive(store, it) })
    if (clean.isEmpty()) return IngestResult(store, 0, 0, 0, rejected, skipped = true)

    // Last row wins if a store lists the same URL twice in one scrape.
    val rows = clean.associateBy { it.urlCanon }.values.toList()

    return conn.transaction {
        conn.exec("INSERT OR IGNORE INTO store(name, active) VALUES (?, 1)", store)
        val games = ensureGames(conn, rows, ts)
        val known = existing(conn, store)

        // Every row this scrape touches sets its own flag below, so whatever
        // stays NULL is exactly what was not seen.
        conn.exec("UPDATE product SET flag = NULL WHERE store = ?", store)

        val inserts = mutableListOf<List<Any?>>()
        val updates = mutableListOf<List<Any?>>()
        var restock = 0
        for (r in rows) {
            val prev = known[r.urlCanon]
            val flag = flagFor(prev, r)
            val payload = listOf(
                games[r.norm], r.titleRaw, r.title, r.norm, r.kind,
                r.priceOriginal, r.priceCurrent, r.priceEff, r.inStock, flag, ts,
            )
            if (prev == null) {
                inserts += listOf(store, r.urlCanon, r.url) + payload + ts
            } else {
                updates += payload + prev.id
                if (flag == "restock") restock++
            }
        }

        conn.execMany(
            """INSERT INTO product
               (store, url_canon, url, game_id, title_raw, title, norm, kind,
                price_original, price_current, price_eff, in_stock, flag,
                last_seen, first_seen)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            inserts,
        )
        conn.execMany(
            """UPDATE product SET
                 game_id=?, title_raw=?, title=?, norm=?, kind=?,
                 price_original=?, price_current=?, price_eff=?,
                 in_stock=?, flag=?, last_seen=?
               WHERE id=?""",
            updates,
        )

        recordPrices(conn, store, ts)
        conn.exec(
            "INSERT OR REPLACE INTO scrape_run(store, ts, n_products, success) VALUES (?,?,?,1)",
            store, ts, rows.size,
        )
        IngestResult(store, rows.size, inserts.size, restock, rejected, skipped = false)
    }
}

/**
 * Append an observation only where the price, the stock state or the list
 * price behind an offer moved. Verbatim from ingest._record_prices, whose
 * docstring explains why all three.
 */
private fun recordPrices(conn: SQLiteConnection, store: String, ts: Long) {
    conn.exec(
        """
        INSERT OR IGNORE INTO price_obs(product_id, ts, price, in_stock, price_original)
        SELECT p.id, ?, p.price_eff, p.in_stock, p.price_original
          FROM product p
          LEFT JOIN (
                SELECT o.product_id, o.price, o.in_stock, o.price_original,
                       ROW_NUMBER() OVER (PARTITION BY o.product_id
                                          ORDER BY o.ts DESC) AS rn
                  FROM price_obs o
                  JOIN product pp ON pp.id = o.product_id
                 WHERE pp.store = ?
          ) last ON last.product_id = p.id AND last.rn = 1
         WHERE p.store = ?
           AND p.price_eff IS NOT NULL
           AND (last.price IS NULL
                OR last.price != p.price_eff
                OR last.in_stock IS NOT p.in_stock
                OR last.price_original IS NOT p.price_original)
        """,
        ts, store, store,
    )
}

/** Log a failed scrape without touching the store's products. */
fun recordFailure(conn: SQLiteConnection, store: String, ts: Long = now()) {
    conn.exec(
        "INSERT OR REPLACE INTO scrape_run(store, ts, n_products, success) VALUES (?,?,NULL,0)",
        store, ts,
    )
}
