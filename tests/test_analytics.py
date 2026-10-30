"""Smart sorts, leaderboard, history trends, alerts, and price validation."""
import pytest

from tablero import alerts
from tablero import changes
from tablero import analytics
from tablero import history
from tablero import validation
from tablero import watchlist


# ---------------------------------------------------------------------------
# Smart sorts
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("by", analytics.SMART_SORT_OPTIONS)
def test_smart_sorts_run_and_score(db_conn, by):
    rows = analytics.smart_products(db_conn, by=by, limit=10)
    assert rows
    assert all("score" in r for r in rows)
    scores = [r["score"] for r in rows]
    assert scores == sorted(scores, reverse=True)


def test_unknown_smart_sort_rejected(db_conn):
    with pytest.raises(ValueError, match="Unknown smart sort"):
        analytics.smart_products(db_conn, by="nonsense")


def test_value_favours_price_below_median(db_conn):
    """tiendaA sells Catan at 39,990; tiendaB at 45,990. The cheaper one wins."""
    rows = analytics.smart_products(db_conn, by="value", limit=50)
    catan = [r for r in rows if r["title"].startswith("Catan") and r["kind"] == "game"]
    assert catan[0]["store"] == "tiendaA"


def test_scarcity_prefers_fewer_stores(db_conn):
    """Catan is in two stores; the expansion in one, so it is scarcer."""
    rows = analytics.smart_products(db_conn, by="scarcity", limit=50)
    by_title = {r["title"]: r["score"] for r in rows}
    assert by_title["Catan Expansión Navegantes"] > by_title["Catan"]


def test_median_is_per_game_not_global(db_conn):
    rows = analytics.smart_products(db_conn, by="value", limit=50)
    medians = {r["title"]: r["median"] for r in rows}
    # Catan's median comes from its own two listings, not the whole catalog.
    assert medians["Catan"] == pytest.approx((39990.0 + 45990.0) / 2)


# ---------------------------------------------------------------------------
# Leaderboard
# ---------------------------------------------------------------------------

def test_sparkline_shapes():
    assert history.sparkline([1, 2, 3]) == "▁▄█"
    assert history.sparkline([5, 5, 5]) == "▁▁▁"
    assert history.sparkline([]) == ""
    assert history.sparkline([None]) == ""


def test_game_trends_from_observations(db_conn):
    """tiendaB's Catan fell 52,990 -> 45,990 across two observations."""
    gid = db_conn.execute("SELECT id FROM game WHERE norm = ?", ("catan",)).fetchone()[0]
    trends = history.game_trends(db_conn, gid, min_points=2)
    assert len(trends) == 1
    t = trends[0]
    assert (t["store"], t["first"], t["last"], t["n"]) == ("tiendaB", 52990.0, 45990.0, 2)
    assert t["change_pct"] < 0
    assert t["spark"]


def test_min_points_hides_single_observations(db_conn):
    """One point is a price, not a trend."""
    gid = db_conn.execute("SELECT id FROM game WHERE norm = ?", ("catan",)).fetchone()[0]
    assert len(history.game_trends(db_conn, gid, min_points=2)) == 1
    assert len(history.game_trends(db_conn, gid, min_points=1)) == 1


def test_alerts_from_watchlist_respects_target(db_conn):
    gid = db_conn.execute("SELECT id FROM game WHERE norm = ?", ("catan",)).fetchone()[0]
    watchlist.add(db_conn, gid, target=45000)      # cheapest is 39,990
    fired = alerts.from_watchlist(db_conn)
    assert len(fired) == 1
    assert fired[0]["price"] == 39990.0
    assert fired[0]["store"] == "tiendaA"


def test_alerts_silent_above_target(db_conn):
    gid = db_conn.execute("SELECT id FROM game WHERE norm = ?", ("catan",)).fetchone()[0]
    watchlist.add(db_conn, gid, target=1000)
    assert alerts.from_watchlist(db_conn) == []


