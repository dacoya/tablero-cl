"""
Second grouping pass: fold near-identical match keys into one game.

Pass one is exact `norm` equality (see utils.normalize). This pass catches what
rules cannot: typos, plurals and word order ("king of tokio mind bug" vs "king
of tokyo mind bug", "7 wonder duel" vs "7 wonders duel").

Two norms are the same game when their token_sort_ratio is >= THRESHOLD and
the words that differ are typos of each other -- never a number, and never a
variant word on one side only ("catan plus", "europa aniversario"). An
over-merge advertises one product's price as another's, so every guard leans
toward keeping rows apart.

`regroup` rebuilds every product's norm and game from its raw title with the
current rules. It runs after each update, so a rule change reaches the whole
catalogue on the next update instead of only on newly seen titles.
"""
import re

from rapidfuzz import fuzz, process

from .classify import classify
from .derive import match_key

THRESHOLD = 95
TYPO_THRESHOLD = 80

# One-letter exception: a single letter changed, added or dropped is a typo
# even below THRESHOLD ("king tokio mind bug" vs "king tokyo mind bug" is
# 94.7), as long as the word it lands in is long enough that one letter does
# not make another word ("azul"/"azur", "hive"/"hike").
#
# Only in names of two words or more: alone, one letter is too often another
# game ("clans"/"clank", "badlands"/"radlands", "raven"/"ramen"). And never a
# swapped final o/a, which is gender in Spanish names ("bandido"/"bandida").
ONE_LETTER_MIN_WORD = 5
ONE_LETTER_MIN_WORDS = 2

# Words that name a different product when only one side has them.
VARIANT_WORDS = frozenset({
    "aniversario", "anniversary", "plus", "deluxe", "junior", "kids", "mini",
    "duelo", "duel", "cartas", "dados", "expansion", "ampliacion", "viaje",
    "legacy", "big", "box", "pack",
})


# Numbers, arabic or roman, tell sequels and volumes apart ("rivales ii" vs "iii").
_NUMBER = re.compile(r"\d+|\b(i{1,3}|iv|vi{0,3}|ix|x)\b")


def _numbers(norm: str) -> list[str]:
    return sorted(m.group(0) for m in _NUMBER.finditer(norm))


def _length_window(n: int) -> range:
    """Lengths a string of length n can have and still be the same game."""
    slack = (100 - THRESHOLD) / 100
    lo = int(n * (1 - slack) / (1 + slack))
    hi = int(n * (1 + slack) / (1 - slack)) + 1
    # A one-letter typo moves the length by at most one.
    return range(min(lo, n - 1), max(hi, n + 1) + 1)


def _one_edit(a: str, b: str) -> bool:
    """True when exactly one character was changed, inserted or deleted."""
    if abs(len(a) - len(b)) > 1 or a == b:
        return False
    if len(a) > len(b):
        a, b = b, a
    i = 0
    while i < len(a) and a[i] == b[i]:
        i += 1
    rest = i + 1 if len(a) == len(b) else i
    return a[rest:] == b[i + 1:]


def _gender_swap(a: str, b: str) -> bool:
    return a[:-1] == b[:-1] and {a[-1], b[-1]} == {"o", "a"}


def _one_letter_apart(words: int, only_a: list[str], only_b: list[str]) -> bool:
    if words < ONE_LETTER_MIN_WORDS or not len(only_a) == len(only_b) == 1:
        return False
    a, b = only_a[0], only_b[0]
    return (min(len(a), len(b)) >= ONE_LETTER_MIN_WORD
            and _one_edit(a, b) and not _gender_swap(a, b))


def _min_score(n: int) -> float:
    """Lowest token_sort_ratio a one-letter typo of an n-letter norm can score."""
    return min(THRESHOLD, 100 * (1 - 2 / max(2 * n - 1, 2)))


def same_game(a: str, b: str) -> bool:
    """True when two norms name one product. See the module docstring."""
    if _numbers(a) != _numbers(b):
        return False
    ta, tb = a.split(), b.split()
    only_a = [w for w in ta if w not in tb]
    only_b = [w for w in tb if w not in ta]
    if fuzz.token_sort_ratio(a, b) < THRESHOLD and not _one_letter_apart(len(ta), only_a, only_b):
        return False
    if len(only_a) != len(only_b):
        return False
    for word in only_a:
        partner = max(only_b, key=lambda other: fuzz.ratio(word, other))
        if fuzz.ratio(word, partner) < TYPO_THRESHOLD:
            return False
        if (word in VARIANT_WORDS) != (partner in VARIANT_WORDS):
            return False
    return True


