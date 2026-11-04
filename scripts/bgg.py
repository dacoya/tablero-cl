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

Two fallbacks, in order, for a norm with no exact hit:

1. Without its edition words. "Wingspan Deluxe" and "On Mars KS" are their
   own products, so the words stay in `norm` and grouping keeps them apart,
   but they are the base game on BGG. Only EDITION_WORDS may go: any other
   extra word ("Carcassonne 2 Constructores", "Marvel Champions Civil War")
   names an expansion or another game, and a subset match there (token_set
   style) gave 3500 matches, mostly wrong.
2. Fuzzy, in match_games only: token_sort_ratio >= grouping.THRESHOLD with
   grouping.same_game's guards, which catches word order ("Fluxx Oz") and
   Spanish spellings of English names ("Not Alone Exploracion"). Without
   same_game's one-letter exception: "Stick Up" is not "Stack Up".
"""
import csv
import json
import os
import re
import urllib.parse
import urllib.request
from bisect import bisect_left
from pathlib import Path

from rapidfuzz import fuzz, process

from . import grouping
from .paths import DATA_DIR, PKG_DIR
from .utils import clean_title, normalize

CSV_PATH = DATA_DIR / "boardgames_ranks.csv"
# One row per BGG id: its Spanish names, for matching, and its game facts.
ALIASES_PATH = PKG_DIR / "bgg_names_es.csv"
# `source` says where name_es came from. Categories, designers, year and rank
# are for people reading the file; nothing loads them into the database.
ALIAS_COLUMNS = ("bgg_id", "bgg_name", "name_es", "variants", "min_players", "max_players", "play_time",
                 "min_age", "complexity", "mechanics", "categories", "designers", "year", "rank", "source")
# Between the values of variants, mechanics, categories and designers.
LIST_SEP = "|"
CURSOR = "bgg_csv"

# Entries with fewer votes are mostly obscure homonyms: keeping them added
# ~70 matches, nearly all wrong ("Trama" 1990, 0 votes), and 90k rows.
MIN_VOTES = 5
CLEAR_WINNER = 10

# Kinds BGG catalogues. Accessories, puzzles and TCG products are not games
# there, and a sleeve pack named like a game must not borrow its rating.
MATCHED_KINDS = ("game", "expansion")

# Norm words that name a printing of a game, not another game. Not "ultimate"
# ("Ultimate Werewolf" is not "Werewolf") or "coleccion" (often a bundle).
EDITION_WORDS = re.compile(
    r"\b(deluxe|kickstarter|ks|collectors?|coleccionista|retail|premium|limitada|limited|especial"
    r"|special|lujo|definitiva|definitive|aniversario|anniversary|all in|big box)\b"
)

_INT_COLUMNS = (
    "yearpublished", "rank", "abstracts_rank", "cgs_rank", "childrensgames_rank",
    "familygames_rank", "partygames_rank", "strategygames_rank", "thematic_rank",
    "wargames_rank",
)

WIKIDATA_URL = "https://query.wikidata.org/sparql"
WIKIDATA_USER_AGENT = "tablero-cl/0.1 (personal price tracker)"
WIKIDATA_TIMEOUT = 60
_WIKIDATA_QUERY = """SELECT ?bgg ?label WHERE {
  ?item wdt:P2339 ?bgg; rdfs:label ?label . FILTER(LANG(?label) = "es")
}"""

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


def _read_aliases(path: Path) -> list[dict]:
    with open(path, encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh))


def _write_aliases(path: Path, rows: list[dict]) -> None:
    """Rewrite the alias file sorted by id, whole or not at all."""
    tmp = path.with_suffix(".tmp")
    with open(tmp, "w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, ALIAS_COLUMNS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(sorted(rows, key=lambda r: int(r["bgg_id"])))
    os.replace(tmp, path)


def _split(value: str | None) -> list[str]:
    return [v.strip() for v in (value or "").split(LIST_SEP) if v.strip()]


def _alias_names(row: dict) -> list[str]:
    """Every name of an alias row, name_es first."""
    return _split(row.get("name_es")) + _split(row.get("variants"))


def load_aliases(conn, path: Path = ALIASES_PATH) -> int:
    """
    Replace bgg_alias with `path`'s Spanish names. An alias whose BGG entry is
    not in the bgg table (too few votes, or no CSV imported yet) is skipped, and
    so is one two entries claim, since it cannot say which game it means.
    Returns aliases kept.
    """
    known = {i for (i,) in conn.execute("SELECT id FROM bgg")}
    claims: dict[str, set[int]] = {}
    for r in _read_aliases(path):
        for name in _alias_names(r):
            claims.setdefault(normalize(clean_title(name)), set()).add(int(r["bgg_id"]))
    rows = [(norm, next(iter(ids))) for norm, ids in claims.items()
            if norm and len(ids) == 1 and next(iter(ids)) in known]
    conn.execute("DELETE FROM bgg_alias")
    conn.executemany("INSERT INTO bgg_alias(norm, bgg_id) VALUES (?, ?)", rows)
    conn.commit()
    return len(rows)


def load_facts(conn, path: Path = ALIASES_PATH) -> int:
    """
    Replace bgg_facts and bgg_mechanic with `path`'s facts. A row with no facts
    gets no bgg_facts row. Returns rows kept.
    """
    facts = []
    mechanics = []
    for r in _read_aliases(path):
        bgg_id = int(r["bgg_id"])
        values = (_int(r.get("min_players")), _int(r.get("max_players")), _int(r.get("play_time")),
                  _int(r.get("min_age")), _float(r.get("complexity")))
        if any(v is not None for v in values):
            facts.append((bgg_id, *values))
        mechanics.extend((bgg_id, m) for m in _split(r.get("mechanics")))
    with conn:
        conn.execute("DELETE FROM bgg_facts")
        conn.execute("DELETE FROM bgg_mechanic")
        conn.executemany("INSERT INTO bgg_facts VALUES (?, ?, ?, ?, ?, ?)", facts)
        conn.executemany("INSERT OR IGNORE INTO bgg_mechanic VALUES (?, ?)", mechanics)
    return len(facts)


def fetch_wikidata_names() -> list[tuple[str, str]]:
    """(BGG id, Spanish label) for every Wikidata item with a BGG id (P2339)."""
    url = f"{WIKIDATA_URL}?{urllib.parse.urlencode({'query': _WIKIDATA_QUERY, 'format': 'json'})}"
    request = urllib.request.Request(url, headers={"User-Agent": WIKIDATA_USER_AGENT})
    with urllib.request.urlopen(request, timeout=WIKIDATA_TIMEOUT) as response:
        bindings = json.load(response)["results"]["bindings"]
    return [(b["bgg"]["value"], b["label"]["value"]) for b in bindings]


def merge_wikidata_names(conn, pairs, path: Path = ALIASES_PATH) -> int:
    """
    Add Wikidata's Spanish names to the alias file. Returns names added.

    An alias beats every other match, so only clear ones get in: the norm is
    new to the file and to BGG's own names, one entry claims it, and that entry
    is in the bgg table. A name for an id already in the file becomes a variant,
    so existing names, the manual ones above all, are untouched.
    """
    existing = _read_aliases(path)
    taken = {normalize(clean_title(n)) for r in existing for n in _alias_names(r)}
    taken |= {n for (n,) in conn.execute("SELECT norm FROM bgg")}
    known = dict(conn.execute("SELECT id, name FROM bgg"))
    claims: dict[str, tuple[set[int], str]] = {}
    for raw_id, label in pairs:
        bgg_id = _int(raw_id)
        norm = normalize(clean_title(label))
        if norm and bgg_id in known and norm not in taken:
            claims.setdefault(norm, (set(), label))[0].add(bgg_id)
    added: dict[int, list[str]] = {}
    for ids, label in claims.values():
        if len(ids) == 1:
            added.setdefault(next(iter(ids)), []).append(label)
    merged = [{**r, "variants": LIST_SEP.join(_split(r.get("variants")) + added.get(int(r["bgg_id"]), []))}
              for r in existing]
    present = {int(r["bgg_id"]) for r in existing}
    merged += [{"bgg_id": i, "bgg_name": known[i], "name_es": labels[0], "variants": LIST_SEP.join(labels[1:]),
                "source": "wikidata"} for i, labels in added.items() if i not in present]
    _write_aliases(path, merged)
    return sum(map(len, added.values()))


def without_edition(norm: str) -> str:
    """The norm without EDITION_WORDS, or "" when they are all it has."""
    return " ".join(EDITION_WORDS.sub(" ", norm).split())


def lookup(conn, norm: str) -> int | None:
    """BGG id for a norm, or for it without edition words. Aliases win."""
    hit = _exact(conn, norm)
    base = without_edition(norm)
    return hit if hit or not base or base == norm else _exact(conn, base)


def _exact(conn, norm: str) -> int | None:
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


def _sorted_tokens(norm: str) -> str:
    return " ".join(sorted(norm.split()))


class _FuzzyIndex:
    """
    BGG and alias norms keyed by their sorted tokens, the keys in length order.
    Sorting tokens once here lets fuzz.ratio score what token_sort_ratio would
    without re-sorting 89k names per lookup, and a length window is a slice.
    """

    def __init__(self, conn):
        self.names: dict[str, list[str]] = {}
        for (norm,) in conn.execute("SELECT norm FROM bgg UNION SELECT norm FROM bgg_alias"):
            self.names.setdefault(_sorted_tokens(norm), []).append(norm)
        self.keys = sorted(self.names, key=len)
        self.lens = [len(k) for k in self.keys]

    def near(self, norm: str) -> list[str]:
        """Keys whose length can still score grouping.THRESHOLD against norm."""
        window = grouping._length_window(len(norm))
        return self.keys[bisect_left(self.lens, window.start):bisect_left(self.lens, window.stop)]


def fuzzy_lookup(conn, norm: str, index: _FuzzyIndex) -> int | None:
    """
    BGG id of the names grouping.same_game takes for `norm`, scoring at least
    THRESHOLD on token_sort_ratio. None when none, when the best disagree, or
    when the entry has no year: those are mostly unpublished homonyms
    ("King of the Court" is not the yearless "Court of Kings").
    """
    hits = [(score, c) for key, score, _ in process.extract(
        _sorted_tokens(norm), index.near(norm), scorer=fuzz.ratio, score_cutoff=grouping.THRESHOLD,
        limit=None, processor=None) for c in index.names[key] if c != norm and grouping.same_game(norm, c)]
    if not hits:
        return None
    best = max(score for score, _ in hits)
    ids = {_exact(conn, c) for score, c in hits if score == best}
    hit = ids.pop() if len(ids) == 1 else None
    dated = hit and conn.execute("SELECT yearpublished FROM bgg WHERE id = ?", (hit,)).fetchone()[0]
    return hit if dated else None


def match_games(conn) -> int:
    """
    Set game.bgg_id for every game from its own norm or, failing that, any of
    its products' norms (a cluster folds spellings BGG may know under one),
    exact first and fuzzy after. Returns games matched.
    """
    kinds = ",".join("?" * len(MATCHED_KINDS))
    candidates: dict[int, list[str]] = {}
    for gid, norm, own in conn.execute(f"""
        SELECT g.id, p.norm, p.norm = g.norm FROM game g JOIN product p ON p.game_id = g.id
         WHERE g.kind IN ({kinds}) GROUP BY g.id, p.norm ORDER BY g.id, 3 DESC, p.norm
    """, MATCHED_KINDS):
        candidates.setdefault(gid, []).append(norm)
    index = _FuzzyIndex(conn)

    def fuzzy(n: str) -> int | None:
        base = without_edition(n) or n
        return fuzzy_lookup(conn, n, index) or (fuzzy_lookup(conn, base, index) if base != n else None)

    matches = {}
    for gid, norms in candidates.items():
        matches[gid] = (next((hit for hit in map(lambda n: lookup(conn, n), norms) if hit), None)
                        or next((hit for hit in map(fuzzy, norms) if hit), None))
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
    aliases = 0
    if ALIASES_PATH.exists():
        aliases = load_aliases(conn, ALIASES_PATH)
        load_facts(conn, ALIASES_PATH)
    return {"imported": imported, "aliases": aliases, "matched": match_games(conn)}