def test_alerts_skip_entries_without_target(db_conn):
    gid = db_conn.execute("SELECT id FROM game WHERE norm = ?", ("catan",)).fetchone()[0]
    watchlist.add(db_conn, gid, target=None)
    assert alerts.from_watchlist(db_conn) == []


def test_alerts_in_stock_filter(db_conn):
    """tiendaA's Wingspan is out of stock, so only tiendaB can satisfy it."""
    gid = db_conn.execute("SELECT id FROM game WHERE norm = ?", ("wingspan",)).fetchone()[0]
    watchlist.add(db_conn, gid, target=70000)
    fired = alerts.from_watchlist(db_conn, in_stock_only=True)
    assert len(fired) == 1 and fired[0]["store"] == "tiendaB"


@pytest.mark.parametrize("row, reason", [
    ({"price_original": -1, "price_current": None}, "nonpositive_original"),
    ({"price_original": 100.0, "price_current": 0}, "nonpositive_current"),
    ({"price_original": None, "price_current": None}, "no_price"),
    ({"price_original": 100.0, "price_current": 200.0}, "offer_above_original"),
    ({"price_original": 100.0, "price_current": 5.0}, "discount_over_90pct"),
    ({"price_original": 100.0, "price_current": 50.0}, None),
    ({"price_original": 100.0, "price_current": None}, None),
])
def test_validation_rules(row, reason):
    assert validation.check(row) == reason


def test_partition_counts_reasons():
    clean, reasons = validation.partition([
        {"price_original": 100.0, "price_current": 50.0},
        {"price_original": 100.0, "price_current": 200.0},
        {"price_original": None, "price_current": None},
    ])
    assert len(clean) == 1
    assert reasons == {"offer_above_original": 1, "no_price": 1}


def test_changes_limit_none_means_unlimited(db_conn):
    """parser.DEFAULT_LIMIT is None; the TUI passes it straight through."""
    assert changes._limit(None) == -1
    assert changes._limit(0) == 0
    assert changes._limit(5) == 5
    changes.set_cursor(db_conn)
    assert isinstance(changes.since_last_review(db_conn, min_pct=5.0, limit=None), list)


# ---------------------------------------------------------------------------
# Paging
# ---------------------------------------------------------------------------

def test_browse_pages_do_not_overlap(db_conn):
    """Consecutive pages must be disjoint and agree on the total."""
    first, total = analytics.browse(db_conn, sort="price", limit=2, offset=0)
    second, total_again = analytics.browse(db_conn, sort="price", limit=2, offset=2)

    assert total == total_again
    assert {r["product_id"] for r in first}.isdisjoint(r["product_id"] for r in second)


def test_browse_total_counts_the_whole_filtered_set(db_conn):
    """
    The heading's total must describe the filters, not the page.

    Counting one population and listing another is what once produced
    "Catálogo (5 de 13400)" over five rows of a filtered list.
    """
    page, total = analytics.browse(db_conn, sort="price", limit=1)
    everything, _ = analytics.browse(db_conn, sort="price")

    assert len(page) == 1
    assert total == len(everything)


@pytest.mark.parametrize("sort", analytics.SMART_SORT_OPTIONS)
def test_smart_sorts_honour_offset(db_conn, sort):
    """Paging has to work for the derived orders too, not just column sorts."""
    both = analytics.smart_products(db_conn, by=sort, limit=2)
    second = analytics.smart_products(db_conn, by=sort, limit=1, offset=1)

    if len(both) < 2:
        pytest.skip("fixture catalog too small to page")
    assert [r["product_id"] for r in second] == [both[1]["product_id"]]