def fold_map(norms, known=()) -> dict[str, str]:
    """
    {norm: canonical} for each norm that folds into another.

    `known` norms are canonical already. `norms` are visited in order, and the
    first one of a cluster becomes its canonical, so pass them most important
    first. Canonicals never fold into each other afterwards, which keeps a
    chain a~b~c from joining a and c.
    """
    known = set(known)
    # Bucketed by length: only near-equal lengths can score THRESHOLD, which
    # skips most of the catalogue on every lookup.
    by_len: dict[int, list[str]] = {}
    for norm in sorted(known):
        by_len.setdefault(len(norm), []).append(norm)
    remap: dict[str, str] = {}
    for norm in norms:
        if norm in known or norm in remap:
            continue
        pool = [c for n in _length_window(len(norm)) for c in by_len.get(n, ())]
        hits = process.extract(norm, pool, scorer=fuzz.token_sort_ratio,
                               score_cutoff=_min_score(len(norm)), limit=None, processor=None)
        target = next((hit[0] for hit in hits if same_game(norm, hit[0])), None)
        if target is None:
            known.add(norm)
            by_len.setdefault(len(norm), []).append(norm)
        else:
            remap[norm] = target
    return remap


def merge_similar_norms(conn, rows: list) -> list:
    """Map rows with an unseen norm onto a near-identical known game. Returns new rows."""
    known = {r[0] for r in conn.execute("SELECT norm FROM game")}
    remap = fold_map(sorted({r["norm"] for r in rows} - known), known)
    return [{**r, "norm": remap.get(r["norm"], r["norm"])} for r in rows]


def _targets(conn, members: dict[str, list]) -> dict[str, int | None]:
    """
    {canonical norm: game id to keep}. A game already holding the norm wins;
    otherwise the lowest game id among the members is renamed, so watchlist
    entries and links keep pointing at the same id. None means a new game.
    """
    by_norm = {norm: gid for gid, norm in conn.execute("SELECT id, norm FROM game")}
    targets = {norm: by_norm.get(norm) for norm in members}
    taken = {gid for gid in targets.values() if gid is not None}
    for norm, rows in members.items():
        if targets[norm] is None:
            free = sorted({r["game_id"] for r in rows if r["game_id"] is not None} - taken)
            if free:
                targets[norm] = free[0]
                taken.add(free[0])
    return targets


def _assign_games(conn, members: dict[str, list], now: int) -> dict[str, int]:
    targets = _targets(conn, members)
    # Renames go through a placeholder first: two games may swap norms.
    renamed = {norm: gid for norm, gid in targets.items() if gid is not None}
    conn.executemany("UPDATE game SET norm = '~' || id WHERE id = ?",
                     [(gid,) for gid in renamed.values()])
    conn.executemany("UPDATE game SET norm = ? WHERE id = ?", list(renamed.items()))
    for norm, gid in targets.items():
        if gid is None:
            row = min(members[norm], key=lambda r: (len(r["title"]), r["id"]))
            targets[norm] = conn.execute(
                "INSERT INTO game(norm, title, kind, created_at) VALUES (?,?,?,?)",
                (norm, row["title"], row["kind"], now),
            ).lastrowid
    return targets


def _move_watchlist(conn, moves: set[tuple[int, int]]) -> None:
    """Point watchlist and collection entries at the game their products moved to."""
    for table in ("watchlist", "owned"):
        conn.executemany(f"UPDATE OR IGNORE {table} SET game_id = ? WHERE game_id = ?",
                         [(new, old) for old, new in moves])


def regroup(conn, now: int = 0) -> dict:
    """
    Recompute every product's title, norm and game with the current rules.

    Games left without products by the move are deleted; their watchlist
    entries move with the products first. Returns counts for reporting.
    """
    products = []
    for r in conn.execute("SELECT id, game_id, title_raw, url, kind FROM product"):
        title, norm = match_key(r["title_raw"], r["url"])
        if norm:
            products.append({**dict(r), "title": title, "norm": norm, "kind": classify(title)})

    counts: dict[str, int] = {}
    for p in products:
        counts[p["norm"]] = counts.get(p["norm"], 0) + 1
    order = sorted(counts, key=lambda n: (-counts[n], n))
    remap = fold_map(order)

    members: dict[str, list] = {}
    for p in products:
        members.setdefault(remap.get(p["norm"], p["norm"]), []).append(p)

    targets = _assign_games(conn, members, now)
    moves = set()
    writes = []
    for norm, rows in members.items():
        for p in rows:
            gid = targets[norm]
            if p["game_id"] is not None and p["game_id"] != gid:
                moves.add((p["game_id"], gid))
            writes.append((gid, p["title"], norm, p["kind"], p["id"]))

    conn.executemany("UPDATE product SET game_id = ?, title = ?, norm = ?, kind = ? WHERE id = ?", writes)
    _move_watchlist(conn, moves)
    emptied = {old for old, _ in moves}
    conn.executemany(
        "DELETE FROM game WHERE id = ? AND NOT EXISTS (SELECT 1 FROM product WHERE game_id = ?)",
        [(gid, gid) for gid in emptied],
    )
    # Shortest member title names the cluster, as on insert.
    conn.execute("""
        UPDATE game SET title = (
            SELECT p.title FROM product p WHERE p.game_id = game.id
             ORDER BY length(p.title), p.id LIMIT 1)
         WHERE EXISTS (SELECT 1 FROM product p WHERE p.game_id = game.id)
    """)
    conn.commit()
    return {"folded": len(remap), "moved": len(moves),
            "games": conn.execute("SELECT count(*) FROM game").fetchone()[0]}
