"""
Regressions for bugs found in code review of 7331d57.

Each test reproduces the original failure, so a reintroduction fails loudly
rather than silently.
"""
import sqlite3

import pytest

from tablero import db as db_mod
from tablero import ingest


# ---------------------------------------------------------------------------
# MEDIUM: statements exceeded SQLite's variable cap on large stores
# ---------------------------------------------------------------------------

# SQLite builds before 3.32 default to 999 host parameters. The project's own
# catalog has stores well past that (updown ~3,300 products), so an unbounded
# "IN (?,?,...)" made `update` fail outright on any older environment.
LEGACY_VARIABLE_LIMIT = 999

# Connection.setlimit is Python 3.11+. Where it is unavailable the constraint
# cannot be simulated, so these skip rather than pass vacuously.
requires_setlimit = pytest.mark.skipif(
    not hasattr(sqlite3.Connection, "setlimit"),
    reason="sqlite3.Connection.setlimit requires Python 3.11+",
)


def _many_records(n: int) -> list:
    return [
        {"title": f"Juego Numero {i}", "url": f"https://tiendaa.cl/p/{i}",
         "original_price": f"${10000 + i}", "current_price": None,
         "stock_status": None}
        for i in range(n)
    ]


@requires_setlimit
@pytest.mark.parametrize("count", [1200, 2500])
def test_ingest_survives_legacy_variable_limit(tmp_path, count):
    conn = db_mod.connect(tmp_path / "t.db", create=True)
    db_mod.init_db(conn)
    conn.setlimit(sqlite3.SQLITE_LIMIT_VARIABLE_NUMBER, LEGACY_VARIABLE_LIMIT)
    try:
        result = ingest.ingest_store(conn, "tiendaA", _many_records(count))
        assert result["ingested"] == count
    finally:
        conn.close()


@requires_setlimit
def test_price_observations_recorded_under_legacy_limit(tmp_path):
    """The price-history write is the other statement that scaled with store size."""
    conn = db_mod.connect(tmp_path / "t.db", create=True)
    db_mod.init_db(conn)
    conn.setlimit(sqlite3.SQLITE_LIMIT_VARIABLE_NUMBER, LEGACY_VARIABLE_LIMIT)
    try:
        ingest.ingest_store(conn, "tiendaA", _many_records(1500), ts=1_700_100_000)
        assert conn.execute("SELECT COUNT(*) FROM price_obs").fetchone()[0] >= 1500
    finally:
        conn.close()


def test_flags_still_clear_for_unseen_products(db_conn):
    """
    Clearing flags up front must stay equivalent to the old NOT IN.

    tiendaB's Catan carries flag='new' from the fixture and is absent from this
    scrape, so it must end up unflagged.
    """
    ingest.ingest_store(db_conn, "tiendaB", [
        {"title": "Wingspan", "url": "https://tiendab.cl/p/wingspan",
         "original_price": "$59.990", "current_price": None, "stock_status": None},
    ], ts=1_700_200_000)

    assert db_conn.execute(
        "SELECT flag FROM product WHERE store='tiendaB' AND norm='catan'"
    ).fetchone()[0] is None


def test_price_observation_still_collapses_unchanged_prices(db_conn):
    """The INSERT...SELECT rewrite must keep the no-change-no-row behaviour."""
    records = [{"title": "Catan", "url": "https://tiendaa.cl/producto/catan/",
                "original_price": "$49.990", "current_price": "$39.990",
                "stock_status": None}]
    ingest.ingest_store(db_conn, "tiendaA", records, ts=1_700_200_000)
    first = db_conn.execute("SELECT COUNT(*) FROM price_obs").fetchone()[0]
    ingest.ingest_store(db_conn, "tiendaA", records, ts=1_700_300_000)
    assert db_conn.execute("SELECT COUNT(*) FROM price_obs").fetchone()[0] == first


# ---------------------------------------------------------------------------
# LOW: identifier quoting
# ---------------------------------------------------------------------------

def test_table_counts_quotes_identifiers(db_conn):
    """A table name cannot be bound, so it must at least be quoted."""
    db_conn.execute('CREATE TABLE "weird ""name"" table" (x INTEGER)')
    db_conn.commit()
    counts = db_mod.table_counts(db_conn)
    assert counts['weird "name" table'] == 0
    assert counts["product"] > 0
