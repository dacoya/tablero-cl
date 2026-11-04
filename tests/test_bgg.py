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
    _aliases(aliases, {"bgg_id": 105593, "name_es": "Polilla Tramposa", "source": "manual", "bgg_name": "Cheating Moth"},
             {"bgg_id": 999, "name_es": "Juego Fantasma", "source": "manual", "bgg_name": "Not imported"})
    assert bgg.load_aliases(db_conn, aliases) == 1
    assert bgg.lookup(db_conn, "polilla tramposa") == 105593


def test_shipped_aliases_point_at_real_ids():
    import csv as csv_mod
    rows = list(csv_mod.DictReader(open(bgg.ALIASES_PATH, encoding="utf-8")))
    assert rows and all(int(r["bgg_id"]) > 0 and r["name_es"].strip() for r in rows)
    assert list(rows[0]) == list(bgg.ALIAS_COLUMNS)
    names = [n for r in rows for n in bgg._alias_names(r)]
    assert not any(n.endswith(("...", "…")) for n in names)


def _aliases(path, *rows):
    """Write an alias file; each row is a dict of the columns it fills."""
    with open(path, "w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, bgg.ALIAS_COLUMNS, lineterminator="\n")
        w.writeheader()
        w.writerows(rows or [{"bgg_id": 13, "name_es": "Catán", "source": "manual", "bgg_name": "Catan"}])
    return path


def test_aliases_read_variants_and_skip_blanks(db_conn, tmp_path):
    bgg.import_csv(db_conn, _csv(tmp_path / "r.csv", ROWS))
    path = _aliases(tmp_path / "a.csv", {"bgg_id": 13, "name_es": "Catán", "variants": "Los Colonos de Catán| |Colonos"},
                    {"bgg_id": 266192, "name_es": ""})
    assert bgg.load_aliases(db_conn, path) == 3
    assert bgg.lookup(db_conn, "colonos catan") == 13
    assert bgg.lookup(db_conn, "colonos") == 13


def test_alias_claimed_by_two_entries_is_dropped(db_conn, tmp_path):
    bgg.import_csv(db_conn, _csv(tmp_path / "r.csv", ROWS))
    path = _aliases(tmp_path / "a.csv", {"bgg_id": 13, "name_es": "Catán", "variants": "Alas"},
                    {"bgg_id": 266192, "name_es": "Alas"})
    assert bgg.load_aliases(db_conn, path) == 1
    assert bgg.lookup(db_conn, "alas") is None


def test_load_facts_maps_values_and_keeps_mechanics_whole(db_conn, tmp_path):
    path = _aliases(
        tmp_path / "a.csv",
        {"bgg_id": 13, "name_es": "Catán", "min_players": 3, "max_players": 4, "play_time": 120, "min_age": 10,
         "complexity": 2.32, "mechanics": "Dice Rolling|I Cut, You Choose|Trading", "categories": "Negotiation"},
        {"bgg_id": 14, "name_es": "Parcial", "min_players": 2, "complexity": 0},
        {"bgg_id": 15, "name_es": "Sin datos", "year": 2000, "rank": 7},
    )
    assert bgg.load_facts(db_conn, path) == 2
    assert tuple(db_conn.execute("SELECT * FROM bgg_facts WHERE bgg_id = 13").fetchone()) == (13, 3, 4, 120, 10, 2.32)
    assert tuple(db_conn.execute("SELECT * FROM bgg_facts WHERE bgg_id = 14").fetchone()) == (14, 2, None, None, None, None)
    assert db_conn.execute("SELECT count(*) FROM bgg_facts WHERE bgg_id = 15").fetchone()[0] == 0
    assert [m for (m,) in db_conn.execute("SELECT mechanic FROM bgg_mechanic ORDER BY mechanic")] == [
        "Dice Rolling", "I Cut, You Choose", "Trading"]


def test_load_facts_replaces_earlier_rows(db_conn, tmp_path):
    bgg.load_facts(db_conn, _aliases(tmp_path / "a.csv", {"bgg_id": 13, "min_players": 3, "mechanics": "Trading"}))
    bgg.load_facts(db_conn, _aliases(tmp_path / "b.csv", {"bgg_id": 20, "min_players": 1, "mechanics": "Trading"}))
    assert [i for (i,) in db_conn.execute("SELECT bgg_id FROM bgg_facts")] == [20]
    assert [i for (i,) in db_conn.execute("SELECT bgg_id FROM bgg_mechanic")] == [20]


def test_refresh_loads_facts_from_the_alias_file(db_conn, tmp_path, monkeypatch):
    monkeypatch.setattr(bgg, "ALIASES_PATH", _aliases(tmp_path / "a.csv", {"bgg_id": 13, "name_es": "Catán", "min_players": 3}))
    bgg.refresh(db_conn, tmp_path / "missing.csv")
    assert db_conn.execute("SELECT min_players FROM bgg_facts WHERE bgg_id = 13").fetchone()[0] == 3


def test_merge_wikidata_adds_only_safe_names(db_conn, tmp_path):
    bgg.import_csv(db_conn, _csv(tmp_path / "r.csv", ROWS))
    path = _aliases(tmp_path / "a.csv", {"bgg_id": 13, "name_es": "Catán", "source": "manual", "bgg_name": "Catan",
                                         "min_players": 3})
    added = bgg.merge_wikidata_names(db_conn, [
        ("266192", "Alas"),          # new: added
        ("266192", "alas"),          # same norm, same id: once
        ("266192", "Aves"),          # second name for a new row: a variant
        ("13", "Los Colonos"),       # existing row: a variant, the manual name stays
        ("50", "Catán"),             # a manual row owns this norm
        ("50", "Robin"), ("51", "Robin"),  # same name, two entries: ambiguous
        ("99", "Oscuro"),            # not in the bgg table
        ("Q5", "Raro"),              # not a BGG id
    ], path)
    assert added == 3
    with open(path, encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))
    assert [(r["bgg_id"], r["name_es"], r["variants"], r["source"], r["bgg_name"], r["min_players"]) for r in rows] == [
        ("13", "Catán", "Los Colonos", "manual", "Catan", "3"),
        ("266192", "Alas", "Aves", "wikidata", "Wingspan", "")]


