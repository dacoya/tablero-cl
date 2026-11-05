"""Product images: what the parsers pick, what derive keeps, how ingest stores it."""
import sqlite3

import pytest
from bs4 import BeautifulSoup

from tablero import db as db_mod, derive, ingest, parsers


def _card(html):
    return BeautifulSoup(html, "html.parser").div


@pytest.mark.parametrize("html, base, expected", [
    # Lazy-loading attributes win over a placeholder src.
    ('<div><img src="data:image/gif;base64,R0l" data-src="https://s.cl/a.jpg"></div>', "", "https://s.cl/a.jpg"),
    ('<div><img src="/spinner.gif" data-lazy-src="https://s.cl/b.jpg"></div>', "", "https://s.cl/b.jpg"),
    # srcset: the first candidate, not the whole attribute.
    ('<div><img srcset="https://s.cl/c-300.jpg 300w, https://s.cl/c-600.jpg 600w"></div>', "", "https://s.cl/c-300.jpg"),
    # Shopify width templates and protocol-relative URLs.
    ('<div><img data-src="//cdn.shopify.com/x_{width}x.jpg"></div>', "", "https://cdn.shopify.com/x_360x.jpg"),
    # Relative paths need the store's base; without one they are dropped.
    ('<div><img src="/img/d.jpg"></div>', "https://s.cl/", "https://s.cl/img/d.jpg"),
    ('<div><img src="/img/d.jpg"></div>', "", None),
    # <picture><source> counts, a card with only data: URIs has no image.
    ('<div><picture><source srcset="https://s.cl/e.webp"></picture></div>', "", "https://s.cl/e.webp"),
    ('<div><img src="data:image/png;base64,iVBOR"></div>', "", None),
    ('<div>sin foto</div>', "", None),
])
def test_img_picks_the_real_photo(html, base, expected):
    assert parsers._img(_card(html), base) == expected


@pytest.mark.parametrize("value, expected", [
    ("https://s.cl/a.jpg", "https://s.cl/a.jpg"),
    ("  https://s.cl/a.jpg ", "https://s.cl/a.jpg"),
    # The app forbids cleartext, so an http photo could never load there.
    ("http://s.cl/a.jpg", "https://s.cl/a.jpg"),
    ("HTTP://S.CL/A.JPG", "https://S.CL/A.JPG"),
    ("javascript:alert(1)", None),
    ("//cdn.s.cl/a.jpg", None),
    ("", None),
    (None, None),
])
def test_derive_keeps_only_absolute_http_urls(value, expected):
    assert derive.image_url(value) == expected


def _v4_database(path):
    """A database as schema v4 left it: no image_url column."""
    conn = sqlite3.connect(path)
    schema = db_mod.SCHEMA_PATH.read_text(encoding="utf-8")
    conn.executescript(schema.replace("    image_url      TEXT,\n", ""))
    # Nor what v6 added.
    conn.executescript("DROP TABLE bgg; DROP TABLE bgg_alias; ALTER TABLE game DROP COLUMN bgg_id;")
    conn.execute("INSERT INTO store(name) VALUES ('s')")
    conn.execute("INSERT INTO game(norm, title, created_at) VALUES ('catan', 'Catan', 1)")
    conn.execute(
        "INSERT INTO product(store, url_canon, url, game_id, title_raw, title, norm, "
        "first_seen, last_seen) VALUES ('s', 's.cl/catan', 'https://s.cl/catan', 1, "
        "'Catan', 'Catan', 'catan', 1, 1)")
    conn.execute("PRAGMA user_version = 4")
    conn.commit()
    conn.close()


@pytest.mark.parametrize("read_only", [False, True])
def test_older_database_is_upgraded_on_open(tmp_path, read_only):
    """`tablero update` on a v4 database must not die on the new column."""
    path = tmp_path / "old.db"
    _v4_database(path)
    conn = db_mod.connect(path, read_only=read_only)
    try:
        assert db_mod.schema_version(conn) == db_mod.SCHEMA_VERSION
        row = conn.execute("SELECT title, image_url FROM product").fetchone()
        assert (row["title"], row["image_url"]) == ("Catan", None)
    finally:
        conn.close()


def test_rebuild_reader_sees_the_file_as_it_is(tmp_path):
    path = tmp_path / "old.db"
    _v4_database(path)
    conn = db_mod.connect(path, read_only=True, upgrade_schema=False)
    try:
        assert db_mod.schema_version(conn) == 4
    finally:
        conn.close()


def test_a_scrape_without_a_photo_keeps_the_known_one(tmp_path):
    conn = db_mod.connect(tmp_path / "t.db", create=True)
    db_mod.init_db(conn)
    rec = {"title": "Catan", "original_price": "$40.000", "url": "https://s.cl/catan"}
    ingest.ingest_store(conn, "s", [{**rec, "image": "https://s.cl/catan.jpg"}], ts=1)
    ingest.ingest_store(conn, "s", [rec], ts=2)
    assert conn.execute("SELECT image_url FROM product").fetchone()[0] == "https://s.cl/catan.jpg"
    ingest.ingest_store(conn, "s", [{**rec, "image": "https://s.cl/catan-2.jpg"}], ts=3)
    assert conn.execute("SELECT image_url FROM product").fetchone()[0] == "https://s.cl/catan-2.jpg"
    conn.close()


def test_every_fixture_page_yields_photos():
    """
    A parser that stops finding photos fails here, not silently in the app.

    Per page rather than overall: one store losing its images entirely would
    barely move a catalogue-wide ratio.
    """
    from tablero import fixtures

    thin = {}
    for path in fixtures.all_fixtures():
        _, expected = fixtures.load(path)
        with_image = sum(1 for r in expected if r.get("image"))
        if with_image < 0.9 * len(expected):
            thin[f"{path.parent.name}/{path.name}"] = f"{with_image}/{len(expected)}"
    assert thin == {}
