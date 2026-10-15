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
