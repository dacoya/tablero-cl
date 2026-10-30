"""
Derived rankings: smart sorts and the store leaderboard.

Rewritten over SQL. The previous version operated on DataFrames and re-parsed
price strings on every row; prices are numeric on disk now, so converting them
back into "$45.990" just to re-parse would be pure waste.

SQLite has no median aggregate, so the median-per-game CTE below picks the
middle row(s) by ROW_NUMBER and averages them -- correct for both odd and even
counts. Median matters here rather than mean: one absurdly-priced listing should
not redefine what a game "normally" costs.

Pure: connection in, list[dict] out. Nothing prints.
"""
from . import repo

SMART_SORT_OPTIONS = ("value", "scarcity", "volatility")

# Median effective price per game, over rows with a usable price.
_MEDIAN_CTE = """
    WITH priced AS (
        SELECT id, game_id, store, price_eff, in_stock,
               ROW_NUMBER() OVER (PARTITION BY game_id ORDER BY price_eff) AS rn,
               COUNT(*)     OVER (PARTITION BY game_id)                    AS cnt
          FROM product
         WHERE price_eff IS NOT NULL AND price_eff > 0
    ),
    med AS (
        SELECT game_id, AVG(price_eff) AS median
          FROM priced
         WHERE rn IN ((cnt + 1) / 2, (cnt + 2) / 2)
         GROUP BY game_id
    ),
    spread AS (
        SELECT game_id,
               MIN(price_eff) AS lo,
               MAX(price_eff) AS hi,
               COUNT(DISTINCT store) AS n_stores
          FROM priced
         GROUP BY game_id
    )
"""

# Deal index ("value"): how attractive a sale is, in pesos.
#
#   ref = the list price; for a game sold in 2+ stores, capped at the market
#         median (but never below this price), so an inflated "antes" price
#         does not make a deal.
#   S   = ref - price                       the saving, in pesos
#   d   = S / ref                           the saving, as a fraction
#   index = S * d / (d + DEAL_D0) * ref / (ref + DEAL_P0)
#
# Pesos saved carry the score: 20k off is worth more than 13.5k off. d/(d+D0)
# drops off only for token discounts (5% on 300k), and ref/(ref+P0) damps
# cheap games, where a 90% cut is more likely clearance than a find:
#   15k -> 1.5k: 5.5k   40k -> 20k: 12.1k   80k -> 60k: 13.3k   300k -5%: 7.0k
# (exact: 5,481 / 12,121 / 13,333 / 7,031)
DEAL_D0 = 0.05
DEAL_P0 = 20000
_DEAL_REF = """(CASE WHEN s.n_stores >= 2
                     THEN MIN(COALESCE(p.price_original, p.price_eff), MAX(m.median, p.price_eff))
                     ELSE COALESCE(p.price_original, p.price_eff) END)"""
_DEAL_SAVING = f"({_DEAL_REF} - p.price_eff)"
DEAL_INDEX_SQL = f"""(CASE WHEN {_DEAL_SAVING} > 0
     THEN {_DEAL_SAVING} * {_DEAL_SAVING} / ({_DEAL_SAVING} + {DEAL_D0} * {_DEAL_REF})
          * {_DEAL_REF} / ({_DEAL_REF} + {DEAL_P0})
     ELSE 0 END)"""

# Each smart sort scores a row; all are ordered descending by that score.
_SCORE = {
    "value": DEAL_INDEX_SQL,
    # Carried by few stores: concentrated availability.
    "scarcity": "-s.n_stores",
    # Wide cross-store spread: the same game costs very different amounts.
    "volatility": "(s.hi - s.lo) / m.median",
}


def smart_products(conn, by: str = "value", limit: int | None = None,
                   offset: int = 0,
                   store: str | None = None, in_stock_only: bool = False,
                   kind=None, on_sale: bool = False,
                   min_price=None, max_price=None,
                   include_stale: bool = False, min_discount=None,
                   flag=None) -> list[dict]:
    """
    Products ranked by a derived metric rather than a single column.

    Takes the same filters as `repo.products`. It previously accepted no price
    bounds at all, so `--max-price` was silently dropped whenever a smart sort
    was chosen and the output contradicted the flag the user had passed.
    """
    if by not in SMART_SORT_OPTIONS:
        raise ValueError(
            f"Unknown smart sort '{by}'. Options: {', '.join(SMART_SORT_OPTIONS)}")

    where, params = repo.filter_clause(
        store=store, in_stock_only=in_stock_only, min_price=min_price,
        max_price=max_price, on_sale=on_sale, kind=kind, include_stale=include_stale,
        min_discount=min_discount, flag=flag)

    sql = f"""
        {_MEDIAN_CTE}
        SELECT p.id AS product_id, p.store, p.title, p.url, p.game_id, p.kind,
               p.price_original, p.price_current, p.price_eff, p.in_stock, p.flag,
               p.image_url, s.n_stores, m.median,
               {repo.DISCOUNT_PCT_SQL} AS discount_pct,
               ROUND({_SCORE[by]}, 4) AS score
          FROM product p
          JOIN med    m ON m.game_id = p.game_id
          JOIN spread s ON s.game_id = p.game_id
         WHERE m.median > 0 AND {where}
         ORDER BY score DESC, p.id
    """
    # limit=None means "everything", the same contract as repo.products.
    if limit is not None:
        sql += " LIMIT ? OFFSET ?"
        params = [*params, int(limit), int(offset)]
    return [dict(r) for r in conn.execute(sql, params)]


def browse(conn, sort: str = "discount", limit: int | None = None,
           offset: int = 0, **filters) -> tuple[list[dict], int]:
    """
    One page of browse rows plus the total under the same filters.

    Lives here rather than in repo because picking between a plain column sort
    and a derived one needs the smart sorts, and repo importing analytics would
    close an import cycle.

    Rows and total come back together deliberately. Counting with one set of
    filters and listing with another is exactly the bug that made a heading
    claim 1.284 results above a list that had been filtered down to 40.
    """
    if sort in SMART_SORT_OPTIONS:
        rows = smart_products(conn, by=sort, limit=limit, offset=offset, **filters)
    else:
        rows = repo.products(conn, sort=sort, limit=limit, offset=offset, **filters)
    # The smart sorts additionally drop games with no usable median, so for
    # those this total is an upper bound rather than an exact count.
    return rows, repo.count_products(conn, **filters)


