"""
Replay saved store pages through the current parser.

This is the Python half of the parity harness: the Kotlin/Jsoup port reads the
same files and must produce the same records. A store that changes its HTML
fails here, loudly, instead of quietly returning nothing on the next scrape.
"""
import json

import pytest

from tablero import changes, fixtures

FIXTURE_PATHS = fixtures.all_fixtures()


@pytest.mark.skipif(not FIXTURE_PATHS, reason="no fixtures captured yet")
@pytest.mark.parametrize("path", FIXTURE_PATHS, ids=lambda p: p.parent.name)
def test_parser_matches_fixture(path):
    site = fixtures.site_by_name(path.parent.name)
    assert site is not None, f"{path.parent.name} is no longer a registered site"

    html, expected = fixtures.load(path)
    assert fixtures.parse(html, site) == expected


# The vector files are generated from the Python code itself, so these only
# fail when someone changed the behaviour without regenerating. That is the
# failure that matters: a stale vector file is what the Kotlin port is tested
# against, and it would then be tested against the wrong answer.
#
# Regenerate with:  tablero fixtures --vectors-only

def _committed(name):
    return json.loads((fixtures.FIXTURE_DIR / name).read_text(encoding="utf-8"))


def _fresh(content):
    # Round-trip so tuples compare as lists, exactly as they were written.
    return json.loads(json.dumps(content, ensure_ascii=False))


def test_derive_vectors_are_current():
    committed = _committed("derive_vectors.json")
    stale = [
        (c["store"], c["record"].get("title"))
        for c, f in zip(committed, _fresh(fixtures.derive_vectors()))
        if c != f
    ]
    assert not stale[:5]
    assert len(committed) == len(fixtures.derive_vectors())


def test_url_vectors_are_current():
    assert _committed("url_vectors.json") == _fresh(fixtures.url_vectors())


def test_ingest_scenario_is_current():
    assert _committed("ingest_scenario.json") == _fresh(fixtures.ingest_scenario())


def test_sites_table_is_current():
    """The Kotlin side's registry; a stale one points the phone at old URLs."""
    committed = (fixtures.FIXTURE_DIR / "sites.tsv").read_text(encoding="utf-8")
    assert committed == fixtures.sites_table()


def test_scenario_exercises_every_change_kind():
    """The scenario is only worth porting against if it covers the whole view."""
    kinds = {r["change"] for r in fixtures.ingest_scenario()["changes"]["rows"]}
    assert kinds == set(changes.CHANGE_KINDS)


def test_scenario_empty_scrape_changes_nothing():
    steps = fixtures.ingest_scenario()["steps"]
    assert steps[-1]["result"]["skipped"]
    assert steps[-1]["state"] == steps[-2]["state"]


def test_capture_does_not_overwrite_with_an_empty_page(tmp_path, monkeypatch):
    class Response:
        text = "<html><body>Just a moment...</body></html>"

        def raise_for_status(self):
            pass

    monkeypatch.setattr(fixtures.scrape._SESSION, "get", lambda *a, **k: Response())
    got = fixtures.capture(fixtures.site_by_name("laloseta"), out_dir=tmp_path)
    assert got["records"] == 0
    assert not (tmp_path / "laloseta").exists()
