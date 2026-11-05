import json

import pytest


@pytest.fixture
def sample_products():
    """A small catalog exercising sales, out-of-stock, flags and noisy titles."""
    return {
        "tiendaA": [
            {"title": "Catan - Juego de Mesa", "original_price": "$49.990",
             "current_price": "$39.990", "stock_status": None,
             "url": "https://tiendaa.cl/producto/catan/"},
            {"title": "Wingspan", "original_price": "$62.000",
             "current_price": None, "stock_status": "Agotado",
             "url": "https://tiendaa.cl/producto/wingspan/"},
            {"title": "Fundas Standard 63x88 para Catan", "original_price": "$3.990",
             "current_price": None, "stock_status": None,
             "url": "https://tiendaa.cl/producto/fundas-catan/"},
        ],
        "tiendaB": [
            {"title": "Catan", "original_price": "$45.990",
             "current_price": None, "stock_status": None,
             "url": "https://tiendab.cl/p/catan", "flag": "new"},
            {"title": "Catan Expansión Navegantes", "original_price": "$34.990",
             "current_price": None, "stock_status": None,
             "url": "https://tiendab.cl/p/catan-navegantes"},
            {"title": "Wingspan", "original_price": "$59.990",
             "current_price": None, "stock_status": None,
             "url": "https://tiendab.cl/p/wingspan"},
        ],
    }


@pytest.fixture
def sample_history():
    return {
        "catan|tiendaB": [
            {"t": 1_700_000_000, "price": 52990.0},
            {"t": 1_700_090_000, "price": 45990.0},
        ],
        "titulo fantasma|tiendaA": [{"t": 1_700_000_000, "price": 1000.0}],
    }


@pytest.fixture
def db_conn(tmp_path, sample_products, sample_history):
    """A database ingested from the sample catalog, with its price history."""
    from tablero import db as db_mod, ingest

    conn = db_mod.connect(tmp_path / "tablero.db", create=True)
    db_mod.init_db(conn)
    for store, records in sample_products.items():
        ingest.ingest_store(conn, store, records, ts=1_700_100_000)
    # A first ingest flags everything new; keep only the flags the catalog carries.
    conn.execute("UPDATE product SET flag = NULL")
    for store, records in sample_products.items():
        for r in records:
            if r.get("flag"):
                conn.execute("UPDATE product SET flag = ? WHERE url = ?", (r["flag"], r["url"]))
    conn.execute("DELETE FROM price_obs")  # the history below is the only price record
    for key, points in sample_history.items():
        norm, store = key.split("|")
        conn.executemany(
            "INSERT OR IGNORE INTO price_obs(product_id, ts, price) "
            "SELECT id, ?, ? FROM product WHERE norm = ? AND store = ?",
            [(p["t"], p["price"], norm, store) for p in points])
    conn.commit()
    yield conn
    conn.close()


@pytest.fixture(autouse=True)
def _no_real_bgg_csv(tmp_path, monkeypatch):
    """Keep every test off the 180k-row BGG CSV a real data directory may hold."""
    from tablero import bgg
    monkeypatch.setattr(bgg, "CSV_PATH", tmp_path / "no-bgg.csv")
