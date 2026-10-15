"""
Golden fixtures: a saved page plus the records the parser got out of it.

Both test suites read these same files. pytest replays them through the Python
parser; the Kotlin/Jsoup port replays the identical bytes and must produce the
identical records. A store that changes its HTML then fails in both places at
once, and a drifting port fails immediately instead of quietly returning
nothing.

HTML is gzipped: a real catalog page is ~355 KB, and 46 stores of raw pages
would put ~16 MB of markup in the repository.

HTML to records is only half the pipeline. The other half -- title cleaning,
the match key, kind, price parsing, the ingest upsert -- is Spanish-specific
regex and SQL that is just as easy to port wrong, so it gets golden files too:

  derive_vectors.json   scraped record -> database row, for every record in the
                        page fixtures plus the edge cases below
  url_vectors.json      (base url, pagination style, page) -> url
  ingest_scenario.json  three scrapes of one store -> resulting tables and the
                        changes view

All three are generated from the Python code, so pytest re-deriving them only
catches drift nobody regenerated for. That is the point: a deliberate change
regenerates them, and the regenerated file is what turns the Kotlin side red.
"""
import gzip
import json
import tempfile
from pathlib import Path

from bs4 import BeautifulSoup

from . import changes, db as db_mod, ingest, repo, scrape, validation
from .derive import derive
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
    summary = {"store": site["name"], "url": url, "records": len(records),
               "bytes": len(html)}
    # A page that parses to nothing is a challenge interstitial, a moved
    # catalog or a broken parser. Saving it would pin "returns []" as the
    # correct answer and overwrite a fixture that still meant something.
    if not records:
        return summary

    target = (out_dir or FIXTURE_DIR) / site["name"]
    target.mkdir(parents=True, exist_ok=True)

    with gzip.open(target / f"page{page}.html.gz", "wt", encoding="utf-8") as f:
        f.write(html)
    (target / f"page{page}.expected.json").write_text(
        json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return summary


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


# ---------------------------------------------------------------------------
# Vectors
# ---------------------------------------------------------------------------

# Titles the store pages do not reliably contain: one or more per rule in
# utils.py and classify.py, mostly lifted from the cases their tests pin.
_EDGE_TITLES = (
    "Catan - Juego de Mesa", "Carcassonne | Board Game",
    "Arkham Horror: El Juego de Cartas", "Catan: Juego de Cartas",
    "Terraforming Mars Juego de Dados", "Camel Up Edición 2.0",
    "Camel Up 2.0 Juego de Cartas", "Sushi Go - Party", "Time's Up! - Party",
    "Clank Base", "Clank (juego base)",
    "Clank! Expansión: Tesoros Sumergidos - Español",
    "Catan Expansión", "Catan Expansión Navegantes", "Base Camp",
    "Baseball Highlights 2045", "Wingspan - Juego de Mesa Familiar",
    "Azul - Abstracto, estrategia",
    "Pandemic La CuraJuego de Mesa, Cooperativo, Juego de dados",
    "Clank! Juego de Mesa Aventura", "Clank!: En las Catacumbas (En Español)",
    "Terraforming Mars Edición Kickstarter", "ÁÉÍÓÚ ñ", "Ñandú",
    "El Rey De Los Dados", "Pandemic - Cooperativo, dados",
    "Catan el Juego de Mesa", "Dixit [English]", "Root 2da Edición",
    "Troyes Dice", "Flapjacks & Sasquatches Dice Game", "Monopoly Pokémon",
    "Dobble Blister", "Fábulas de Peluche",
    "Fundas Prime Mini European 46 x 71 mm", "GG: Moon D6 Dice Set 16 mm",
    "Boardgame Sleeves - European Sized", "Playmat Scythe", "Deck Box Ultra Pro",
    "Smart 10 Ampliación", "Not Alone - Sanctuary (EXPANSIÓN)",
    "Magic The Gathering Play Booster", "Pokemon Booster Display",
    "Puzzle 1000 piezas", "Booknook Tonecheer 3D Puzzle",
    "Война и мир", "東京 Tokyo", "  Espacios   dobles  ", "Catan\u00a0Plus",
)

# (original, current) pairs covering each branch of parse_price and of
# validation.check.
_EDGE_PRICES = (
    ("$69.990", None), ("$69.990,50", None), ("$49,000", None),
    ("69,990", None), ("1,234.56", None), ("9,5", None), ("9.5", None),
    ("", None), ("sin precio", None), (None, None), (None, "$12.990"),
    ("$10.000", "$10.000"), ("$10.000", "$8.000"), ("$10.000", "$20.000"),
    ("$10.000", "$500"), ("$0", None), ("$10.000", "$0"),
)

# (title, url) pairs for truncated titles, where identity comes from the slug.
_EDGE_SLUGS = (
    ("Terraforming Mars -...", "https://www.planetaloz.cl/123-terraforming-mars-preludio-2.html"),
    ("Terraforming Mars...", "https://tienda.cl/p/terraforming-mars-expedicion-ares-p77/"),
    ("Terraforming Mars…", "https://tienda.cl/p/terraforming-mars"),
    ("Catan…", None),
)

_EDGE_STOCK = ("Agotado", " agotado ", "AGOTADO", "Disponible", "", None)
_EDGE_FLAGS = ("new", "restock", "bogus", None)


def _edge_records() -> list:
    base = {"original_price": "$10.000", "current_price": None,
            "stock_status": None, "url": "https://edge.cl/p/x"}
    out = [{**base, "title": t, "url": f"https://edge.cl/p/{i}"}
           for i, t in enumerate(_EDGE_TITLES)]
    out += [{**base, "title": "Precio", "original_price": o, "current_price": c}
            for o, c in _EDGE_PRICES]
    out += [{**base, "title": t, "url": u} for t, u in _EDGE_SLUGS]
    out += [{**base, "title": "Stock", "stock_status": s} for s in _EDGE_STOCK]
    out += [{**base, "title": "Flag", "flag": f} for f in _EDGE_FLAGS]
    out += [{**base, "title": t} for t in (None, "", "   ", 123)]
    out += [{**base, "title": "URL", "url": u} for u in (
        "HTTPS://WWW.Edge.cl/P/Catan/?utm=1#top", "http://edge.cl/p/catan/",
        "edge.cl/p/catan", "", None)]
    return out


def derive_vectors(root: Path = None) -> list:
    """Every fixture record plus the edge cases, through derive and validation."""
    cases = [(p.parent.name, rec) for p in all_fixtures(root) for rec in load(p)[1]]
    cases += [("edge", rec) for rec in _edge_records()]
    out = []
    for store, record in cases:
        row = derive(store, record)
        out.append({"store": store, "record": record, "row": row,
                    "rejected": validation.check(row) if row else None})
    return out


def url_vectors() -> list:
    return [
        {"base_url": s["base_url"], "pagination": s["pagination"], "page": page,
         "url": scrape.build_url(s["base_url"], s["pagination"], page)}
        for s in scrape.sites for page in (1, 2, 3)
    ]


_SCENARIO_STORE = "tienda"


def _rec(slug, title, original, current=None, stock=None, url=None):
    return {"title": title, "original_price": original, "current_price": current,
            "stock_status": stock,
            "url": url if url is not None else f"https://tienda.cl/p/{slug}/"}


# Three scrapes of one store. Each product is there to exercise one rule:
# a duplicate URL (last wins), a title tie within one game, rejected prices,
# a synthetic identity, a truncated title, then every change kind, a delisting, and an empty scrape
# that must leave everything alone.
_SCENARIO = (
    (1_000, [
        _rec("catan", "Catan - Juego de Mesa", "$45.000",
             url="https://www.tienda.cl/p/catan/?ref=home"),
        _rec("catan", "Catan - Juego de Mesa", "$40.000"),
        _rec("wingspan", "Wingspan", "$60.000", "$50.000"),
        _rec("azul", "Azul", "$30.000", stock="Agotado"),
        _rec("carcassonne", "Carcassonne", "$25.000"),
        _rec("clank", "Clank Base", "$35.000"),
        # Same norm, same length: the first title seen names the game.
        _rec("clank-caps", "CLANK BASE", "$36.000"),
        _rec("tm", "Terraforming Mars", "$45.000"),
        _rec("tm-p2", "Terraforming Mars -...", "$20.000",
             url="https://tienda.cl/p/terraforming-mars-preludio-2"),
        _rec("gratis", "Gratis", "$0"),
        _rec("caro", "Sobreprecio", "$10.000", "$20.000"),
        _rec("sin-titulo", None, "$10.000"),
        _rec("", "Sin URL", "$5.000", url=""),
    ]),
    (2_000, [
        _rec("catan", "Catan - Juego de Mesa", "$35.000"),           # baja
        _rec("wingspan", "Wingspan", "$60.000"),                      # fin_oferta
        _rec("azul", "Azul", "$30.000"),                              # restock
        _rec("clank", "Clank Base", "$35.000", "$30.000"),            # oferta
        _rec("tm", "Terraforming Mars", "$45.000", stock="Agotado"),  # agotado
        _rec("tm-p2", "Terraforming Mars -...", "$22.000",            # alza
             url="https://tienda.cl/p/terraforming-mars-preludio-2"),
        _rec("dixit", "Dixit", "$28.000"),                            # nuevo
        _rec("", "Sin URL", "$5.000", url=""),                        # unchanged
    ]),                                                               # carcassonne delisted
    (3_000, []),
)


def _snapshot(conn) -> dict:
    """Every table the ingest writes, keyed on natural keys rather than ids."""
    def rows(sql):
        return [dict(r) for r in conn.execute(sql)]

    return {
        "products": rows(f"""
            SELECT p.url_canon, p.url, g.norm AS game_norm, p.title_raw, p.title,
                   p.norm, p.kind, p.price_original, p.price_current, p.price_eff,
                   p.in_stock, p.flag, p.first_seen, p.last_seen,
                   CASE WHEN {repo.STALE_CLAUSE} THEN 1 ELSE 0 END AS live
              FROM product p LEFT JOIN game g ON g.id = p.game_id
             ORDER BY p.url_canon"""),
        "games": rows("SELECT norm, title, kind, created_at FROM game ORDER BY norm"),
        "price_obs": rows("""
            SELECT p.url_canon, o.ts, o.price, o.in_stock, o.price_original
              FROM price_obs o JOIN product p ON p.id = o.product_id
             ORDER BY p.url_canon, o.ts"""),
        "scrape_runs": rows(
            "SELECT store, ts, n_products, success FROM scrape_run ORDER BY ts"),
    }


def ingest_scenario() -> dict:
    """Run the scenario through the real ingest on a scratch database."""
    with tempfile.TemporaryDirectory() as tmp:
        conn = db_mod.connect(Path(tmp) / "scenario.db", create=True)
        try:
            db_mod.init_db(conn)
            steps = []
            for ts, records in _SCENARIO:
                result = ingest.ingest_store(conn, _SCENARIO_STORE, records, ts=ts)
                steps.append({"ts": ts, "records": records, "result": result,
                              "state": _snapshot(conn)})
            since = _SCENARIO[1][0]
            moved = [
                {k: v for k, v in r.items() if k not in ("product_id", "game_id")}
                for r in changes.since_last_review(conn, since=since, limit=None)
            ]
        finally:
            conn.close()
    return {"store": _SCENARIO_STORE, "steps": steps,
            "changes": {"since": since, "rows": moved}}


def build_vectors(root: Path = None) -> dict:
    """{file name: content} for every vector file."""
    return {
        "derive_vectors.json": derive_vectors(root),
        "url_vectors.json": url_vectors(),
        "ingest_scenario.json": ingest_scenario(),
    }


def write_vectors(root: Path = None) -> list[Path]:
    target = root or FIXTURE_DIR
    target.mkdir(parents=True, exist_ok=True)
    written = []
    for name, content in build_vectors(root).items():
        path = target / name
        path.write_text(json.dumps(content, ensure_ascii=False, indent=1) + "\n",
                        encoding="utf-8")
        written.append(path)
    return written
