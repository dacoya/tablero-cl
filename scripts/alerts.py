"""
Price alerts, for cron.

Rewritten over the database. Two sources:

    watchlist -- every watched game with a target price (the normal case)
    ad-hoc    -- explicit queries plus one shared threshold

The watchlist path is the reason this is worth having: targets are stored, so a
scheduled run needs no arguments and cannot drift out of sync with what you
actually told the tool you wanted.

Emits the same alert shape the previous JSON-file version did, so anything
already consuming that output keeps working.
"""
import time

from . import repo, watchlist as watch_mod


def _cheapest(conn, game_id: int, in_stock_only: bool = False):
    """Cheapest usable offer for a game, or None."""
    for offer in repo.game_prices(conn, game_id):
        price = offer.get("price_eff")
        if price is None or price <= 0:
            continue
        if in_stock_only and not offer.get("in_stock"):
            continue
        return offer
    return None


def _alert(game: dict, offer: dict, threshold: float, query: str) -> dict:
    return {
        "query": query,
        "matched_title": game.get("title"),
        "game_id": game.get("game_id"),
        "store": offer["store"],
        "price": float(offer["price_eff"]),
        "threshold": float(threshold),
        "url": offer.get("url"),
        "in_stock": bool(offer.get("in_stock")),
        "timestamp": int(time.time()),
    }


def from_watchlist(conn, in_stock_only: bool = False) -> list[dict]:
    """Alerts for watched games at or below their stored target."""
    alerts = []
    for entry in watch_mod.entries(conn):
        target = entry.get("target")
        if not target:
            continue
        offer = _cheapest(conn, entry["game_id"], in_stock_only=in_stock_only)
        if offer and offer["price_eff"] <= target:
            alerts.append(_alert(
                {"title": entry["title"], "game_id": entry["game_id"]},
                offer, target, entry["title"],
            ))
    return alerts
