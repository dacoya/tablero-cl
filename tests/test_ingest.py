"""Live scrape ingest: identity, flags, and price-history collapsing."""
from tablero import ingest


def _record(title, url, original="$10.000", current=None, stock=None):
    return {"title": title, "url": url, "original_price": original,
            "current_price": current, "stock_status": stock}


def test_new_product_is_flagged_new(db_conn):
    result = ingest.ingest_store(db_conn, "tiendaA", [
        _record("Azul", "https://tiendaa.cl/producto/azul/"),
    ], ts=1_700_200_000)
    assert result["new"] == 1

    row = db_conn.execute(
        "SELECT flag, price_eff FROM product WHERE url_canon='tiendaa.cl/producto/azul'"
    ).fetchone()
    assert row["flag"] == "new"
    assert row["price_eff"] == 10000.0


def test_restock_detected(db_conn):
    """tiendaA's Wingspan starts out of stock in the fixture."""
    result = ingest.ingest_store(db_conn, "tiendaA", [
        _record("Wingspan", "https://tiendaa.cl/producto/wingspan/", stock=None),
    ], ts=1_700_200_000)
    assert result["restock"] == 1


def test_same_url_updates_not_duplicates(db_conn):
    before = db_conn.execute("SELECT COUNT(*) FROM product WHERE store='tiendaA'").fetchone()[0]
    ingest.ingest_store(db_conn, "tiendaA", [
        _record("Catan", "https://tiendaa.cl/producto/catan/", original="$44.990"),
    ], ts=1_700_200_000)
    after = db_conn.execute("SELECT COUNT(*) FROM product WHERE store='tiendaA'").fetchone()[0]
    assert after == before
    price = db_conn.execute(
        "SELECT price_eff FROM product WHERE url_canon='tiendaa.cl/producto/catan'"
    ).fetchone()[0]
    assert price == 44990.0


def test_unchanged_price_records_no_observation(db_conn):
    """Without collapsing, every scrape would append a duplicate row per product."""
    ingest.ingest_store(db_conn, "tiendaA", [
        _record("Catan", "https://tiendaa.cl/producto/catan/", original="$49.990",
                current="$39.990"),
    ], ts=1_700_200_000)
    first = db_conn.execute("SELECT COUNT(*) FROM price_obs").fetchone()[0]

    ingest.ingest_store(db_conn, "tiendaA", [
        _record("Catan", "https://tiendaa.cl/producto/catan/", original="$49.990",
                current="$39.990"),
    ], ts=1_700_300_000)
    assert db_conn.execute("SELECT COUNT(*) FROM price_obs").fetchone()[0] == first


def test_changed_price_records_observation(db_conn):
    ingest.ingest_store(db_conn, "tiendaA", [
        _record("Catan", "https://tiendaa.cl/producto/catan/", original="$49.990",
                current="$39.990"),
    ], ts=1_700_200_000)
    before = db_conn.execute("SELECT COUNT(*) FROM price_obs").fetchone()[0]

    ingest.ingest_store(db_conn, "tiendaA", [
        _record("Catan", "https://tiendaa.cl/producto/catan/", original="$49.990",
                current="$31.990"),
    ], ts=1_700_300_000)
    assert db_conn.execute("SELECT COUNT(*) FROM price_obs").fetchone()[0] > before


def test_empty_scrape_preserves_data(db_conn):
    """A network failure must not look like a store dropping its catalog."""
    before = db_conn.execute("SELECT COUNT(*) FROM product WHERE store='tiendaA'").fetchone()[0]
    result = ingest.ingest_store(db_conn, "tiendaA", [], ts=1_700_200_000)
    assert result["skipped"] is True
    after = db_conn.execute("SELECT COUNT(*) FROM product WHERE store='tiendaA'").fetchone()[0]
    assert after == before


def test_stale_flags_are_cleared(db_conn):
    """tiendaB's Catan carries flag='new' from the fixture."""
    assert db_conn.execute(
        "SELECT flag FROM product WHERE store='tiendaB' AND norm='catan'"
    ).fetchone()[0] == "new"

    ingest.ingest_store(db_conn, "tiendaB", [
        _record("Wingspan", "https://tiendab.cl/p/wingspan"),
    ], ts=1_700_200_000)

    assert db_conn.execute(
        "SELECT flag FROM product WHERE store='tiendaB' AND norm='catan'"
    ).fetchone()[0] is None


def test_failure_recorded_without_touching_products(db_conn):
    ingest.record_failure(db_conn, "tiendaA", ts=1_700_400_000)
    row = db_conn.execute(
        "SELECT success, n_products FROM scrape_run WHERE store='tiendaA' AND ts=1700400000"
    ).fetchone()
    assert row["success"] == 0 and row["n_products"] is None

