import pytest

from tablero import grouping, ingest
from tablero.utils import clean_title, normalize


def key(title):
    return normalize(clean_title(title))


@pytest.mark.parametrize("titles", [
    ["El Señor de los Anillos: El Destino de la Comunidad",
     "El Señor de los Anillos: Destino de la Comunidad",
     "PREVENTA El Señor de los Anillos: El Destino de la Comunidad"],
    ["Catan", "Catan(Nueva Edicion)", "Catan Basico - español", "Catan - el juego",
     "Catan - Base (Devir)", "Catan: El juego (4ta Edicion)"],
    ["Terraforming mars: The", "terraforming mars", "terraforming marsTerraforming mars"],
    ["Wingspan", "Wingspan Juego BaseWingspan"],
    ["Scythe", "Scythe (Juego Base)Scythe"],
    ["Carcassonne", "Carcassonne (2da Edición)", "Carcassonne 2da Ed."],
    ["Clank! Catacumbas", "Clank!: En las Catacumbas (En Español)"],
    ["Catan Junior (Multilanguage)", "Catan Junior (Trilingue)"],
])
def test_same_product_shares_a_key(titles):
    assert len({key(t) for t in titles}) == 1


@pytest.mark.parametrize("titles", [
    ["Catan", "Catan Plus", "Catan Aniversario", "Catan: El Juego de Cartas",
     "Catan Expansión", "Catan Junior"],
    ["Badlands", "Badlands Deluxe"],
    ["Aventureros al Tren Europa", "Aventureros al Tren Europa 15º Aniversario"],
    ["Amigos de Mierda", "Amigos de Mierda 2"],
])
def test_variants_keep_their_own_key(titles):
    assert len({key(t) for t in titles}) == len(titles)


def test_glued_tags_are_split_and_camel_case_names_kept():
    assert clean_title("Monopoly: Demon SlayerProducto en Inglés") == "Monopoly: Demon Slayer"
    assert clean_title("Monopoly: Cartoon NetworkCartoon Network") == "Monopoly: Cartoon Network"
    assert clean_title("Happy Little DinosaursHappy Little Dinasaurs") == "Happy Little Dinosaurs"
    assert key("Heat - LeyendasExpansión") == "heat leyendas"
    for name in ("KareKare", "DoDeLiDo", "MicroMacro: Crime City", "Timeline: EcoBlister - Clásico"):
        assert clean_title(name) == name


@pytest.mark.parametrize("a, b", [
    ("king of tokio mind bug", "king of tokyo mind bug"),
    ("7 wonder duel", "7 wonders duel"),
    ("adivina quien pokemon", "pokemon adivina quien"),
    ("king tokio mind bug", "king tokyo mind bug"),
    ("monopoli pokemon", "monopoly pokemon"),
])
def test_fuzzy_folds_typos_and_word_order(a, b):
    assert grouping.same_game(a, b)


@pytest.mark.parametrize("a, b", [
    ("pandemic legacy season 1", "pandemic legacy season 2"),
    ("aventureros al tren america", "aventureros al tren asia"),
    ("aventureros al tren europa aniversario", "aventureros al tren europa anniversary plus"),
    ("catan navegantes", "catan navegantes plus"),
    ("iron clays 100", "iron clays 200"),
    ("age steam deluxe volume ii", "age steam deluxe volume iii"),
    ("clans", "clank"),
    ("badlands", "radlands"),
    ("bandido fractal", "bandida fractal"),
    ("azul verano", "azur verano"),
])
def test_fuzzy_keeps_different_products_apart(a, b):
    assert not grouping.same_game(a, b)


def test_fold_map_does_not_chain():
    remap = grouping.fold_map(["king of tokyo mind bug", "king of tokio mind bug"])
    assert remap == {"king of tokio mind bug": "king of tokyo mind bug"}


def _game_of(conn, url_canon):
    return conn.execute("SELECT game_id FROM product WHERE url_canon = ?", (url_canon,)).fetchone()[0]


def test_regroup_merges_existing_games_and_keeps_watchlist(db_conn):
    ingest.ingest_store(db_conn, "tiendaC", [
        {"title": "Catan Básico (Devir)", "original_price": "$40.000",
         "url": "https://tiendac.cl/p/catan-basico"},
    ], ts=1_700_300_000)
    # Simulate a game created under older rules, then watched.
    db_conn.execute("INSERT INTO game(norm, title, kind, created_at) VALUES ('catan basico devir', 'x', 'game', 0)")
    old = db_conn.execute("SELECT id FROM game WHERE norm = 'catan basico devir'").fetchone()[0]
    db_conn.execute("UPDATE product SET game_id = ?, norm = 'catan basico devir' WHERE store = 'tiendaC'", (old,))
    db_conn.execute("INSERT INTO watchlist(game_id, added_at) VALUES (?, 0)", (old,))
    db_conn.execute("INSERT INTO owned(game_id, added_at) VALUES (?, 0)", (old,))

    ingest.refresh_indexes(db_conn)

    catan = _game_of(db_conn, "tiendab.cl/p/catan")
    assert _game_of(db_conn, "tiendac.cl/p/catan-basico") == catan
    assert db_conn.execute("SELECT count(*) FROM game WHERE id = ?", (old,)).fetchone()[0] == 0
    assert db_conn.execute("SELECT game_id FROM watchlist").fetchall()[0][0] == catan
    assert db_conn.execute("SELECT game_id FROM owned").fetchall()[0][0] == catan


def test_regroup_is_idempotent(db_conn):
    first = grouping.regroup(db_conn)
    second = grouping.regroup(db_conn)
    assert second["moved"] == 0 and second["games"] == first["games"]
