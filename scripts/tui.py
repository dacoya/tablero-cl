"""
Interactive menu.

The organising rule is results first, questions second. Entering a view asks
nothing: it shows rows under whatever filters the session already carries, and
narrowing is an action on those results rather than a gate in front of them.
Browsing used to demand store, stock, price and sort before a single row
appeared, and changing one of them meant answering all four again.

Screens are navigable rather than terminal. A results page offers paging, a
drill-down into one product's prices, the filter editor and an export, and
coming back from any of them leaves you where you were.

Other properties worth not breaking:

  * The table is the listing and the picker holds verbs only. Printing the
    rows *and* offering them as selectable entries put every result on screen
    twice.
  * One consistent widget set. An older drill-down dropped to a raw input()
    loop mid-flow, which broke arrow-key navigation inside the TUI.
  * Results page through `less` (LESS=FRX), so long tables scroll and stay on
    screen after quitting.
  * The store picker filters as you type instead of listing 47 flat entries,
    and re-asks on a typo instead of abandoning the flow.
  * The worker prompt falls back to the documented default of 5; an earlier
    fallback silently used 20, which provokes Cloudflare blocks.
"""
import questionary

from . import alerts as alerts_mod
from . import analytics, basket as basket_mod
from . import changes, db as db_mod, export as exporter, history
from . import parser as parser_mod, render, repo
from . import search as search_mod, update as update_mod
from . import watchlist as watch_mod

BANNER = "\n🎲  tablero-cl — comparador de juegos de mesa\n"

# Sentinel for "go back" / "no thanks" entries.
#
# questionary.Choice falls back to the TITLE when value is None, so
# Choice("No", value=None) hands back the string "No" -- which reached the
# exporter as a format name and crashed. A distinct object cannot be confused
# with a real selection.
BACK = object()

# Actions offered alongside the results themselves. Same reasoning as BACK:
# each must be an object no real row can equal.
OPEN, NEXT, PREV, FILTERS, EXPORT = (object() for _ in range(5))
REQUERY, SET_MAX, TOGGLE_KINDS, SET_ORDER = (object() for _ in range(4))

# Rows per screen. Fixed rather than derived from the terminal height: a
# height-derived size fights `render.page`, which with LESS=FRX does not fire
# for a table this short anyway, and it would make the page numbering shift
# when a window is resized mid-session.
PAGE_SIZE = 20

# Filters persist for the length of one session, so narrowing the catalogue
# once does not have to be redone on the next visit. Deals and the catalogue
# share these -- it is the same screen, only `on_sale` differs. Search keeps
# its own, so a price cap set while browsing cannot silently filter a later
# search, which is the kind of surprise you debug at 3am.
BROWSE_DEFAULTS = {"store": None, "in_stock": False, "min_price": None,
                   "max_price": None, "sort": "discount", "urls": False}
SEARCH_DEFAULTS = {"query": "", "max_price": None,
                   "all_kinds": False, "order": "relevance"}
BROWSE = dict(BROWSE_DEFAULTS)
SEARCH = dict(SEARCH_DEFAULTS)

ORDER_LABELS = {
    "relevance": "Relevancia",
    "price": "Más barato primero",
    "stores": "En más tiendas",
}

SORT_LABELS = {
    "discount": "Mayor descuento",
    "price": "Más barato primero",
    "price_desc": "Más caro primero",
    "store": "Por tienda",
    "title": "Por nombre",
    # Derived orders: computed from cross-store comparison, not a single column.
    "value": "Mejor valor (bajo su mediana)",
    "scarcity": "Escasez (en pocas tiendas)",
    "volatility": "Volatilidad (mayor diferencia entre tiendas)",
}


def _ask(prompt):
    """Run a questionary prompt, returning None when cancelled."""
    try:
        return prompt.ask()
    except (KeyboardInterrupt, EOFError):
        return None


def _ask_store(conn):
    """
    (ok, store_name). ok=False means cancel; store=None means all stores.

    A typo re-asks instead of ending the flow. Throwing the user back to the
    top menu for mistyping one store name meant every other answer they had
    given was discarded with it.
    """
    names = repo.store_names(conn, active_only=False)
    while True:
        choice = _ask(questionary.autocomplete(
            "Tienda (vacío = todas, Tab para completar):",
            choices=names,
            ignore_case=True,
            match_middle=True,
        ))
        if choice is None:          # Esc / Ctrl-C: the only way out
            return False, None
        choice = choice.strip()
        if not choice:
            return True, None

        matches = repo.resolve_store(conn, choice)
        if len(matches) == 1:
            return True, matches[0]
        if not matches:
            print(f"  Tienda '{choice}' no encontrada.")
        else:
            print(f"  '{choice}' coincide con: {', '.join(matches)}")


