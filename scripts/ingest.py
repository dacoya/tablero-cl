"""
Write scraped records into the database.

Replaces merge_to_json's snapshot diffing. Because product identity is stable
here (store + canonical URL), new/restock detection and price history fall out
of comparing against the rows already present -- no previous-snapshot file has
to be kept around and re-read.

Per store, so a partial or failed scrape never touches another store's data.
"""
import time

from . import bgg, db as db_mod, grouping, validation
from .derive import derive


def _existing(conn, store: str) -> dict:
    """{url_canon: row} for a store's current products."""
    sql = """SELECT id, url_canon, in_stock, price_eff, first_seen
               FROM product WHERE store = ?"""
    return {r["url_canon"]: dict(r) for r in conn.execute(sql, (store,))}


def _ensure_games(conn, rows: list, now: int) -> dict:
    """Create game rows for unseen norms; return {norm: game_id}."""
    norms = {r["norm"] for r in rows}
    if not norms:
        return {}

    # Shortest title wins as the cluster's display name, matching the migration.
    best: dict[str, tuple] = {}
    for r in rows:
        cur = best.get(r["norm"])
        if cur is None or len(r["title"]) < len(cur[0]):
            best[r["norm"]] = (r["title"], r["kind"])

    known = {n for (n,) in conn.execute("SELECT norm FROM game")} if best else set()
    conn.executemany(
        "INSERT INTO game(norm, title, kind, created_at, bgg_id) VALUES (?,?,?,?,?)",
        [(norm, title, kind, now, bgg.lookup(conn, norm) if kind in bgg.MATCHED_KINDS else None)
         for norm, (title, kind) in best.items() if norm not in known],
    )

    # Joined through a temp table rather than IN (?,?,...): a large store has
    # thousands of norms, and SQLite builds before 3.32 cap a statement at 999
    # variables. executemany is not subject to that cap.
    conn.execute("CREATE TEMP TABLE IF NOT EXISTS _scan_norms(norm TEXT PRIMARY KEY)")
    conn.execute("DELETE FROM _scan_norms")
    conn.executemany(
        "INSERT OR IGNORE INTO _scan_norms(norm) VALUES (?)",
        [(n,) for n in norms],
    )
    return {
        r["norm"]: r["id"]
        for r in conn.execute(
            "SELECT g.id, g.norm FROM game g JOIN _scan_norms n ON n.norm = g.norm"
        )
    }


def _flag_for(prev, row) -> str | None:
    """
    'new' for an unseen URL, 'restock' when a known one came back into stock.

    Anything else is unflagged: a flag marks a change worth surfacing, and a
    product merely still being available is not one.
    """
    if prev is None:
        return "new"
    if not prev["in_stock"] and row["in_stock"]:
        return "restock"
    return None


def _split_writes(rows: list, existing: dict, games: dict, store: str, ts: int):
    """Partition rows into (inserts, updates) with their bound values."""
    inserts, updates = [], []
    for r in rows:
        prev = existing.get(r["url_canon"])
        payload = (
            games.get(r["norm"]), r["title_raw"], r["title"], r["norm"], r["kind"],
            r["price_original"], r["price_current"], r["price_eff"],
            r["in_stock"], _flag_for(prev, r), ts, r["image_url"],
        )
        if prev is None:
            inserts.append((store, r["url_canon"], r["url"], *payload, ts))
        else:
            updates.append((*payload, prev["id"]))
    return inserts, updates


_INSERT_PRODUCT = """
    INSERT INTO product
      (store, url_canon, url, game_id, title_raw, title, norm, kind,
       price_original, price_current, price_eff, in_stock, flag,
       last_seen, image_url, first_seen)
    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
"""

_UPDATE_PRODUCT = """
    UPDATE product SET
      game_id=?, title_raw=?, title=?, norm=?, kind=?,
      price_original=?, price_current=?, price_eff=?,
      in_stock=?, flag=?, last_seen=?,
      -- A scrape that found no photo keeps the last one known:
      -- lazy-loading markup changes more often than products do.
      image_url=COALESCE(?, image_url)
    WHERE id=?
"""


