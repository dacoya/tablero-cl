-- tablero-cl canonical schema.
--
-- Design notes that matter for consumers (including the Android app):
--   * Prices are stored as REAL, already parsed. No consumer needs parse_price.
--   * `norm` (the cross-store matching key) is persisted, not recomputed. No
--     consumer needs to reimplement clean_title/normalize.
--   * Product identity is (store, url_canon) -- stable across scrapes, which is
--     what lets price_obs and the new/restock flags mean anything over time.
--   * A `game` row clusters products across stores. That cluster is the unit a
--     shopper actually cares about.

PRAGMA foreign_keys = ON;

-- ---------------------------------------------------------------------------
-- Stores
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS store (
    name      TEXT PRIMARY KEY,
    base_url  TEXT,
    city      TEXT,
    active    INTEGER NOT NULL DEFAULT 1
);

-- ---------------------------------------------------------------------------
-- Games: one row per distinct title cluster, shared across stores.
--
-- bgg_id is the matched BoardGameGeek entry, NULL when unmatched. Not a
-- foreign key: re-importing the bgg table replaces every row and re-matches.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS game (
    id         INTEGER PRIMARY KEY,
    norm       TEXT    NOT NULL UNIQUE,
    title      TEXT    NOT NULL,
    kind       TEXT    NOT NULL DEFAULT 'game',
    created_at INTEGER NOT NULL,
    bgg_id     INTEGER
);

CREATE INDEX IF NOT EXISTS idx_game_kind ON game(kind);

-- ---------------------------------------------------------------------------
-- BoardGameGeek ranks, from BGG's free boardgames_ranks.csv export. Columns
-- keep the CSV's names. Only entries with enough votes to be worth matching
-- are kept (see bgg.MIN_VOTES); `norm` is the same match key as game.norm.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bgg (
    id                  INTEGER PRIMARY KEY,
    name                TEXT    NOT NULL,
    norm                TEXT    NOT NULL,
    yearpublished       INTEGER,
    rank                INTEGER,
    bayesaverage        REAL,
    average             REAL,
    usersrated          INTEGER NOT NULL,
    is_expansion        INTEGER NOT NULL,
    abstracts_rank      INTEGER,
    cgs_rank            INTEGER,
    childrensgames_rank INTEGER,
    familygames_rank    INTEGER,
    partygames_rank     INTEGER,
    strategygames_rank  INTEGER,
    thematic_rank       INTEGER,
    wargames_rank       INTEGER
);

CREATE INDEX IF NOT EXISTS idx_bgg_norm ON bgg(norm);

-- Spanish names BGG's CSV does not carry ("Polilla Tramposa" -> Cheating
-- Moth), from scripts/bgg_names_es.csv. Checked before bgg.norm.
CREATE TABLE IF NOT EXISTS bgg_alias (
    norm   TEXT    PRIMARY KEY,
    bgg_id INTEGER NOT NULL
);

-- ---------------------------------------------------------------------------
-- Products: one row per (store, canonical URL).
--
-- price_eff is maintained by the writer rather than declared GENERATED so the
-- schema stays loadable on older SQLite builds (generated columns need 3.31+,
-- and Android ships whatever the OS ships).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS product (
    id             INTEGER PRIMARY KEY,
    store          TEXT    NOT NULL REFERENCES store(name) ON DELETE CASCADE,
    url_canon      TEXT    NOT NULL,
    url            TEXT    NOT NULL,
    game_id        INTEGER REFERENCES game(id) ON DELETE SET NULL,
    title_raw      TEXT    NOT NULL,
    title          TEXT    NOT NULL,
    norm           TEXT    NOT NULL,
    kind           TEXT    NOT NULL DEFAULT 'game',
    price_original REAL,
    price_current  REAL,
    price_eff      REAL,
    in_stock       INTEGER,
    flag           TEXT CHECK (flag IN ('new', 'restock') OR flag IS NULL),
    first_seen     INTEGER NOT NULL,
    last_seen      INTEGER NOT NULL,
    -- The store's own photo URL, never the image itself (schema v5). Last in
    -- the table so a fresh database and an upgraded one have the same columns
    -- in the same order.
    image_url      TEXT,
    UNIQUE (store, url_canon)
);