def _ask_sort(default="discount"):
    return _ask(questionary.select(
        "Ordenar por:",
        choices=[questionary.Choice(label, value=key) for key, label in SORT_LABELS.items()],
        default=default,
    ))


def _parse_price(raw):
    """A Chilean-formatted amount as a float, or None when it is not one."""
    raw = raw.strip()
    if not raw:
        return None
    try:
        return float(raw.replace(".", "").replace(",", "."))
    except ValueError:
        print(f"  '{raw}' no es un número; se ignora.")
        return None


def _ask_price(label):
    raw = _ask(questionary.text(f"{label} (vacío = sin límite):"))
    return None if raw is None else _parse_price(raw)


def _ask_price_range():
    """
    (min, max), chosen from a picker so the bound being set is never in doubt.

    An earlier version took "10000-30000" in one text field. Fewer keystrokes,
    but the syntax had to be remembered and a bare number was ambiguous.

    Cancelling returns None rather than (None, None): now that the filter can
    be edited again later, "I changed my mind" has to be distinguishable from
    "no price filter", or backing out of the picker would silently clear a
    bound that was already set.
    """
    mode = _ask(questionary.select("Filtrar por precio:", choices=[
        questionary.Choice("Sin filtro", value=BACK),
        questionary.Choice("Precio máximo (hasta)", value="max"),
        questionary.Choice("Precio mínimo (desde)", value="min"),
        questionary.Choice("Rango (desde – hasta)", value="range"),
    ]))
    if mode is None:
        return None
    if mode is BACK:
        return None, None
    if mode == "max":
        return None, _ask_price("Precio máximo")
    if mode == "min":
        return _ask_price("Precio mínimo"), None
    return _ask_price("Precio mínimo"), _ask_price("Precio máximo")


def _price_label(min_price, max_price) -> str:
    if min_price is None and max_price is None:
        return "sin límite"
    if min_price is None:
        return f"hasta {render.money(max_price)}"
    if max_price is None:
        return f"desde {render.money(min_price)}"
    return f"{render.money(min_price)} – {render.money(max_price)}"


def _si_no(value) -> str:
    return "sí" if value else "no"


def _filters_menu(conn) -> None:
    """
    Edit one filter at a time, with every current value on screen.

    Browsing used to ask store, then stock, then price, then sort before
    showing a single row, so changing one bound meant answering all four
    again. Here the labels carry the state and the booleans flip in place --
    filters you can ignore entirely until you want them.
    """
    while True:
        action = _ask(questionary.select("Filtros y orden:", choices=[
            questionary.Choice(
                f"Tienda: {BROWSE['store'] or 'todas'}", value="store"),
            questionary.Choice(
                f"Solo disponibles: {_si_no(BROWSE['in_stock'])}", value="in_stock"),
            questionary.Choice(
                f"Precio: {_price_label(BROWSE['min_price'], BROWSE['max_price'])}",
                value="price"),
            questionary.Choice(
                f"Orden: {SORT_LABELS[BROWSE['sort']]}", value="sort"),
            questionary.Choice(
                f"Mostrar URL: {_si_no(BROWSE['urls'])}", value="urls"),
            questionary.Separator(),
            questionary.Choice("← Aplicar", value=BACK),
        ]))
        if action is None or action is BACK:
            return

        if action == "store":
            ok, store = _ask_store(conn)
            if ok:
                BROWSE["store"] = store
        elif action == "price":
            bounds = _ask_price_range()
            if bounds is not None:      # None = cancelled, keep what is set
                BROWSE["min_price"], BROWSE["max_price"] = bounds
        elif action == "sort":
            sort = _ask_sort(default=BROWSE["sort"])
            if sort is not None:
                BROWSE["sort"] = sort
        else:                       # in_stock / urls: booleans flip in place
            BROWSE[action] = not BROWSE[action]


def _pick_row(rows: list[dict], raw) -> dict | None:
    """The row a printed number refers to, or None when it names no row."""
    if raw is None:
        return None
    raw = raw.strip()
    if not raw.isdigit():
        return None
    n = int(raw)
    return rows[n - 1] if 1 <= n <= len(rows) else None


