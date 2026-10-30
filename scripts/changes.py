"""
"What changed since I last looked."

The `flag` column on product only survives one update cycle -- it means
"changed in the most recent scrape", so anything missed while away is gone for
good. These queries work off recorded price observations and a named cursor
instead, which makes the answer independent of how many scrapes have run.

Cursors are named so several consumers can each keep their own position (the
terminal and the phone should not clobber each other's "last seen").
"""
import time

DEFAULT_CURSOR = "last_reviewed"


def get_cursor(conn, name: str = DEFAULT_CURSOR) -> int | None:
    row = conn.execute("SELECT ts FROM cursor WHERE name = ?", (name,)).fetchone()
    return row[0] if row else None


def set_cursor(conn, ts: int | None = None, name: str = DEFAULT_CURSOR) -> int:
    ts = int(ts if ts is not None else time.time())
    conn.execute(
        "INSERT INTO cursor(name, ts) VALUES (?,?) "
        "ON CONFLICT(name) DO UPDATE SET ts=excluded.ts",
        (name, ts),
    )
    conn.commit()
    return ts


def _limit(limit) -> int:
    """SQL bind value for `limit`; None means no limit (SQLite reads -1 that way)."""
    return -1 if limit is None else int(limit)


# Ordered most notable first. A product can satisfy several at once -- a
# restock that is also a price drop -- and each row reports the first that
# applies, with the price columns carrying the rest.
CHANGE_KINDS = ("nuevo", "restock", "agotado", "oferta", "fin_oferta", "baja", "alza")

CHANGE_LABELS = {
    "nuevo": "nuevo",
    "restock": "volvió a stock",
    "agotado": "se agotó",
    "oferta": "entró en oferta",
    "fin_oferta": "terminó oferta",
    "baja": "bajó de precio",
    "alza": "subió de precio",
}

# One observation per product on each side of the cutoff: the newest taken at
# or after it, and the newest taken before. Comparing those two is what makes
# the answer independent of how many scrapes ran while you were away.
#
# "Newest" is MAX(ts) with the other columns bare: SQLite takes them from the
# row that holds the max. A ROW_NUMBER() window gave the same rows but sorted
# the whole history first, which made Cambios the slowest screen on the phone.
#
# `on_sale` is derived rather than stored: an observation was taken during an
# offer when its effective price sits below the list price it recorded.
# `unknown_*` marks rows written before schema v4, which carry NULL in the new
# columns -- not knowing is not the same as "no", and claiming a restock from
# a NULL would invent changes that never happened.
_SIDE = """
    SELECT product_id, ts, price, in_stock,
           (price_original IS NOT NULL AND price < price_original) AS on_sale,
           price_original IS NULL AS unknown_sale,
           in_stock       IS NULL AS unknown_stock
      FROM (SELECT product_id, MAX(ts) AS ts, price, in_stock, price_original
              FROM price_obs WHERE ts {op} ?{only} GROUP BY product_id)
"""

# The "before" side only matters for products that also have an observation
# after the cutoff: a row is reported when both sides exist, or when it is new,
# and a new product has nothing before the cutoff anyway. Skipping the rest
# spares a pass over most of the history.
_ONLY_MOVED = " AND product_id IN (SELECT product_id FROM price_obs WHERE ts >= ?)"

_SINCE_SQL = f"""
    WITH after AS ({_SIDE.format(op='>=', only='')}),
         before AS ({_SIDE.format(op='<', only=_ONLY_MOVED)})
    SELECT p.id AS product_id, p.store, p.title, p.url, p.game_id, p.kind,
           p.in_stock, p.price_eff, p.image_url,
           b.price AS old_price,
           COALESCE(a.price, p.price_eff) AS new_price,
           COALESCE(a.ts, p.first_seen)   AS seen_at,
           CASE WHEN b.price > 0 AND a.price IS NOT NULL
                THEN ROUND((b.price - a.price) * 100.0 / b.price, 1) END AS drop_pct,
           CASE
             -- New only when nothing was recorded before the cutoff. A
             -- product carrying an earlier observation existed then, whatever
             -- first_seen says, and calling it new would hide the move it
             -- actually made.
             WHEN p.first_seen >= ? AND b.product_id IS NULL            THEN 'nuevo'
             WHEN b.unknown_stock = 0 AND b.in_stock = 0
                                      AND a.in_stock = 1                THEN 'restock'
             WHEN b.unknown_stock = 0 AND b.in_stock = 1
                                      AND a.in_stock = 0                THEN 'agotado'
             WHEN b.unknown_sale = 0 AND b.on_sale = 0 AND a.on_sale = 1 THEN 'oferta'
             WHEN b.unknown_sale = 0 AND b.on_sale = 1 AND a.on_sale = 0 THEN 'fin_oferta'
             WHEN a.price < b.price                                     THEN 'baja'
             WHEN a.price > b.price                                     THEN 'alza'
           END AS change
      FROM product p
      LEFT JOIN after  a ON a.product_id = p.id
      LEFT JOIN before b ON b.product_id = p.id
     WHERE p.first_seen >= ? OR (a.product_id IS NOT NULL AND b.product_id IS NOT NULL)
"""

_ORDER = " ".join(f"WHEN '{k}' THEN {i}" for i, k in enumerate(CHANGE_KINDS))


def since_last_review(conn, since: int | None = None, name: str = DEFAULT_CURSOR,
                      min_pct: float = 0.0, kinds=None,
                      limit: int | None = 100) -> list[dict]:
    """
    Everything that moved since the cutoff, each row saying what moved.

    This used to answer one question -- which prices fell -- so a game coming
    back into stock, or a sale starting at the same headline price, was
    invisible. Those are the changes you act on in a shop.

    `min_pct` applies only to the two pure price rows ('baja' and 'alza'); a
    restock is worth seeing whatever the price did. `kinds` restricts to a
    subset of CHANGE_KINDS.
    """
    since = since if since is not None else get_cursor(conn, name)
    if since is None:
        return []

    wanted = tuple(kinds) if kinds else CHANGE_KINDS
    bad = set(wanted) - set(CHANGE_KINDS)
    if bad:
        raise ValueError(
            f"Unknown change kind(s): {', '.join(sorted(bad))}. "
            f"Options: {', '.join(CHANGE_KINDS)}")

    sql = f"""
        SELECT * FROM ({_SINCE_SQL}) c
         WHERE c.change IN ({','.join('?' * len(wanted))})
           AND (c.change NOT IN ('baja', 'alza')
                OR ABS(COALESCE(c.drop_pct, 0)) >= ?)
         ORDER BY CASE c.change {_ORDER} END,
                  ABS(COALESCE(c.drop_pct, 0)) DESC,
                  c.seen_at DESC,
                  c.product_id
         LIMIT ?
    """
    # Five `since` binds inside _SINCE_SQL, in source order: the after side,
    # the before side and its _ONLY_MOVED filter, the 'nuevo' test, and the WHERE.
    params = (since, since, since, since, since, *wanted, float(min_pct), _limit(limit))
    return [dict(r) for r in conn.execute(sql, params)]
