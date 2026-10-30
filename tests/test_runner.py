"""The CSV that scrape_site writes: deduplicated by url, ordered, stdlib-only."""
import csv

from tablero import runner


def _site(tmp_path, pages):
    """A registry entry whose parser serves `pages` in order, then nothing."""
    served = iter(pages)
    return {
        "name": "fake",
        "base_url": "https://example.test/x",
        "pagination": "page_param",
        "parser": lambda html: next(served, []),
        "output": str(tmp_path / "fake_jdm.csv"),
    }


def _row(title, url, price="$1.000"):
    return {"title": title, "original_price": price, "current_price": "",
            "stock_status": "", "url": url}


def test_dedupes_on_url_not_title(tmp_path, monkeypatch):
    """A store may list one title twice at different urls; both are real."""
    monkeypatch.setattr(runner, "fetch_html", lambda url: "<html/>")
    page = [
        _row("Drunagor", "https://example.test/drunagor-despertar"),
        _row("Drunagor", "https://example.test/drunagor-apocalipsis"),
        _row("Azul", "https://example.test/azul"),
        _row("Azul (again)", "https://example.test/azul"),   # same url: a dupe
    ]
    rows = runner.scrape_site(_site(tmp_path, [page]), dry_run=True)

    assert [r["url"] for r in rows] == [
        "https://example.test/azul",
        "https://example.test/drunagor-apocalipsis",
        "https://example.test/drunagor-despertar",
    ], "sorted by url, one row per url, distinct titles kept"


def test_written_csv_matches_returned_rows(tmp_path, monkeypatch):
    monkeypatch.setattr(runner, "fetch_html", lambda url: "<html/>")
    page = [_row("B", "https://example.test/b"), _row("A", "https://example.test/a")]
    rows = runner.scrape_site(_site(tmp_path, [page]), dry_run=True)

    written = list(csv.DictReader((tmp_path / "fake_jdm.csv").open(encoding="utf-8")))
    assert written == rows
    assert list(written[0]) == ["title", "original_price", "current_price",
                                "stock_status", "url"]


def test_empty_scrape_returns_empty_and_writes_nothing(tmp_path, monkeypatch):
    monkeypatch.setattr(runner, "fetch_html", lambda url: "<html/>")
    assert runner.scrape_site(_site(tmp_path, [[]]), dry_run=True) == []
    assert not (tmp_path / "fake_jdm.csv").exists()


def test_records_without_a_url_are_dropped_not_fatal(monkeypatch, tmp_path):
    """
    A newsletter box matching the product selector took out a whole store.

    elpatiogeek's grid holds one card with no product link, so `url` came back
    None, and sorting the deduplicated rows died with "'<' not supported
    between instances of 'NoneType' and 'str'" -- losing all 988 products.
    Product identity is (store, url_canon), so a record without one could
    never have been stored anyway.
    """
    from tablero import runner

    page = [
        {"title": "Catan", "url": "https://x.cl/p/catan",
         "original_price": "$1.000", "current_price": None, "stock_status": None},
        {"title": "Suscríbete al newsletter", "url": None,
         "original_price": None, "current_price": None, "stock_status": None},
    ]
    monkeypatch.setattr(runner, "fetch_html", lambda url: "<html/>")
    monkeypatch.setattr(runner, "resolve_output", lambda out: tmp_path / "x.csv")

    site = {"name": "x", "base_url": "https://x.cl", "pagination": "page_param",
            "output": "x.csv", "parser": lambda html: page}
    rows = runner.scrape_site(site, dry_run=True)

    assert [r["title"] for r in rows] == ["Catan"]


def test_a_page_of_only_urlless_records_is_an_empty_scrape(monkeypatch, tmp_path):
    """Empty rather than a CSV of junk -- ingest treats it as a failed store."""
    from tablero import runner

    monkeypatch.setattr(runner, "fetch_html", lambda url: "<html/>")
    monkeypatch.setattr(runner, "resolve_output", lambda out: tmp_path / "x.csv")
    site = {"name": "x", "base_url": "https://x.cl", "pagination": "page_param",
            "output": "x.csv",
            "parser": lambda html: [{"title": "Banner", "url": None}]}

    assert runner.scrape_site(site, dry_run=True) == []


