"""The TUI's price filter: a picker for the bound, then the amount."""
import pytest

from tablero import tui


@pytest.fixture
def browse_defaults():
    """Session filters are module state; keep one test from leaking into another."""
    tui.BROWSE.update(tui.BROWSE_DEFAULTS)
    yield
    tui.BROWSE.update(tui.BROWSE_DEFAULTS)


def _answers(*values):
    """Feed `values` to successive _ask calls, in order."""
    it = iter(values)
    return lambda prompt: next(it)


@pytest.mark.parametrize("raw, expected", [
    ("30000", 30000.0),
    ("30.000", 30000.0),        # Chilean thousands separator
    ("30000,5", 30000.5),       # decimal comma
    ("", None),
    ("   ", None),
    ("basura", None),           # unparseable is ignored, not fatal
])
def test_parse_price(raw, expected):
    assert tui._parse_price(raw) == expected


def test_no_filter(monkeypatch):
    monkeypatch.setattr(tui, "_ask", _answers(tui.BACK))
    assert tui._ask_price_range() == (None, None)


def test_max_only(monkeypatch):
    monkeypatch.setattr(tui, "_ask", _answers("max", "30000"))
    assert tui._ask_price_range() == (None, 30000.0)


def test_min_only(monkeypatch):
    monkeypatch.setattr(tui, "_ask", _answers("min", "10000"))
    assert tui._ask_price_range() == (10000.0, None)


def test_range(monkeypatch):
    monkeypatch.setattr(tui, "_ask", _answers("range", "10000", "30000"))
    assert tui._ask_price_range() == (10000.0, 30000.0)


def test_range_with_one_side_left_blank(monkeypatch):
    """An empty amount means that end stays unbounded."""
    monkeypatch.setattr(tui, "_ask", _answers("range", "", "30000"))
    assert tui._ask_price_range() == (None, 30000.0)


def test_cancelled_picker_keeps_whatever_is_set(monkeypatch):
    """
    Cancel is not the same answer as "no filter".

    The bounds are editable from the filters menu now, so a picker that
    reported both the same way would clear a filter every time you backed out
    of looking at it.
    """
    monkeypatch.setattr(tui, "_ask", _answers(None))
    assert tui._ask_price_range() is None


def test_no_filter_is_distinct_from_cancel(monkeypatch):
    """Choosing "Sin filtro" does clear the bounds, unlike cancelling."""
    monkeypatch.setattr(tui, "_ask", _answers(tui.BACK))
    assert tui._ask_price_range() == (None, None)


def test_browse_title_states_the_visible_range(browse_defaults):
    assert tui._browse_title("Ofertas", 134, 20, 20, "discount") == (
        "Ofertas (21–40 de 134) · orden: Mayor descuento")


def test_browse_title_states_the_active_filters(browse_defaults):
    """A short list with no stated bounds looks like missing data."""
    tui.BROWSE.update(store="flexo", in_stock=True,
                      min_price=10000.0, max_price=30000.0)
    assert tui._browse_title("Catálogo", 7, 0, 7, "price") == (
        "Catálogo (1–7 de 7) · orden: Más barato primero · flexo · "
        "solo disponibles · $10.000 – $30.000")


@pytest.mark.parametrize("raw, expected_index", [
    ("3", 2),
    (" 1 ", 0),
])
def test_pick_row_takes_a_printed_number(raw, expected_index):
    rows = [{"id": 1}, {"id": 2}, {"id": 3}]
    assert tui._pick_row(rows, raw) is rows[expected_index]


@pytest.mark.parametrize("raw", ["0", "4", "x", "", "   ", "-1", "1.5", None])
def test_pick_row_rejects_anything_that_names_no_row(raw):
    """Out of range and garbage both mean 'ask again', never an exception."""
    assert tui._pick_row([{"id": 1}, {"id": 2}, {"id": 3}], raw) is None


def test_ask_store_reprompts_after_a_typo(db_conn, monkeypatch):
    """
    A mistyped store used to abandon the whole flow.

    Every other answer already given went with it, which is why browsing felt
    punishing.
    """
    monkeypatch.setattr(tui, "_ask", _answers("tiendax", "tiendaA"))
    assert tui._ask_store(db_conn) == (True, "tiendaA")


def test_ask_store_cancel_is_the_only_exit(db_conn, monkeypatch):
    monkeypatch.setattr(tui, "_ask", _answers(None))
    assert tui._ask_store(db_conn) == (False, None)


def test_ask_store_blank_means_every_store(db_conn, monkeypatch):
    monkeypatch.setattr(tui, "_ask", _answers("  "))
    assert tui._ask_store(db_conn) == (True, None)