CREATE INDEX IF NOT EXISTS idx_product_game  ON product(game_id);
CREATE INDEX IF NOT EXISTS idx_product_store ON product(store);
CREATE INDEX IF NOT EXISTS idx_product_price ON product(price_eff);
CREATE INDEX IF NOT EXISTS idx_product_norm  ON product(norm);
CREATE INDEX IF NOT EXISTS idx_product_kind  ON product(kind);
CREATE INDEX IF NOT EXISTS idx_product_flag  ON product(flag) WHERE flag IS NOT NULL;
-- Each game's prices in order, with the store: the phone computes per-game
-- median and spread for the smart sorts in one pass over this index.
CREATE INDEX IF NOT EXISTS idx_product_game_price ON product(game_id, price_eff, store);

-- ---------------------------------------------------------------------------
-- Price observations, keyed on the stable product id.
--
-- The legacy history.json keyed on "norm|store", which collides whenever two
-- distinct products in one store normalize to the same string -- 77 series in
-- the old file had duplicate timestamps and meaningless trend lines.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS price_obs (
    product_id     INTEGER NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    ts             INTEGER NOT NULL,
    price          REAL    NOT NULL,   -- the effective price: offer if any, else list
    -- Recorded alongside the price so a change can be told apart from the
    -- others. Without them an observation says a product got cheaper but not
    -- whether a sale started, and says nothing at all about a product coming
    -- back into stock at an unchanged price.
    --
    -- `price = price_original` means no offer was running. NULL in either
    -- column means the observation predates schema v4 and the answer is
    -- unknown, which is not the same as "no".
    in_stock       INTEGER,
    price_original REAL,
    PRIMARY KEY (product_id, ts)
) WITHOUT ROWID;

CREATE INDEX IF NOT EXISTS idx_price_obs_ts ON price_obs(ts);

-- ---------------------------------------------------------------------------
-- Scrape bookkeeping (replaces metadata.json["sites"]).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS scrape_run (
    store      TEXT    NOT NULL,
    ts         INTEGER NOT NULL,
    n_products INTEGER,
    success    INTEGER NOT NULL,
    PRIMARY KEY (store, ts)
) WITHOUT ROWID;

-- ---------------------------------------------------------------------------
-- Watchlist / favourites. Replaces the ephemeral --watch flag.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS watchlist (
    game_id  INTEGER PRIMARY KEY REFERENCES game(id) ON DELETE CASCADE,
    target   REAL,
    note     TEXT,
    added_at INTEGER NOT NULL
);

-- ---------------------------------------------------------------------------
-- Games the user owns. Personal state, like the watchlist.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS owned (
    game_id  INTEGER PRIMARY KEY REFERENCES game(id) ON DELETE CASCADE,
    added_at INTEGER NOT NULL
);

-- ---------------------------------------------------------------------------
-- What shipping from a store costs the user, as they entered it; 0 is free
-- pickup. Stores publish nothing usable, so nothing here is scraped.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS store_shipping (
    store TEXT PRIMARY KEY,
    cost  REAL NOT NULL
);

-- ---------------------------------------------------------------------------
-- Named read cursors, so "since I last looked" is answerable.
--
-- The `flag` column on product only survives one update cycle; it means
-- "changed in the most recent scrape", not "changed since you last looked".
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cursor (
    name TEXT    PRIMARY KEY,
    ts   INTEGER NOT NULL
);

-- ---------------------------------------------------------------------------
-- Full-text search over product titles.
-- ---------------------------------------------------------------------------
CREATE VIRTUAL TABLE IF NOT EXISTS product_fts USING fts5(
    norm,
    title,
    content = 'product',
    content_rowid = 'id',
    tokenize = 'unicode61'
);
