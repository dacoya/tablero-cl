"""
"What changed since I last looked" -- the kinds, not just the price drops.

Stock and offer transitions need observations on both sides of the cutoff, so
these write price_obs rows directly rather than driving a scrape: the point is
the classification, and a fixture catalog cannot be made to go out of stock.
"""
import pytest

from tablero import changes


SINCE = 1_700_500_000
BEFORE, AFTER = SINCE - 3600, SINCE + 3600


def _product(conn, title="Catan", store="tiendaA"):
    row = conn.execute(
        "SELECT id FROM product WHERE store = ? AND title LIKE ? LIMIT 1",
        (store, f"{title}%"),
    ).fetchone()
    assert row, f"fixture has no {title} at {store}"
    return row[0]


def _obs(conn, pid, ts, price, in_stock, original=None):
    """One observation. `original` defaults to the price, i.e. no offer running."""
    conn.execute(
        "INSERT OR REPLACE INTO price_obs(product_id, ts, price, in_stock, price_original) "
        "VALUES (?,?,?,?,?)",
        (pid, ts, price, in_stock, price if original is None else original),
    )
    conn.commit()


def _only(conn, pid, **kw):
    rows = [r for r in changes.since_last_review(conn, since=SINCE, limit=None, **kw)
            if r["product_id"] == pid]
    assert len(rows) == 1, f"expected one row, got {rows}"
    return rows[0]


def test_restock_is_reported(db_conn):
    """The change you actually act on: it is buyable again."""
    pid = _product(db_conn)
    _obs(db_conn, pid, BEFORE, 40000, in_stock=0)
    _obs(db_conn, pid, AFTER, 40000, in_stock=1)
    assert _only(db_conn, pid)["change"] == "restock"


def test_going_out_of_stock_is_reported(db_conn):
    pid = _product(db_conn)
    _obs(db_conn, pid, BEFORE, 40000, in_stock=1)
    _obs(db_conn, pid, AFTER, 40000, in_stock=0)
    assert _only(db_conn, pid)["change"] == "agotado"


def test_a_sale_starting_is_not_merely_a_price_drop(db_conn):
    """
    Same direction as 'baja', different thing.

    The list price has not moved; an offer appeared against it, and that is
    what the row should say.
    """
    pid = _product(db_conn)
    _obs(db_conn, pid, BEFORE, 40000, in_stock=1, original=40000)
    _obs(db_conn, pid, AFTER, 30000, in_stock=1, original=40000)
    row = _only(db_conn, pid)
    assert row["change"] == "oferta"
    assert (row["old_price"], row["new_price"]) == (40000, 30000)


def test_a_sale_ending_is_reported(db_conn):
    pid = _product(db_conn)
    _obs(db_conn, pid, BEFORE, 30000, in_stock=1, original=40000)
    _obs(db_conn, pid, AFTER, 40000, in_stock=1, original=40000)
    assert _only(db_conn, pid)["change"] == "fin_oferta"


@pytest.mark.parametrize("new_price, expected", [(35000, "baja"), (45000, "alza")])
def test_plain_price_moves_are_reported_both_ways(db_conn, new_price, expected):
    """A rise matters too -- it is the signal to buy now or stop waiting."""
    pid = _product(db_conn)
    _obs(db_conn, pid, BEFORE, 40000, in_stock=1)
    _obs(db_conn, pid, AFTER, new_price, in_stock=1)
    assert _only(db_conn, pid)["change"] == expected


def test_an_unchanged_product_is_not_a_change(db_conn):
    pid = _product(db_conn)
    _obs(db_conn, pid, BEFORE, 40000, in_stock=1)
    _obs(db_conn, pid, AFTER, 40000, in_stock=1)
    assert not [r for r in changes.since_last_review(db_conn, since=SINCE, limit=None)
                if r["product_id"] == pid]


def test_restock_outranks_the_price_drop_it_came_with(db_conn):
    """One row per product, reporting the most notable thing that happened."""
    pid = _product(db_conn)
    _obs(db_conn, pid, BEFORE, 40000, in_stock=0)
    _obs(db_conn, pid, AFTER, 30000, in_stock=1, original=40000)
    row = _only(db_conn, pid)
    assert row["change"] == "restock"
    assert row["drop_pct"] == 25.0        # the drop is still on the row


def test_observations_older_than_v4_do_not_invent_changes(db_conn):
    """
    Rows written before the schema carried stock cannot say it changed.

    NULL means unknown. Reading it as "was out of stock" would report a
    restock for every product in the catalog the first time this ran.
    """
    pid = _product(db_conn)
    db_conn.execute(
        "INSERT OR REPLACE INTO price_obs(product_id, ts, price, in_stock, price_original) "
        "VALUES (?,?,?,NULL,NULL)", (pid, BEFORE, 40000))
    db_conn.commit()
    _obs(db_conn, pid, AFTER, 40000, in_stock=1)
    assert not [r for r in changes.since_last_review(db_conn, since=SINCE, limit=None)
                if r["product_id"] == pid]


def test_min_pct_spares_the_stock_and_offer_rows(db_conn):
    """A restock is worth seeing whatever the price did; a 1% wobble is not."""
    restocked = _product(db_conn, "Catan")
    _obs(db_conn, restocked, BEFORE, 40000, in_stock=0)
    _obs(db_conn, restocked, AFTER, 40000, in_stock=1)

    wobbled = _product(db_conn, "Wingspan")
    _obs(db_conn, wobbled, BEFORE, 40000, in_stock=1)
    _obs(db_conn, wobbled, AFTER, 39800, in_stock=1)

    got = {r["product_id"]: r["change"]
           for r in changes.since_last_review(db_conn, since=SINCE, min_pct=5.0, limit=None)}
    assert got.get(restocked) == "restock"
    assert wobbled not in got


def test_kinds_filter_rejects_a_name_that_is_not_one(db_conn):
    with pytest.raises(ValueError, match="Unknown change kind"):
        changes.since_last_review(db_conn, since=SINCE, kinds=["abaratado"])


def test_a_product_with_earlier_history_is_not_new(db_conn):
    """
    first_seen alone is not enough to call something new.

    After a rebuild every product carries a fresh first_seen, so keying on it
    labelled the entire catalog 'nuevo' and buried the price moves that had
    actually happened.
    """
    pid = _product(db_conn)
    _obs(db_conn, pid, BEFORE, 40000, in_stock=1)
    _obs(db_conn, pid, AFTER, 30000, in_stock=1)
    db_conn.execute("UPDATE product SET first_seen = ? WHERE id = ?", (AFTER, pid))
    db_conn.commit()
    assert _only(db_conn, pid)["change"] == "baja"


def test_a_genuinely_new_product_is_new(db_conn):
    """No observation before the cutoff, so there is nothing to compare to."""
    pid = _product(db_conn)
    db_conn.execute("DELETE FROM price_obs WHERE product_id = ?", (pid,))
    _obs(db_conn, pid, AFTER, 30000, in_stock=1)
    db_conn.execute("UPDATE product SET first_seen = ? WHERE id = ?", (AFTER, pid))
    db_conn.commit()
    row = _only(db_conn, pid)
    assert row["change"] == "nuevo"
    assert row["old_price"] is None
