"""
Replay saved store pages through the current parser.

This is the Python half of the parity harness: the Kotlin/Jsoup port reads the
same files and must produce the same records. A store that changes its HTML
fails here, loudly, instead of quietly returning nothing on the next scrape.
"""
import pytest

import fixtures

FIXTURE_PATHS = fixtures.all_fixtures()


@pytest.mark.skipif(not FIXTURE_PATHS, reason="no fixtures captured yet")
@pytest.mark.parametrize("path", FIXTURE_PATHS, ids=lambda p: p.parent.name)
def test_parser_matches_fixture(path):
    site = fixtures.site_by_name(path.parent.name)
    assert site is not None, f"{path.parent.name} is no longer a registered site"

    html, expected = fixtures.load(path)
    assert fixtures.parse(html, site) == expected