def test_paging_is_stable_when_prices_tie(tmp_path):
    """
    Pages concatenate to the full list even when every sort key ties.

    SQLite does not promise any order for tied rows. The build in use returns
    them in rowid order, so this passed before the p.id tiebreak too; it
    guards the contract, which also has to hold on the phone's SQLite.
    """
    from tablero import db as db_mod, ingest, repo

    conn = db_mod.connect(tmp_path / "t.db", create=True)
    db_mod.init_db(conn)
    ingest.ingest_store(conn, "s", [
        {"title": f"Juego {i:02d}", "original_price": "$10.000", "current_price": "$8.000",
         "stock_status": None, "url": f"https://s.cl/p/{i}"}
        for i in range(30)
    ], ts=1)
    for sort in ("discount", "price", "price_desc", "store"):
        everything = [r["product_id"] for r in repo.products(conn, sort=sort)]
        paged = [r["product_id"] for off in range(0, 30, 7)
                 for r in repo.products(conn, sort=sort, limit=7, offset=off)]
        assert paged == everything, sort
        assert len(set(paged)) == 30
    conn.close()


def test_min_discount_and_several_stores(db_conn):
    """The phone's filter sheet: a discount floor and a multi-store pick."""
    from tablero import repo

    both = repo.products(db_conn, store=["tiendaA", "tiendaB"])
    assert {r["store"] for r in both} == {"tiendaA", "tiendaB"}
    assert repo.count_products(db_conn, store=["tiendaA"]) == repo.count_products(db_conn, store="tiendaA")

    discounted = repo.products(db_conn, min_discount=10)
    assert discounted and all(r["discount_pct"] >= 10 for r in discounted)
    assert repo.products(db_conn, min_discount=99) == []
    # Smart sorts take the same filter.
    from tablero import analytics
    assert all(r["discount_pct"] >= 10 for r in analytics.smart_products(db_conn, min_discount=10))


def test_browse_smart_sort_honours_flag(db_conn):
    """browse(flag=...) with a smart sort used to raise TypeError: smart_products took no flag."""
    from tablero import analytics, repo
    db_conn.execute("UPDATE product SET flag = 'new' WHERE id = (SELECT MIN(id) FROM product)")
    rows, total = analytics.browse(db_conn, sort="value", flag="new")
    assert rows and all(r["flag"] == "new" for r in rows)
    assert total == repo.count_products(db_conn, flag="new")


def _deal(conn, original, current):
    """The deal index of a lone listing, through the real SQL."""
    from tablero import ingest
    ingest.ingest_store(conn, "solo", [{"title": f"Juego {original}", "original_price": str(original),
                                         "current_price": str(current), "url": f"https://solo.cl/{original}"}], ts=1)
    rows = analytics.smart_products(conn, by="value", store="solo", limit=None, include_stale=True)
    return next(r["score"] for r in rows if r["price_original"] == original)


def test_deal_index_prefers_real_savings_on_real_games(tmp_path):
    from tablero import db as db_mod
    conn = db_mod.connect(tmp_path / "d.db", create=True)
    db_mod.init_db(conn)
    cheap = _deal(conn, 15000, 1500)      # 90% off a 15k game
    mid = _deal(conn, 40000, 20000)       # half off a 40k game
    big = _deal(conn, 80000, 60000)       # 20k off an 80k game
    token = _deal(conn, 300000, 285000)   # 5% off a 300k game
    assert mid > cheap and big > cheap and cheap > 0
    assert token < mid
    assert cheap == pytest.approx(5481, abs=2) and mid == pytest.approx(12121, abs=2)


def test_deal_index_caps_an_inflated_list_price_at_the_market(db_conn):
    """Catan: tiendaA 49,990 -> 39,990, tiendaB 45,990. Median 42,990 caps the saving at 3,000."""
    rows = analytics.smart_products(db_conn, by="value", limit=50)
    a = next(r for r in rows if r["title"].startswith("Catan") and r["store"] == "tiendaA")
    b = next(r for r in rows if r["title"].startswith("Catan") and r["store"] == "tiendaB")
    saving, ref = 42990 - 39990, 42990
    assert a["score"] == pytest.approx(saving * saving / (saving + 0.05 * ref) * ref / (ref + 20000), abs=0.01)
    assert b["score"] == 0