def _clean_rows(store: str, records: list) -> tuple[list, dict]:
    """
    Derived rows worth writing, plus the rejection counts.

    Impossible prices are rejected before they reach the database: a bad row
    would otherwise skew the cross-store median every ranking depends on. The
    last row wins if a store lists the same URL twice in one scrape.
    """
    rows = [d for rec in records if (d := derive(store, rec)) is not None]
    rows, rejected = validation.partition(rows)
    return list({r["url_canon"]: r for r in rows}.values()), rejected


def ingest_store(conn, store: str, records: list, ts: int | None = None) -> dict:
    """
    Upsert one store's scraped records.

    An empty `records` -- or one where nothing survives derive and validation
    -- is treated as a failed scrape and leaves existing data alone: a network
    failure should never look like a store dropping its entire catalog.
    """
    ts = int(ts if ts is not None else time.time())
    rows, rejected = _clean_rows(store, records)
    if not rows:
        return {"store": store, "ingested": 0, "new": 0, "restock": 0,
                "rejected": rejected, "skipped": True}

    conn.execute("INSERT OR IGNORE INTO store(name, active) VALUES (?, 1)", (store,))
    rows = grouping.merge_similar_norms(conn, rows)
    games = _ensure_games(conn, rows, ts)
    existing = _existing(conn, store)

    # Clear the store's flags up front. Every row this scrape touches sets its
    # own flag below, so whatever stays NULL is exactly what was not seen. The
    # previous "NOT IN (every seen url)" needed one variable per product, which
    # exceeds the 999-variable cap on SQLite builds before 3.32.
    conn.execute("UPDATE product SET flag = NULL WHERE store = ?", (store,))

    inserts, updates = _split_writes(rows, existing, games, store, ts)
    conn.executemany(_INSERT_PRODUCT, inserts)
    conn.executemany(_UPDATE_PRODUCT, updates)

    _record_prices(conn, store, ts)
    conn.execute(
        "INSERT OR REPLACE INTO scrape_run(store, ts, n_products, success) VALUES (?,?,?,1)",
        (store, ts, len(rows)),
    )
    conn.commit()

    return {
        "store": store,
        "ingested": len(rows),
        "new": len(inserts),
        "restock": sum(1 for u in updates if u[9] == "restock"),
        "rejected": rejected,
        "skipped": False,
    }


def _record_prices(conn, store: str, ts: int) -> None:
    """
    Append an observation only where something actually moved.

    Collapsing repeats keeps the series meaningful and the table small: without
    it every scrape would add ~29k identical rows.

    "Something" is the price, the stock state, or the list price behind an
    offer. Keying collapse on the effective price alone meant a product going
    out of stock, or a sale starting at a price that happened to match, left
    no trace -- and `product.flag` only survives one scrape, so the change was
    then unrecoverable. Anything missed while away was gone for good.

    Done as one INSERT...SELECT taking three bound values, rather than reading
    every last row into Python and writing back one by one. That also keeps
    the statement clear of the 999-variable cap on older SQLite builds.
    """
    conn.execute(
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
                -- IS NOT, not !=, so a NULL on either side counts as a change
                -- rather than swallowing one. Rows written before v4 carry
                -- NULL in both new columns.
                OR last.in_stock IS NOT p.in_stock
                OR last.price_original IS NOT p.price_original)
        """,
        (ts, store, store),
    )


def record_failure(conn, store: str, ts: int | None = None) -> None:
    """Log a failed scrape without touching the store's products."""
    ts = int(ts if ts is not None else time.time())
    conn.execute(
        "INSERT OR REPLACE INTO scrape_run(store, ts, n_products, success) VALUES (?,?,NULL,0)",
        (store, ts),
    )
    conn.commit()


def refresh_indexes(conn) -> dict:
    """
    Regroup the catalogue with the current matching rules, match games to
    BoardGameGeek (importing a newer CSV first), then resync FTS.
    Runs once after a batch of stores has been ingested.
    """
    report = grouping.regroup(conn, int(time.time()))
    report["bgg"] = bgg.refresh(conn)
    db_mod.rebuild_fts(conn)
    return report
