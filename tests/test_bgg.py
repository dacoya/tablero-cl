import csv

from tablero import bgg, ingest

HEADER = ["id", "name", "yearpublished", "rank", "bayesaverage", "average", "usersrated",
          "is_expansion", "abstracts_rank", "cgs_rank", "childrensgames_rank", "familygames_rank",
          "partygames_rank", "strategygames_rank", "thematic_rank", "wargames_rank"]


def _csv(path, rows):
    with open(path, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(HEADER)
        for r in rows:
            w.writerow(r + [""] * (len(HEADER) - len(r)))
    return path


ROWS = [
    ["13", "Catan", "1995", "600", "6.9", "7.1", "140000", "0", "", "", "", "120"],
    ["266192", "Wingspan", "2019", "30", "7.9", "8.0", "110000", "0"],
    ["1", "Wingspan", "2001", "0", "", "4.0", "900", "0"],          # far fewer votes: Wingspan 2019 wins
    ["50", "Robin Hood", "2000", "0", "", "6.0", "600", "0"],
    ["51", "Robin Hood", "2010", "0", "", "6.5", "400", "0"],       # no clear winner
    ["99", "Obscure", "1990", "0", "", "", "2", "0"],               # under MIN_VOTES
]


def test_import_keeps_voted_rows_and_maps_zero_to_null(db_conn, tmp_path):
    kept = bgg.import_csv(db_conn, _csv(tmp_path / "r.csv", ROWS))
    assert kept == 5
    row = db_conn.execute("SELECT * FROM bgg WHERE id = 13").fetchone()
    assert (row["norm"], row["rank"], row["familygames_rank"], row["abstracts_rank"]) == ("catan", 600, 120, None)
    assert db_conn.execute("SELECT rank FROM bgg WHERE id = 1").fetchone()[0] is None


def test_lookup_needs_a_clear_winner(db_conn, tmp_path):
    bgg.import_csv(db_conn, _csv(tmp_path / "r.csv", ROWS))
    assert bgg.lookup(db_conn, "wingspan") == 266192
    assert bgg.lookup(db_conn, "robin hood") is None
    assert bgg.lookup(db_conn, "obscure") is None


def test_games_are_matched_and_accessories_skipped(db_conn, tmp_path):
    bgg.import_csv(db_conn, _csv(tmp_path / "r.csv", ROWS))
    assert bgg.match_games(db_conn) >= 2
    ids = dict(db_conn.execute("SELECT norm, bgg_id FROM game").fetchall())
    assert ids["catan"] == 13 and ids["wingspan"] == 266192
    assert all(v is None for k, v in ids.items() if "fundas" in k)


def test_new_game_gets_its_bgg_id_on_ingest(db_conn, tmp_path):
    bgg.import_csv(db_conn, _csv(tmp_path / "r.csv", [["777", "Cascadia", "2021", "50", "7.8", "7.9", "50000", "0"]]))
    ingest.ingest_store(db_conn, "tiendaC", [
        {"title": "Cascadia - Juego de Mesa", "original_price": "$30.000", "url": "https://c.cl/p/cascadia"},
    ], ts=1_700_300_000)
    assert db_conn.execute("SELECT bgg_id FROM game WHERE norm = 'cascadia'").fetchone()[0] == 777


def test_refresh_imports_only_a_newer_csv(db_conn, tmp_path):
    path = _csv(tmp_path / "r.csv", ROWS)
    assert bgg.refresh(db_conn, path)["imported"] == 5
    assert bgg.refresh(db_conn, path)["imported"] is None
    assert bgg.refresh(db_conn, tmp_path / "missing.csv")["imported"] is None


def test_spanish_alias_wins_over_the_csv_name(db_conn, tmp_path):
    bgg.import_csv(db_conn, _csv(tmp_path / "r.csv", [["105593", "Cheating Moth", "2011", "900", "6.5", "6.8", "9000", "0"]]))
    aliases = tmp_path / "es.csv"
    aliases.write_text("bgg_id,name_es,source,bgg_name\n105593,Polilla Tramposa,manual,Cheating Moth\n"
                       "999,Juego Fantasma,manual,Not imported\n", encoding="utf-8")
    assert bgg.load_aliases(db_conn, aliases) == 1
    assert bgg.lookup(db_conn, "polilla tramposa") == 105593


def test_shipped_aliases_point_at_real_ids():
    import csv as csv_mod
    rows = list(csv_mod.DictReader(open(bgg.ALIASES_PATH, encoding="utf-8")))
    assert rows and all(int(r["bgg_id"]) > 0 and r["name_es"].strip() for r in rows)
    assert not any(r["name_es"].rstrip().endswith(("...", "…")) for r in rows)
