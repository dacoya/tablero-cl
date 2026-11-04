"""
SQLite connection and schema management.

The database is the canonical store: prices are numeric, the cross-store match
key is persisted, and product identity is stable across scrapes. That makes the
file directly readable by an external consumer (the Android app reads this same
file via Room) with no Python-side translation.

Everything here is I/O plumbing only -- no business logic, no printing.
"""
import sqlite3
from pathlib import Path
from urllib.parse import quote

from .paths import DATA_DIR, PKG_DIR

DB_PATH = DATA_DIR / "tablero.db"
SCHEMA_PATH = PKG_DIR / "schema.sql"

# Bump when schema.sql changes in a way that needs a migration step.
SCHEMA_VERSION = 9

# Upgrades that can be applied to a populated database in place, keyed by the
# version they move it to. Only additive changes belong here -- a column with
# no default costs nothing and leaves every existing row readable. Anything
# that rewrites or drops data should stay a rebuild, which is loud.
_UPGRADES = {
    4: (
        "ALTER TABLE price_obs ADD COLUMN in_stock INTEGER",
        "ALTER TABLE price_obs ADD COLUMN price_original REAL",
    ),
    5: (
        "ALTER TABLE product ADD COLUMN image_url TEXT",
    ),
    6: (
        "ALTER TABLE game ADD COLUMN bgg_id INTEGER",
        "CREATE TABLE IF NOT EXISTS bgg ( id INTEGER PRIMARY KEY, name TEXT NOT NULL, norm TEXT NOT NULL, yearpublished INTEGER, rank INTEGER, bayesaverage REAL, average REAL, usersrated INTEGER NOT NULL, is_expansion INTEGER NOT NULL, abstracts_rank INTEGER, cgs_rank INTEGER, childrensgames_rank INTEGER, familygames_rank INTEGER, partygames_rank INTEGER, strategygames_rank INTEGER, thematic_rank INTEGER, wargames_rank INTEGER )",
        "CREATE INDEX IF NOT EXISTS idx_bgg_norm ON bgg(norm)",
    ),
    7: (
        "CREATE TABLE IF NOT EXISTS bgg_alias ( norm TEXT PRIMARY KEY, bgg_id INTEGER NOT NULL )",
    ),
    8: (
        "CREATE TABLE IF NOT EXISTS owned ( game_id INTEGER PRIMARY KEY REFERENCES game(id) ON DELETE CASCADE, added_at INTEGER NOT NULL )",
        "CREATE TABLE IF NOT EXISTS store_shipping ( store TEXT PRIMARY KEY, cost REAL NOT NULL )",
    ),
    9: (
        "CREATE TABLE IF NOT EXISTS bgg_facts ( bgg_id INTEGER PRIMARY KEY, min_players INTEGER, max_players INTEGER, play_time INTEGER, min_age INTEGER, complexity REAL )",
        "CREATE TABLE IF NOT EXISTS bgg_mechanic ( bgg_id INTEGER NOT NULL, mechanic TEXT NOT NULL, PRIMARY KEY (bgg_id, mechanic) )",
        "CREATE INDEX IF NOT EXISTS idx_bgg_mechanic ON bgg_mechanic(mechanic)",
    ),
}


class SchemaVersionError(RuntimeError):
    """Raised when a database was written by an incompatible schema version."""


def _uri_path(target: Path) -> str:
    """Percent-encode a path for use in a sqlite3 file: URI."""
    return quote(str(target))


def _apply_pragmas(conn: sqlite3.Connection, read_only: bool = False) -> None:
    conn.execute("PRAGMA foreign_keys = ON")
    if not read_only:
        # WAL lets a reader (an export job, a sync agent) work while a scrape
        # writes. Setting it on a read-only connection fails outright when the
        # database is not already in WAL -- e.g. a copy made without its -wal
        # sidecar -- so reads must not try.
        conn.execute("PRAGMA journal_mode = WAL")
        conn.execute("PRAGMA synchronous = NORMAL")