# ---------------------------------------------------------------------------
# Flows
# ---------------------------------------------------------------------------

def _search_flow(conn) -> None:
    """
    Search, then keep working on the results.

    The query, the price cap, the accessory toggle and the ordering are all
    changeable from inside the result list. Previously the query froze the
    moment it was typed: narrowing it meant going back to the menu and
    starting again.
    """
    query = _ask(questionary.text("Nombre del juego:", default=SEARCH["query"]))
    if not query or not query.strip():
        return
    SEARCH["query"] = query.strip()

    selected = None
    while True:
        # Re-run rather than cache behind a dirty flag: FTS over 400 candidates
        # plus fuzzy ranking is milliseconds, and no cache is no stale cache.
        hits = search_mod.search(
            conn, SEARCH["query"], limit=parser_mod.SEARCH_LIMIT,
            include_accessories=SEARCH["all_kinds"],
            max_price=SEARCH["max_price"], order=SEARCH["order"],
        )
        if not hits:
            print(f"\n  Sin resultados para '{SEARCH['query']}'"
                  f"{' con esos filtros' if SEARCH['max_price'] else ''}.")
        else:
            print(f"\n  {len(hits)} resultados para '{SEARCH['query']}':")

        # The picker IS the result list -- printing them separately showed
        # every result twice, once as text and again as a selectable row.
        width = render.hit_title_width(hits)
        choices = [questionary.Choice(render.hit_label(h, width), value=h) for h in hits]
        choices += [
            questionary.Separator(),
            questionary.Choice("✎  Cambiar búsqueda…", value=REQUERY),
            questionary.Choice(
                f"Precio máximo: {render.money(SEARCH['max_price'])}"
                if SEARCH["max_price"] else "Precio máximo: sin límite", value=SET_MAX),
            questionary.Choice(
                f"Accesorios: {_si_no(SEARCH['all_kinds'])}", value=TOGGLE_KINDS),
            questionary.Choice(
                f"Orden: {ORDER_LABELS[SEARCH['order']]}", value=SET_ORDER),
            questionary.Choice("← Menú", value=BACK),
        ]

        choice = _ask(questionary.select(
            "Ver precios de:", choices=choices, use_shortcuts=False,
            # Type to narrow the visible hits. j/k navigation has to go with
            # it: questionary refuses both at once, since j and k are letters
            # you would be typing.
            use_search_filter=True, use_jk_keys=False,
            # Land the cursor back on the game just viewed rather than at the
            # top of the list.
            default=selected if selected in hits else None,
        ))
        if choice is None or choice is BACK:
            return

        if choice is REQUERY:
            new = _ask(questionary.text("Nombre del juego:", default=SEARCH["query"]))
            if new and new.strip():
                SEARCH["query"], selected = new.strip(), None
        elif choice is SET_MAX:
            SEARCH["max_price"] = _ask_price("Precio máximo")
        elif choice is TOGGLE_KINDS:
            SEARCH["all_kinds"] = not SEARCH["all_kinds"]
        elif choice is SET_ORDER:
            order = _ask(questionary.select("Ordenar resultados por:", choices=[
                questionary.Choice(label, value=key)
                for key, label in ORDER_LABELS.items()
            ], default=SEARCH["order"]))
            if order is not None:
                SEARCH["order"] = order
        else:
            selected = choice
            render.price_table(repo.game_prices(conn, choice["game_id"]), choice["title"])


def _browse_filters() -> dict:
    """Session filters in the shape `analytics.browse` takes."""
    return {"store": BROWSE["store"], "in_stock_only": BROWSE["in_stock"],
            "min_price": BROWSE["min_price"], "max_price": BROWSE["max_price"]}


