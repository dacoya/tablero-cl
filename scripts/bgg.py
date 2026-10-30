"""
BoardGameGeek ranks: import BGG's boardgames_ranks.csv and match games to it.

The CSV is BGG's free export (one row per BGG entry: name, year, rank,
ratings, votes, category ranks). It carries only the primary name, mostly
English, so Spanish store titles ("Polilla Tramposa", "Código Secreto") stay
unmatched until alternate names are added.

A match is exact on the same `norm` key games use. When several BGG entries
share a name ("Robin Hood" is twelve of them) the most voted wins only if it
has CLEAR_WINNER times the votes of the next; otherwise the game stays
unmatched. A wrong rating is worse than none.
"""
import csv
from pathlib import Path

from .paths import DATA_DIR, PKG_DIR
from .utils import clean_title, normalize

CSV_PATH = DATA_DIR / "boardgames_ranks.csv"
# Spanish titles -> BGG ids, curated for the most-listed unmatched games.
ALIASES_PATH = PKG_DIR / "bgg_names_es.csv"
CURSOR = "bgg_csv"

# Entries with fewer votes are mostly obscure homonyms: keeping them added
# ~70 matches, nearly all wrong ("Trama" 1990, 0 votes), and 90k rows.
MIN_VOTES = 5
CLEAR_WINNER = 10

# Kinds BGG catalogues. Accessories, puzzles and TCG products are not games
# there, and a sleeve pack named like a game must not borrow its rating.
MATCHED_KINDS = ("game", "expansion")

_INT_COLUMNS = (
    "yearpublished", "rank", "abstracts_rank", "cgs_rank", "childrensgames_rank",
    "familygames_rank", "partygames_rank", "strategygames_rank", "thematic_rank",
    "wargames_rank",
)
_COLUMNS = ("id", "name", "norm", *_INT_COLUMNS, "bayesaverage", "average",
            "usersrated", "is_expansion")


def _int(value: str) -> int | None:
    """CSV ints; BGG writes 0 for "no rank" and "no year", which is NULL here."""
    try:
        n = int(value)
    except (TypeError, ValueError):
        return None
    return n or None


def _float(value: str) -> float | None:
    try:
        x = float(value)
    except (TypeError, ValueError):
        return None
    return x or None


def _row(r: dict) -> tuple | None:
    """A CSV row as a bgg table row, or None when unusable or under MIN_VOTES."""
    votes = _int(r.get("usersrated")) or 0
    norm = normalize(clean_title(r.get("name") or ""))
    bgg_id = _int(r.get("id"))
    if votes < MIN_VOTES or not norm or bgg_id is None:
        return None
    return (bgg_id, r["name"].strip(), norm, *(_int(r.get(c)) for c in _INT_COLUMNS),
            _float(r.get("bayesaverage")), _float(r.get("average")), votes,
            1 if r.get("is_expansion") == "1" else 0)


def import_csv(conn, path: Path) -> int:
    """Replace the bgg table with `path`'s rows. Returns rows kept."""
    with open(path, encoding="utf-8", newline="") as fh:
        reader = csv.DictReader(fh)
        missing = {"id", "name", "usersrated"} - set(reader.fieldnames or ())
        if missing:
            raise ValueError(f"{path} is not a BGG ranks CSV: missing {sorted(missing)}")
        rows = [row for row in map(_row, reader) if row is not None]
    conn.execute("DELETE FROM bgg")
    conn.executemany(
        f"INSERT OR REPLACE INTO bgg({', '.join(_COLUMNS)}) VALUES ({', '.join('?' * len(_COLUMNS))})",
        rows,
    )
    conn.commit()
    return len(rows)


def load_aliases(conn, path: Path = ALIASES_PATH) -> int:
    """
    Replace bgg_alias with `path`'s Spanish names. An alias whose BGG entry is
    not in the bgg table (too few votes, or no CSV imported yet) is skipped.
    Returns aliases kept.
    """
    known = {i for (i,) in conn.execute("SELECT id FROM bgg")}
    with open(path, encoding="utf-8", newline="") as fh:
        pairs = {normalize(clean_title(r["name_es"])): int(r["bgg_id"]) for r in csv.DictReader(fh)}
    rows = [(norm, bid) for norm, bid in pairs.items() if norm and bid in known]
    conn.execute("DELETE FROM bgg_alias")
    conn.executemany("INSERT INTO bgg_alias(norm, bgg_id) VALUES (?, ?)", rows)
    conn.commit()
    return len(rows)


def lookup(conn, norm: str) -> int | None:
    """BGG id for a norm, or None when absent or ambiguous. Aliases win."""
    alias = conn.execute("SELECT bgg_id FROM bgg_alias WHERE norm = ?", (norm,)).fetchone()
    if alias:
        return alias[0]
    top = conn.execute(
        "SELECT id, usersrated FROM bgg WHERE norm = ? ORDER BY usersrated DESC, id LIMIT 2",
        (norm,),
    ).fetchall()
    if not top:
        return None
    if len(top) == 2 and top[0][1] < CLEAR_WINNER * top[1][1]:
        return None
    return top[0][0]


def match_games(conn) -> int:
    """
    Set game.bgg_id for every game from its own norm or, failing that, any of
    its products' norms (a cluster folds spellings BGG may know under one).
    Returns games matched.
    """
    kinds = ",".join("?" * len(MATCHED_KINDS))
    candidates: dict[int, list[str]] = {}
    for gid, norm, own in conn.execute(f"""
        SELECT g.id, p.norm, p.norm = g.norm FROM game g JOIN product p ON p.game_id = g.id
         WHERE g.kind IN ({kinds}) GROUP BY g.id, p.norm ORDER BY g.id, 3 DESC, p.norm
    """, MATCHED_KINDS):
        candidates.setdefault(gid, []).append(norm)
    matches = {}
    for gid, norms in candidates.items():
        matches[gid] = next((hit for hit in map(lambda n: lookup(conn, n), norms) if hit), None)
    conn.execute("UPDATE game SET bgg_id = NULL")
    conn.executemany("UPDATE game SET bgg_id = ? WHERE id = ?",
                     [(bid, gid) for gid, bid in matches.items() if bid is not None])
    conn.commit()
    return sum(1 for bid in matches.values() if bid is not None)


def refresh(conn, path: Path | None = None) -> dict:
    """
    Import `path` when it is newer than the last import, then re-match.

    Drop a fresh boardgames_ranks.csv into the data directory and the next
    update picks it up. Without one, matching still runs, so games created
    since the last update get their BGG entry.
    """
    path = path or CSV_PATH
    imported = None
    if path.exists():
        mtime = int(path.stat().st_mtime)
        last = conn.execute("SELECT ts FROM cursor WHERE name = ?", (CURSOR,)).fetchone()
        if last is None or last[0] < mtime:
            imported = import_csv(conn, path)
            conn.execute("INSERT OR REPLACE INTO cursor(name, ts) VALUES (?, ?)", (CURSOR, mtime))
            conn.commit()
    aliases = load_aliases(conn) if ALIASES_PATH.exists() else 0
    return {"imported": imported, "aliases": aliases, "matched": match_games(conn)}
