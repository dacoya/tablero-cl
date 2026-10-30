"""
Query layer. All SQL lives here.

Every function takes a connection and returns plain JSON-serializable data
(``list[dict]`` / ``dict``) -- never a DataFrame, never printed output. That is
what makes the same queries reusable by the terminal today and by an HTTP or
export layer later without touching this module.
"""

# A product is stale when its store was scraped successfully more recently than
# the product was last seen -- it was delisted, or its URL changed. Such rows
# keep their last known price and would otherwise still be offered as live
# deals; 152 games had their advertised "desde" price set by one.
#
# They are excluded from queries rather than deleted, so their price history
# survives and a store that merely served a bad page recovers on the next run.
STALE_CLAUSE = """
    p.last_seen >= COALESCE(
        (SELECT MAX(r.ts) FROM scrape_run r WHERE r.store = p.store AND r.success = 1),
        p.last_seen
    )
"""


# FTS5 treats a lot of punctuation as syntax. User input is reduced to bare
# alphanumeric tokens before it ever reaches MATCH, so a stray quote or NEAR
# cannot alter the query's meaning.
def game_prices(conn, game_id: int, include_stale: bool = False) -> list[dict]:
    """Per-store offers for one game, cheapest first."""
    stale = "" if include_stale else f" AND {STALE_CLAUSE}"
    sql = f"""
        SELECT {_PRODUCT_COLS}
          FROM product p
         WHERE p.game_id = ?{stale}
         ORDER BY (p.price_eff IS NULL), p.price_eff, p.id
    """
    return [dict(r) for r in conn.execute(sql, (game_id,))]


# ---------------------------------------------------------------------------
# Browsing: deals and catalog
# ---------------------------------------------------------------------------


def filter_clause(store=None, in_stock_only=False, min_price=None, max_price=None,
                  on_sale=False, flag=None, kind=None, include_stale=False,
                  min_discount=None):
    """
    Compose an SQL WHERE fragment plus bound parameters.

    Shared with analytics.smart_products, which used to keep its own copy --
    the copy is where `--max-price` once went missing for the smart sorts.

    `store` and `kind` take one value or several. `min_discount` is a
    percentage (25 = at least 25% off) and implies an offer is running.
    """
    clauses, params = ["1=1"], []
    if not include_stale:
        clauses.append(STALE_CLAUSE)
    if store:
        stores = [store] if isinstance(store, str) else list(store)
        clauses.append(f"p.store IN ({','.join('?' * len(stores))})")
        params.extend(stores)
    if kind:
        kinds = [kind] if isinstance(kind, str) else list(kind)
        clauses.append(f"p.kind IN ({','.join('?' * len(kinds))})")
        params.extend(kinds)
    if in_stock_only:
        clauses.append("p.in_stock = 1")
    if min_price is not None:
        clauses.append("p.price_eff >= ?")
        params.append(float(min_price))
    if max_price is not None:
        clauses.append("p.price_eff <= ?")
        params.append(float(max_price))
    if on_sale:
        clauses.append("p.price_current IS NOT NULL AND p.price_original > p.price_current")
    if flag:
        clauses.append("p.flag = ?")
        params.append(flag)
    if min_discount is not None:
        clauses.append(f"({DISCOUNT_PCT_SQL}) >= ?")
        params.append(float(min_discount))
    return " AND ".join(clauses), params


# Single definition of the discount expression, shared with analytics.py so the
# two cannot drift apart.
DISCOUNT_PCT_SQL = """
    CASE WHEN p.price_current IS NOT NULL AND p.price_original > 0
              AND p.price_original > p.price_current
         THEN ROUND((p.price_original - p.price_current) * 100.0 / p.price_original, 1)
    END
"""

_PRODUCT_COLS = f"""
    p.id AS product_id, p.store, p.title, p.url, p.game_id, p.kind,
    p.price_original, p.price_current, p.price_eff, p.in_stock, p.flag,
    p.image_url,
    {DISCOUNT_PCT_SQL} AS discount_pct
"""

# Every order ends on p.id. SQLite promises nothing about the order of equal
# keys, and browsing pages with LIMIT/OFFSET: two products at the same price
# could swap between one page query and the next, so one showed twice and the
# other never. Today's build happens to keep rowid order; the phone's SQLite
# need not, and the Kotlin port is checked row for row against this one.
_ORDER_BY = {
    "discount": "discount_pct DESC NULLS LAST, p.price_eff, p.id",
    "price":    "(p.price_eff IS NULL), p.price_eff, p.id",
    "price_desc": "p.price_eff DESC NULLS LAST, p.id",
    # On-sale products first, dearest sale price first.
    "sale_desc": "(discount_pct IS NULL), p.price_eff DESC, p.id",
    # Pesos off the list price, biggest first.
    "savings":  "(discount_pct IS NULL), (p.price_original - p.price_current) DESC, p.id",
    "store":    "p.store, p.title, p.id",
    "title":    "p.title, p.id",
}


def products(conn, sort="discount", limit=None, offset=0, **filters) -> list[dict]:
    """Filtered product rows. `sort` must be a key of _ORDER_BY."""
    if sort not in _ORDER_BY:
        raise ValueError(f"Unknown sort '{sort}'. Valid: {', '.join(sorted(_ORDER_BY))}")

    where, params = filter_clause(**filters)
    sql = f"SELECT {_PRODUCT_COLS} FROM product p WHERE {where} ORDER BY {_ORDER_BY[sort]}"
    if limit is not None:
        sql += " LIMIT ? OFFSET ?"
        params += [int(limit), int(offset)]
    return [dict(r) for r in conn.execute(sql, params)]


def count_products(conn, **filters) -> int:
    """Row count under the same filters `products` applies."""
    where, params = filter_clause(**filters)
    return conn.execute(f"SELECT COUNT(*) FROM product p WHERE {where}", params).fetchone()[0]


# ---------------------------------------------------------------------------
# Stores
# ---------------------------------------------------------------------------

def stores(conn, active_only: bool = False) -> list[dict]:
    """Every store with its product counts and last scrape time."""
    sql = """
        SELECT s.name, s.base_url, s.city, s.active,
               COUNT(p.id) AS n_products,
               SUM(COALESCE(p.in_stock, 0)) AS n_in_stock,
               (SELECT MAX(ts) FROM scrape_run r WHERE r.store = s.name) AS last_scrape,
               -- Whether that latest run worked; a store whose page moved keeps
               -- its old products, so a count alone does not show the failure.
               (SELECT r.success FROM scrape_run r WHERE r.store = s.name
                 ORDER BY r.ts DESC LIMIT 1)                     AS last_success
          FROM store s
          LEFT JOIN product p ON p.store = s.name
    """
    if active_only:
        sql += " WHERE s.active = 1"
    sql += " GROUP BY s.name ORDER BY s.name"
    return [dict(r) for r in conn.execute(sql)]


def fresh_stores(conn, cutoff_ts: int) -> set[str]:
    """Stores with a successful scrape at or after `cutoff_ts`."""
    return {
        r[0] for r in conn.execute(
            "SELECT store FROM scrape_run WHERE success = 1 "
            "GROUP BY store HAVING MAX(ts) >= ?",
            (int(cutoff_ts),),
        )
    }
