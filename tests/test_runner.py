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