def connect(path=None, read_only: bool = False,
            create: bool = False, upgrade_schema: bool = True) -> sqlite3.Connection:
    """
    Open a connection with dict-style rows and the standard PRAGMAs applied.

    `read_only` opens via a file: URI so a consumer cannot accidentally write.

    A missing file raises FileNotFoundError unless `create` is set. sqlite3
    happily creates an empty database on a write connection, which meant
    `tablero watch add` on a fresh install produced a 0-byte file and then died
    with "no such table: game" -- so creation is now something a caller has to
    ask for: `tablero update` and the test-time `migrate`.

    An existing database from an older schema is brought up to date here, on
    open, when in-place steps exist for it. Until v5 that only happened inside
    init_db, which only `migrate` calls -- so after a schema bump the very next
    `tablero update` would have died on a missing column. `upgrade_schema=False`
    is for the one reader that must see an old file as it is: the rebuild,
    which exists precisely for schemas that cannot be upgraded in place.
    """
    target = Path(path) if path is not None else DB_PATH

    existed = target.exists()
    if not existed and not create:
        raise FileNotFoundError(f"No database at {target}")

    if read_only:
        # A file: URI takes the path as a URL, so ? and # would be parsed as
        # query and fragment separators rather than as part of the filename.
        conn = sqlite3.connect(f"file:{_uri_path(target)}?mode=ro", uri=True)
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(target)

    conn.row_factory = sqlite3.Row
    _apply_pragmas(conn, read_only=read_only)
    if existed and upgrade_schema:
        _bring_up_to_date(target, conn, read_only)
    return conn


def _bring_up_to_date(target: Path, conn: sqlite3.Connection, read_only: bool) -> None:
    """Apply pending in-place upgrades; a read-only caller borrows a writer for it."""
    existing = schema_version(conn)
    if existing in (0, SCHEMA_VERSION):
        return
    if not read_only:
        upgrade(conn, existing)
        return
    writer = sqlite3.connect(target)
    try:
        upgrade(writer, existing)
    finally:
        writer.close()


def schema_version(conn: sqlite3.Connection) -> int:
    return conn.execute("PRAGMA user_version").fetchone()[0]


def init_db(conn: sqlite3.Connection) -> None:
    """Create the schema if absent and stamp the version. Safe to call repeatedly."""
    if not SCHEMA_PATH.exists():
        raise FileNotFoundError(f"Schema file missing: {SCHEMA_PATH}")

    existing = schema_version(conn)
    if existing and existing != SCHEMA_VERSION:
        upgrade(conn, existing)

    conn.executescript(SCHEMA_PATH.read_text(encoding="utf-8"))
    conn.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
    conn.commit()


def upgrade(conn: sqlite3.Connection, existing: int) -> None:
    """
    Walk a populated database forward one version at a time.

    Only the additive steps in `_UPGRADES` are applied; anything else still
    raises, because a silent rewrite of someone's data is worse than a stop.
    The point is that a schema bump must not cost the price history: this
    database holds months of observations that a rebuild from the CSVs cannot
    reconstruct, since the CSVs only ever hold the latest scrape.
    """
    if existing > SCHEMA_VERSION:
        raise SchemaVersionError(
            f"Database at schema v{existing}, newer than the v{SCHEMA_VERSION} "
            f"this code understands. Upgrade tablero-cl."
        )

    missing = [v for v in range(existing + 1, SCHEMA_VERSION + 1) if v not in _UPGRADES]
    if missing:
        raise SchemaVersionError(
            f"Database at schema v{existing}, code expects v{SCHEMA_VERSION}, and "
            f"no in-place step exists for v{missing[0]}. Rebuild with "
            f"`tablero migrate --rebuild`."
        )

    for version in range(existing + 1, SCHEMA_VERSION + 1):
        for statement in _UPGRADES[version]:
            conn.execute(statement)
        conn.execute(f"PRAGMA user_version = {version}")
    conn.commit()


def snapshot(conn: sqlite3.Connection, target) -> Path:
    """
    Write a consistent single-file copy of the database to `target`.

    This is what the phone imports. Copying tablero.db itself is not safe: in
    WAL mode recent writes live in tablero.db-wal until a checkpoint, so a
    plain file copy can silently miss the latest scrape. VACUUM INTO reads
    through the WAL and writes one self-contained file.
    """
    target = Path(target)
    if target.exists():
        raise FileExistsError(f"{target} already exists")
    target.parent.mkdir(parents=True, exist_ok=True)
    conn.execute("VACUUM INTO ?", (str(target),))
    return target


def rebuild_fts(conn: sqlite3.Connection) -> None:
    """
    Resync the FTS index from the product table.

    product_fts is an external-content table, so it does not track writes on its
    own. Rebuilding after a bulk load is cheaper than maintaining triggers
    through a full catalog rewrite.
    """
    conn.execute("INSERT INTO product_fts(product_fts) VALUES('rebuild')")
    conn.commit()


def table_counts(conn: sqlite3.Connection) -> dict:
    """Row count per table -- used by the migration and by `tablero doctor`."""
    names = [
        r[0]
        for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'product_fts%' "
            "ORDER BY name"
        )
    ]
    # A table name cannot be a bound parameter, so it is quoted instead. The
    # names come from sqlite_master rather than any caller, but quoting keeps
    # the identifier inert regardless of what a file happens to contain.
    return {
        n: conn.execute(f'SELECT COUNT(*) FROM "{n.replace(chr(34), chr(34) * 2)}"').fetchone()[0]
        for n in names
    }