def _browse_flow(conn, on_sale: bool) -> None:
    """
    Browse the catalogue or the deals, a page at a time.

    Entering asks nothing: results come up under whatever filters the session
    already has, and narrowing is an action on the results rather than a gate
    in front of them.
    """
    label = "Ofertas" if on_sale else "Catálogo"
    offset = 0

    while True:
        rows, total = analytics.browse(
            conn, sort=BROWSE["sort"], limit=PAGE_SIZE, offset=offset,
            on_sale=on_sale, **_browse_filters())

        # The smart sorts drop games with no usable median, so `total` is an
        # upper bound for them and a page can come back empty before the count
        # says it should.
        if not rows and offset:
            print("\n  No hay más resultados.")
            offset = max(0, offset - PAGE_SIZE)
            continue

        render.product_rows(
            rows, urls=BROWSE["urls"],
            title=_browse_title(label, total, offset, len(rows), BROWSE["sort"]))

        action = _ask(questionary.select("¿Qué hacemos?", choices=[
            questionary.Choice("Abrir un producto (nº)…", value=OPEN),
            *([questionary.Choice(
                f"Página siguiente  ({offset + len(rows) + 1}–"
                f"{min(offset + 2 * PAGE_SIZE, total)} de {total})", value=NEXT)]
              if offset + len(rows) < total else []),
            *([questionary.Choice("Página anterior", value=PREV)] if offset else []),
            questionary.Choice("⚙  Filtros y orden…", value=FILTERS),
            questionary.Choice("Exportar…", value=EXPORT),
            questionary.Choice("← Menú", value=BACK),
        ]))
        if action is None or action is BACK:
            return

        if action is NEXT:
            offset += PAGE_SIZE
        elif action is PREV:
            offset = max(0, offset - PAGE_SIZE)
        elif action is FILTERS:
            _filters_menu(conn)
            offset = 0              # the old page number means nothing now
        elif action is EXPORT:
            # Deliberately re-fetched unpaged: exporting the twenty rows that
            # happen to be on screen would quietly break a working feature.
            everything, _ = analytics.browse(
                conn, sort=BROWSE["sort"], on_sale=on_sale, **_browse_filters())
            _offer_export(everything)
        else:
            row = _pick_row(rows, _ask(questionary.text("Nº:")))
            if row is None:
                print("  Ese número no está en la lista.")
                continue
            render.price_table(repo.game_prices(conn, row["game_id"]), row["title"])


def _browse_title(label, total, offset, n, sort) -> str:
    """
    Heading that says which slice is on screen and under what filters.

    A bare count reads the same on page one and page five, and a short list
    with no stated bounds looks like missing data rather than a filter.
    """
    shown = f"{offset + 1}–{offset + n} de {total}" if n else "0"
    parts = [f"{label} ({shown})", f"orden: {SORT_LABELS[sort]}"]
    if BROWSE["store"]:
        parts.append(BROWSE["store"])
    if BROWSE["in_stock"]:
        parts.append("solo disponibles")
    if BROWSE["min_price"] is not None or BROWSE["max_price"] is not None:
        parts.append(_price_label(BROWSE["min_price"], BROWSE["max_price"]))
    return " · ".join(parts)


def _offer_export(rows) -> None:
    """After showing results, offer to write them out."""
    if not rows:
        return
    fmt = _ask(questionary.select("¿Exportar?", choices=[
        questionary.Choice("No", value=BACK),
        *(questionary.Choice(f.upper(), value=f) for f in exporter.VALID_FORMATS),
    ]))
    if fmt is None or fmt is BACK:
        return
    print(f"  Exportado: {exporter.export_comparison(rows, fmt)}")


def _leaderboard_flow(conn) -> None:
    rows = analytics.store_leaderboard(conn, limit=parser_mod.LEADERBOARD_LIMIT)
    render.leaderboard(rows)
    _offer_export(rows)


def _history_flow(conn) -> None:
    query = _ask(questionary.text("Juego:"))
    if not query or not query.strip():
        return
    hit = search_mod.best_match(conn, query.strip())
    if not hit:
        print(f"\n  Sin resultados para '{query.strip()}'.")
        return
    threshold = parser_mod.DEFAULT_MIN_POINTS
    trends = history.game_trends(conn, hit["game_id"], min_points=threshold)
    if not trends:
        # Fall back rather than showing nothing: one observation is still a
        # price, it just is not yet a trend.
        trends = history.game_trends(conn, hit["game_id"], min_points=1)
        if trends:
            print("\n  (solo una observación por listado; el historial crece "
                  "con cada actualización)")
    render.trends(trends, hit["title"], min_points=threshold)


def _alerts_flow(conn) -> None:
    source = _ask(questionary.select("¿Qué vigilar?", choices=[
        questionary.Choice("Mi lista de seguimiento", value="watch"),
        questionary.Choice("Consultas puntuales", value="adhoc"),
    ]))
    if source is None:
        return

    if source == "watch":
        found = alerts_mod.from_watchlist(conn)
        render.alerts(found, "lista de seguimiento")
        return

    raw = _ask(questionary.text("Juegos separados por coma:"))
    if not raw or not raw.strip():
        return
    threshold = _ask_price("Umbral de precio")
    if threshold is None:
        print("  Se necesita un umbral.")
        return
    queries = [q.strip() for q in raw.split(",") if q.strip()]
    render.alerts(alerts_mod.from_queries(conn, queries, threshold),
                  f"{len(queries)} consulta(s)")


