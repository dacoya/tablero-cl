"""
Scrape execution: walk a site's pages and collect its products.

Extracted from the former main.py, which mixed this with the terminal modes it
no longer owns. Returns records; persistence is ingest.py's job.
"""
import random
import time

from .scrape import build_url, fetch_html

# Politeness delay between page requests. Jitter avoids a robotic cadence, which
# is part of what keeps Cloudflare from degrading the IP's reputation.
PAGE_DELAY_MIN = 1.0
PAGE_DELAY_JITTER = 1.5


def scrape_site(site, dry_run: bool = False) -> list[dict]:
    """
    Scrape every page of one registry entry.

    Silent: progress is the caller's business, reported once per finished
    store. This used to drive a tqdm bar pinned to a fixed terminal row, which
    reserved one row per registered store -- 48 of them for 5 concurrent
    workers -- and the per-page postfixes were diagnostics nothing read.

    Returns the deduplicated records.
    """
    products: list = []
    previous_titles: list = []
    page = 1

    while True:
        html = fetch_html(build_url(site["base_url"], site["pagination"], page))
        if html is None:
            break

        page_data = site["parser"](html)
        if not page_data:
            break

        # Some sites serve the last page repeatedly instead of 404-ing once
        # the page number exceeds the total, so an unchanged page means stop.
        titles = [item["title"] for item in page_data]
        if titles == previous_titles:
            break

        previous_titles = titles
        products.extend(page_data)
        page += 1

        if dry_run:
            break

        time.sleep(PAGE_DELAY_MIN + random.uniform(0, PAGE_DELAY_JITTER))

    if not products:
        return []

    # A record with no URL cannot be stored -- product identity is
    # (store, url_canon) -- and it is never a product anyway: it is the
    # newsletter box or a banner that happened to match the card selector.
    # Dropped here rather than guarded in 46 parsers, because one of them
    # reaching `sorted` with a None url took out an entire 997-product store
    # with "'<' not supported between instances of 'NoneType' and 'str'".
    products = [item for item in products if item.get("url")]
    if not products:
        return []

    # Keyed on url, not title: a store may legitimately list the same title
    # twice (base game and a preorder, or two languages) at different urls.
    unique: dict = {}
    for item in products:
        unique.setdefault(item["url"], item)
    # Sorted so a store reshuffling its pages between runs ingests in the same order.
    return sorted(unique.values(), key=lambda r: r["url"])
