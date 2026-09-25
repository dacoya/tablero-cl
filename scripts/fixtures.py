"""
Golden fixtures: a saved page plus the records the parser got out of it.

Both test suites read these same files. pytest replays them through the Python
parser; the Kotlin/Jsoup port replays the identical bytes and must produce the
identical records. A store that changes its HTML then fails in both places at
once, and a drifting port fails immediately instead of quietly returning
nothing.

HTML is gzipped: a real catalog page is ~355 KB, and 46 stores of raw pages
would put ~16 MB of markup in the repository.
"""
import gzip
import json
from pathlib import Path

from bs4 import BeautifulSoup

from . import scrape
from .paths import REPO_ROOT

FIXTURE_DIR = REPO_ROOT / "tests" / "fixtures"


def site_by_name(name: str) -> dict | None:
    return next((s for s in scrape.sites if s["name"] == name), None)


def parse(html: str, site: dict) -> list:
    """Run a store's parser over raw HTML. The one place both halves agree on."""
    return site["parser"](BeautifulSoup(html, "html.parser"))


def capture(site: dict, page: int = 1, out_dir: Path = None) -> dict:
    """Fetch one page and write it plus its expected parse. Returns a summary."""
    url = scrape.build_url(site["base_url"], site["pagination"], page)
    response = scrape._SESSION.get(url, timeout=20)
    response.raise_for_status()
    html = response.text

    records = parse(html, site)
    target = (out_dir or FIXTURE_DIR) / site["name"]
    target.mkdir(parents=True, exist_ok=True)

    with gzip.open(target / f"page{page}.html.gz", "wt", encoding="utf-8") as f:
        f.write(html)
    (target / f"page{page}.expected.json").write_text(
        json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return {"store": site["name"], "url": url, "records": len(records),
            "bytes": len(html)}


def load(html_path: Path) -> tuple[str, list]:
    """Read a fixture: (html, expected records)."""
    with gzip.open(html_path, "rt", encoding="utf-8") as f:
        html = f.read()
    expected = json.loads(
        html_path.with_name(html_path.name.replace(".html.gz", ".expected.json"))
        .read_text(encoding="utf-8")
    )
    return html, expected


def all_fixtures(root: Path = None) -> list[Path]:
    return sorted((root or FIXTURE_DIR).glob("*/page*.html.gz"))