def _watch_flow(conn) -> None:
    action = _ask(questionary.select("Lista de seguimiento:", choices=[
        questionary.Choice("Ver la lista", value="list"),
        questionary.Choice("Agregar un juego", value="add"),
        questionary.Choice("Quitar un juego", value="rm"),
    ]))
    if action is None:
        return

    if action == "list":
        render.watchlist(watch_mod.entries(conn))
        return

    if action == "rm":
        entries = watch_mod.entries(conn)
        if not entries:
            print("\n  La lista está vacía.")
            return
        target = _ask(questionary.select("Quitar:", choices=[
            questionary.Choice(e["title"], value=e["game_id"]) for e in entries
        ]))
        if target is not None:
            watch_mod.remove(conn, target)
            print("  Quitado.")
        return

    query = _ask(questionary.text("Juego a seguir:"))
    if not query or not query.strip():
        return
    hit = search_mod.best_match(conn, query.strip())
    if not hit:
        print(f"  Sin resultados para '{query.strip()}'.")
        return
    target = _ask_price(f"Precio objetivo para {hit['title']}")
    if watch_mod.add(conn, hit["game_id"], target=target):
        print(f"  Siguiendo: {hit['title']}")
    else:
        print(f"  No se pudo seguir '{hit['title']}'.")


def _basket_flow(conn) -> None:
    use_watch = _ask(questionary.confirm(
        "¿Usar la lista de seguimiento?", default=True))
    if use_watch is None:
        return

    if use_watch:
        ids = watch_mod.game_ids(conn)
        if not ids:
            print("\n  La lista de seguimiento está vacía.")
            return
    else:
        raw = _ask(questionary.text("Juegos separados por coma:"))
        if not raw or not raw.strip():
            return
        ids = []
        for part in (p.strip() for p in raw.split(",") if p.strip()):
            hit = search_mod.best_match(conn, part)
            print(f"  {part:<24} → {hit['title'] if hit else '(sin resultados)'}")
            if hit:
                ids.append(hit["game_id"])
        if not ids:
            return

    shipping = _ask_price("Costo de envío por tienda")
    render.basket_plans(basket_mod.optimize(
        conn, ids,
        shipping=shipping if shipping is not None else basket_mod.DEFAULT_SHIPPING,
    ))


def _changes_flow(conn) -> None:
    if changes.get_cursor(conn) is None:
        print("\n  No hay marcador de última revisión.")
        if _ask(questionary.confirm("¿Fijarlo ahora?", default=True)):
            changes.set_cursor(conn)
            print("  Marcador fijado. Los cambios se listarán desde la próxima actualización.")
        return

    drops = changes.price_drops(conn, min_pct=parser_mod.DEFAULT_MIN_DROP_PCT,
                               limit=parser_mod.DEFAULT_LIMIT)
    render.product_rows(
        [{**d, "price_original": d["old_price"], "price_current": d["new_price"],
          "discount_pct": d["drop_pct"]} for d in drops],
        title=f"Bajadas desde tu última revisión ({len(drops)})",
    )
    if drops and _ask(questionary.confirm("¿Marcar como revisado?", default=False)):
        changes.set_cursor(conn)
        print("  Marcador actualizado.")


def _stores_flow(conn) -> None:
    rows = [{
        "name": s["name"],
        "products": s["n_products"],
        "stock": s["n_in_stock"] or 0,
        "active": "sí" if s["active"] else "no",
    } for s in repo.stores(conn)]
    render.table(rows, [
        ("name", "Tienda", True), ("products", "Productos", False),
        ("stock", "En stock", False), ("active", "Activa", False),
    ], title=f"Tiendas ({len(rows)})")


