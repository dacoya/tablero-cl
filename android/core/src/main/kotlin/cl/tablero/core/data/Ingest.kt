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
        "INSERT OR IGNORE INTO game(norm, title, kind, created_at, bgg_id) VALUES (?,?,?,?,?)",
        best.map { (norm, r) -> listOf(norm, r.title, r.kind, ts, if (r.kind in BGG_KINDS) bggLookup(conn, norm) else null) },
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

private const val INSERT_PRODUCT = """
    INSERT INTO product
      (store, url_canon, url, game_id, title_raw, title, norm, kind,
       price_original, price_current, price_eff, in_stock, flag,
       last_seen, image_url, first_seen)
    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
"""

// A scrape that found no photo keeps the last one known: lazy-loading
// markup changes more often than products do.
private const val UPDATE_PRODUCT = """
    UPDATE product SET
      game_id=?, title_raw=?, title=?, norm=?, kind=?,
      price_original=?, price_current=?, price_eff=?,
      in_stock=?, flag=?, last_seen=?,
      image_url=COALESCE(?, image_url)
    WHERE id=?
"""

// Position of `flag` in an update row, as `u[9]` in ingest.ingest_store.
private const val FLAG_COLUMN = 9

/** ingest._clean_rows: rows worth writing (last per URL wins) and the rejection counts. */
private fun cleanRows(store: String, records: List<Scraped>): Pair<List<ProductRow>, Map<String, Int>> {
    val (clean, rejected) = partition(records.mapNotNull { derive(store, it) })
    return clean.associateBy { it.urlCanon }.values.toList() to rejected
}

/** ingest._split_writes: (inserts, updates) with their bound values. */
private fun splitWrites(
    rows: List<ProductRow>, known: Map<String, Existing>, games: Map<String, Long>, store: String, ts: Long,
): Pair<List<List<Any?>>, List<List<Any?>>> {
    val inserts = mutableListOf<List<Any?>>()
    val updates = mutableListOf<List<Any?>>()
    for (r in rows) {
        val prev = known[r.urlCanon]
        val payload = listOf(
            games[r.norm], r.titleRaw, r.title, r.norm, r.kind,
            r.priceOriginal, r.priceCurrent, r.priceEff, r.inStock, flagFor(prev, r), ts, r.imageUrl,
        )
        if (prev == null) inserts += listOf(store, r.urlCanon, r.url) + payload + ts else updates += payload + prev.id
    }
    return inserts to updates
}

/**
 * Upsert one store's scraped records.
 *
 * An empty `records` -- or one where nothing survives derive and validation --
 * is a failed scrape and leaves existing data alone.
 */
fun ingestStore(conn: SQLiteConnection, store: String, records: List<Scraped>, ts: Long = now()): IngestResult {
    val (cleaned, rejected) = cleanRows(store, records)
    if (cleaned.isEmpty()) return IngestResult(store, 0, 0, 0, rejected, skipped = true)

    return conn.transaction {
        conn.exec("INSERT OR IGNORE INTO store(name, active) VALUES (?, 1)", store)
        val rows = mergeSimilarNorms(conn, cleaned)
        val games = ensureGames(conn, rows, ts)
        val known = existing(conn, store)

        // Every row this scrape touches sets its own flag below, so whatever
        // stays NULL is exactly what was not seen.
        conn.exec("UPDATE product SET flag = NULL WHERE store = ?", store)

        val (inserts, updates) = splitWrites(rows, known, games, store, ts)
        conn.execMany(INSERT_PRODUCT, inserts)
        conn.execMany(UPDATE_PRODUCT, updates)

        recordPrices(conn, store, ts)
        conn.exec(
            "INSERT OR REPLACE INTO scrape_run(store, ts, n_products, success) VALUES (?,?,?,1)",
            store, ts, rows.size,
        )
        val restock = updates.count { it[FLAG_COLUMN] == "restock" }
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
