"""Migration integrity and the personal-use features."""

from tablero import changes
from tablero import watchlist


# ---------------------------------------------------------------------------
# Migration
# ---------------------------------------------------------------------------

def test_all_products_imported(db_conn):
    assert db_conn.execute("SELECT COUNT(*) FROM product").fetchone()[0] == 6


def test_prices_are_numeric(db_conn):
    """The whole point of the SQLite move: no consumer should re-parse strings."""
    rows = db_conn.execute(
        "SELECT price_eff FROM product WHERE price_eff IS NOT NULL"
    ).fetchall()
    assert rows
    assert all(isinstance(r[0], float) for r in rows)


def test_sale_price_wins_over_original(db_conn):
    row = db_conn.execute(
        "SELECT price_original, price_current, price_eff FROM product "
        "WHERE store='tiendaA' AND title='Catan'"
    ).fetchone()
    assert (row["price_original"], row["price_current"], row["price_eff"]) == (49990.0, 39990.0, 39990.0)


def test_norm_is_persisted(db_conn):
    assert db_conn.execute(
        "SELECT COUNT(*) FROM product WHERE norm IS NULL OR norm = ''"
    ).fetchone()[0] == 0


def test_out_of_stock_detected(db_conn):
    row = db_conn.execute(
        "SELECT in_stock FROM product WHERE store='tiendaA' AND title='Wingspan'"
    ).fetchone()
    assert row["in_stock"] == 0


def test_same_game_clusters_across_stores(db_conn):
    """Catan in two stores is one game, which is what makes comparison work."""
    row = db_conn.execute(
        "SELECT g.id, COUNT(DISTINCT p.store) n FROM game g "
        "JOIN product p ON p.game_id=g.id WHERE g.norm='catan' GROUP BY g.id"
    ).fetchone()
    assert row["n"] == 2


def test_expansion_is_its_own_game(db_conn):
    kinds = dict(db_conn.execute("SELECT title, kind FROM game"))
    assert kinds["Catan"] == "game"
    assert kinds["Catan Expansión Navegantes"] == "expansion"


# ---------------------------------------------------------------------------
# Features
# ---------------------------------------------------------------------------

def test_watchlist_roundtrip(db_conn):
    gid = db_conn.execute("SELECT id FROM game WHERE norm = ?", ("catan",)).fetchone()[0]
    assert watchlist.add(db_conn, gid, target=42000)
    entry = watchlist.entries(db_conn)[0]
    assert entry["title"] == "Catan"
    assert entry["hit"] is True          # cheapest is 39,990 <= 42,000
    assert watchlist.remove(db_conn, gid)
    assert watchlist.entries(db_conn) == []


def test_watchlist_rejects_unknown_game(db_conn):
    assert watchlist.add(db_conn, 999_999) is False


def test_price_drop_since_cursor(db_conn):
    """Catan at tiendaB fell 52,990 -> 45,990 between the two observations."""
    drops = changes.since_last_review(db_conn, since=1_700_050_000, min_pct=1.0, kinds=["baja"])
    assert len(drops) == 1
    assert drops[0]["old_price"] == 52990.0
    assert drops[0]["new_price"] == 45990.0
    assert drops[0]["drop_pct"] > 13


def test_no_changes_without_cursor(db_conn):
    assert changes.since_last_review(db_conn) == []


# ---------------------------------------------------------------------------
# Search filters and ordering
# ---------------------------------------------------------------------------