def _update_flow(conn) -> None:
    scope = _ask(questionary.select("¿Qué actualizar?", choices=[
        questionary.Choice("Solo tiendas obsoletas (recomendado)", value="incremental"),
        questionary.Choice("Todas las tiendas", value="all"),
        questionary.Choice("Elegir tiendas", value="some"),
    ]))
    if scope is None:
        return

    names = None
    if scope == "some":
        names = _ask(questionary.checkbox(
            "Tiendas:", choices=repo.store_names(conn, active_only=True)))
        if not names:
            return

    raw = _ask(questionary.text("Workers concurrentes:",
                                default=str(parser_mod.DEFAULT_WORKERS)))
    if raw is None:
        return
    try:
        workers = max(1, int(raw))
    except ValueError:
        # The documented default. The old code fell back to 20 here, four times
        # this, which reliably provokes Cloudflare blocks.
        workers = parser_mod.DEFAULT_WORKERS
        print(f"  Valor inválido; usando {workers}.")

    dry_run = _ask(questionary.confirm("¿Dry run (solo 1 página)?", default=False))
    if dry_run is None:
        return
    if not _ask(questionary.confirm(
            "Esto hará scraping en vivo. ¿Continuar?", default=True)):
        return

    from .scrape import sites

    targets = update_mod.select_sites(
        conn, sites, names, scope == "incremental", 24)
    if not targets:
        print("  Todo al día. Nada que actualizar.")
        return

    print(f"  Actualizando {render.plural(len(targets), 'tienda')}…")
    render.update_report(update_mod.update_stores(
        conn, targets, workers=workers, dry_run=dry_run,
        on_failure=lambda name, exc: print(f"  [{name}] falló: {exc}"),
    ))

_ACTIONS = {
    "search": _search_flow,
    "deals": lambda conn: _browse_flow(conn, on_sale=True),
    "list": lambda conn: _browse_flow(conn, on_sale=False),
    "changes": _changes_flow,
    "watch": _watch_flow,
    "basket": _basket_flow,
    "stores": _stores_flow,
    "leaderboard": _leaderboard_flow,
    "history": _history_flow,
    "alerts": _alerts_flow,
    "update": _update_flow,
}


def _menu_choices() -> list:
    """
    The top menu, grouped by what you are trying to do.

    Everyday browsing first, maintenance last. The separators are skipped by
    the cursor, so grouping costs three lines and no extra keystrokes.

    Built fresh on every redraw rather than held as a module constant:
    questionary assigns `shortcut_key` onto the Choice objects it is given,
    so one shared list would be mutated by each prompt and handed to the next.
    """
    return [
        questionary.Separator("──  Explorar  ──"),
        questionary.Choice("🔍  Buscar un juego", value="search"),
        questionary.Choice("🏷   Ver ofertas", value="deals"),
        questionary.Choice("📋  Listar catálogo", value="list"),
        questionary.Choice("📉  Cambios desde mi última revisión", value="changes"),
        questionary.Separator("──  Mis juegos  ──"),
        questionary.Choice("⭐  Lista de seguimiento", value="watch"),
        questionary.Choice("🔔  Avisos de precio", value="alerts"),
        questionary.Choice("🛒  Dónde comprar (carrito)", value="basket"),
        questionary.Choice("📈  Historial de precios", value="history"),
        questionary.Separator("──  Tiendas y datos  ──"),
        questionary.Choice("🏪  Tiendas", value="stores"),
        questionary.Choice("🏆  Ranking de tiendas", value="leaderboard"),
        questionary.Choice("🔄  Actualizar precios", value="update"),
        questionary.Separator(),
        questionary.Choice("✖   Salir", value="quit"),
    ]


def run_tui() -> None:
    print(BANNER)
    try:
        conn = db_mod.connect()
    except FileNotFoundError:
        print("No hay base de datos. Ejecuta:  tablero migrate")
        print("Si tus datos están en otra carpeta, apunta TABLERO_DATA_DIR ahí.")
        return

    # Reset so a second run_tui() in the same process -- a test, mostly --
    # does not inherit the first one's filters.
    BROWSE.update(BROWSE_DEFAULTS)
    SEARCH.update(SEARCH_DEFAULTS)

    try:
        while True:
            action = _ask(questionary.select(
                "¿Qué quieres hacer?", choices=_menu_choices()))
            if action in (None, "quit"):
                break
            try:
                _ACTIONS[action](conn)
            except KeyboardInterrupt:
                # Ctrl-C outside a prompt -- during a long query, say. It is
                # not an Exception subclass, so without this it ended the
                # session instead of the operation.
                print("\n  Cancelado.")
            except Exception as exc:
                # One failing flow must not end the session: report it and
                # return to the menu.
                print(f"\n  Error en '{action}': {exc}")
    finally:
        conn.close()
        print("\n¡Hasta luego! 🎲")


if __name__ == "__main__":
    run_tui()