def test_merge_wikidata_skips_a_name_already_a_variant(db_conn, tmp_path):
    bgg.import_csv(db_conn, _csv(tmp_path / "r.csv", ROWS))
    path = _aliases(tmp_path / "a.csv", {"bgg_id": 13, "name_es": "Catán", "variants": "Los Colonos"})
    assert bgg.merge_wikidata_names(db_conn, [("266192", "los colonos")], path) == 0


EDITIONS = [
    ["266192", "Wingspan", "2019", "30", "7.9", "8.0", "110000", "0"],
    ["822", "Carcassonne", "2000", "200", "7.3", "7.4", "100000", "0"],
    ["184267", "On Mars", "2020", "200", "7.9", "8.2", "9000", "0"],
    ["8203", "Not Alone: Exploration", "2017", "0", "", "7.6", "1500", "1"],
    ["12", "Oz Fluxx", "2012", "0", "", "6.0", "900", "0"],
    ["5157", "Stack Up", "2015", "0", "", "6.0", "500", "0"],
    ["200725", "Court of Kings", "0", "0", "", "6.0", "6", "0"],     # no year
]


def test_edition_words_reach_the_base_entry(db_conn, tmp_path):
    bgg.import_csv(db_conn, _csv(tmp_path / "r.csv", EDITIONS))
    assert bgg.lookup(db_conn, "wingspan deluxe") == 266192
    assert bgg.lookup(db_conn, "on mars ks") == 184267
    assert bgg.lookup(db_conn, "carcassonne aniversario") == 822
    assert bgg.lookup(db_conn, "deluxe") is None
    # Any other extra word is an expansion or another game, not the base.
    assert bgg.lookup(db_conn, "carcassonne 2 constructores comerciantes") is None
    assert bgg.lookup(db_conn, "wingspan europea") is None


def test_fuzzy_takes_word_order_and_spelling_but_not_other_games(db_conn, tmp_path):
    bgg.import_csv(db_conn, _csv(tmp_path / "r.csv", EDITIONS))
    index = bgg._FuzzyIndex(db_conn)
    assert bgg.fuzzy_lookup(db_conn, "fluxx oz", index) == 12
    assert bgg.fuzzy_lookup(db_conn, "not alone exploracion", index) == 8203
    assert bgg.fuzzy_lookup(db_conn, "stick up", index) is None       # one letter, another game
    assert bgg.fuzzy_lookup(db_conn, "king court", index) is None     # only a yearless entry
    assert bgg.fuzzy_lookup(db_conn, "fluxx oz deluxe", index) is None  # deluxe is a variant word to same_game


def test_match_games_falls_back_to_fuzzy_after_edition_words(db_conn, tmp_path):
    bgg.import_csv(db_conn, _csv(tmp_path / "r.csv", EDITIONS))
    ingest.ingest_store(db_conn, "tiendaF", [
        {"title": "Fluxx Oz Deluxe", "original_price": "$9.990", "url": "https://f.cl/p/oz"},
    ], ts=1_700_300_000)
    assert db_conn.execute("SELECT bgg_id FROM game WHERE norm = 'fluxx oz deluxe'").fetchone()[0] is None
    bgg.match_games(db_conn)
    assert db_conn.execute("SELECT bgg_id FROM game WHERE norm = 'fluxx oz deluxe'").fetchone()[0] == 12