def test_bs_grid_reads_the_list_price_behind_a_sale():
    """The old-price matcher could never match, losing every BS-grid discount."""
    from bs4 import BeautifulSoup
    from tablero import scrape

    html = BeautifulSoup("""
        <section class="grid__item">
          <h3 class="bs-collection__product-title"><a href="/p/catan">Catan</a></h3>
          <div class="bs-collection__product-price">
            <del class="bs-collection__old-price">$40.000</del>
            <div class="bs-collection__product-final-price">$30.000</div>
          </div>
        </section>""", "html.parser")
    [row] = scrape._bs_grid("https://x.cl")(html)
    assert (row["original_price"], row["current_price"]) == ("$40.000", "$30.000")
    assert row["stock_status"] == "Oferta"


def test_product_block_without_label_class_ignores_unrelated_text():
    """label_cls=None used to read the first class-less element as the stock label."""
    from bs4 import BeautifulSoup
    from tablero import scrape

    html = BeautifulSoup("""
        <article class="product-block">
          <span>Agotado el envío express</span>
          <a class="product-block__name" href="/p/azul">Azul</a>
          <div class="product-block__price">$30.000</div>
        </article>""", "html.parser")
    [row] = scrape.vudugaming(html)
    assert row["stock_status"] is None


def test_tentami_reads_the_list_price_behind_a_sale():
    """Only span.price was read, so a sale was stored as a full-price product."""
    from bs4 import BeautifulSoup
    from tablero import bespoke

    html = BeautifulSoup("""
        <product-card><h3 class="h4">Azul</h3><a href="/products/azul">x</a>
          <product-price><div>
            <span class="price-item__group price"><span class="visually-hidden">Precio de oferta</span>$17,990 CLP</span>
            <span class="price-item__group price-item--regular compare-at-price"><span class="visually-hidden">Precio habitual</span>$19,990 CLP</span>
          </div></product-price>
        </product-card>
        <product-card><h3 class="h4">Dixit</h3><a href="/products/dixit">x</a>
          <product-price><div><span class="price">$29,990 CLP</span></div></product-price>
        </product-card>""", "html.parser")
    sale, plain = bespoke.tentami(html)
    assert (sale["original_price"], sale["current_price"]) == ("$19,990 CLP", "$17,990 CLP")
    assert (plain["original_price"], plain["current_price"]) == ("$29,990 CLP", None)


class _Response:
    def __init__(self, body: bytes, status: int = 200):
        self.status_code, self.content, self.headers = status, body, {}
        self.text = body.decode("utf-8")

    def raise_for_status(self):
        pass


def test_fetch_html_refuses_an_oversized_page(monkeypatch, capsys):
    """The phone applies the same cap; both sides must agree on what a page is."""
    from tablero import scrape

    big = b"<html>" + b"x" * scrape._MAX_PAGE_BYTES + b"</html>"
    monkeypatch.setattr(scrape._SESSION, "get", lambda *a, **k: _Response(big))
    assert scrape.fetch_html("https://s.cl/c") is None
    assert "too large" in capsys.readouterr().out

    page = b"<html><body>" + b"<p>x</p>" * 5000 + b"</body></html>"
    monkeypatch.setattr(scrape._SESSION, "get", lambda *a, **k: _Response(page))
    assert scrape.fetch_html("https://s.cl/c") is not None


def test_every_registered_parser_runs():
    """
    Each binding called the way runner.scrape_site calls it.

    Fixtures only exercise stores that are online when captured. playkingdom's
    binding passed its base URL into the `html` slot and crashed on every call,
    and nothing noticed because the store had been down since.
    """
    from bs4 import BeautifulSoup
    from tablero import scrape

    empty = BeautifulSoup("<html><body></body></html>", "html.parser")
    broken = {}
    for site in scrape.sites:
        try:
            assert site["parser"](empty) == []
        except Exception as exc:  # noqa: BLE001 -- the point is to name every failure
            broken[site["name"]] = repr(exc)
    assert broken == {}


def test_playkingdom_parses_a_product_block():
    from bs4 import BeautifulSoup
    from tablero import scrape

    html = BeautifulSoup("""
        <article class="product-block">
          <a class="product-block__name" href="/catan">Catan</a>
          <div class="product-block__pricing"><div class="product-block__price">$40.000</div></div>
        </article>""", "html.parser")
    [row] = next(s for s in scrape.sites if s["name"] == "playkingdom")["parser"](html)
    assert (row["title"], row["original_price"], row["url"]) == ("Catan", "$40.000", "https://playkingdom.cl/catan")
