"""
Command-line interface for the laptop side of the Android app.

Everything a person reads lives in the app now. What is left here feeds it:
scraping the stores the phone cannot reach, the BGG ranks, the single-file
copy the phone imports, and the parity corpus the port is tested against.
"""
import argparse
import random
import shutil
import sys
import time

from . import bgg as bgg_mod, db as db_mod, paths
from . import update as update_mod

DEFAULT_WORKERS = 5


class CommandError(RuntimeError):
    """A user-facing failure: reported as a message, not a traceback."""


def _open_db(read_only: bool = True):
    try:
        return db_mod.connect(read_only=read_only)
    except FileNotFoundError:
        raise CommandError(
            f"No hay base de datos en {db_mod.DB_PATH}.\n"
            f"  Ejecuta:  tablero update\n"
            f"  (el directorio de datos se puede cambiar con TABLERO_DATA_DIR)"
        )


def plural(n: int, singular: str, plural_form: str | None = None) -> str:
    """'1 tienda' / '2 tiendas' -- the count and its correctly-inflected noun."""
    word = singular if abs(n) == 1 else (plural_form or singular + "s")
    return f"{n} {word}"


def progress(done: int, total: int, products: int, store: str) -> None:
    """
    One line of scrape progress, rewritten in place.

    On stderr because stdout is where the data goes: `tablero update > log`
    stays a readable log.

    Only overwrites itself on a terminal. Redirected to a file, carriage
    returns would make the whole run one unreadable line, so each store gets
    its own.
    """
    line = f"  [{done}/{total}] {plural(products, 'producto')} · {store}"
    if not sys.stderr.isatty():
        print(line, file=sys.stderr)
        return
    width = max(20, shutil.get_terminal_size((100, 24)).columns - 1)
    print("\r" + line.ljust(width)[:width],
          end="\n" if done == total else "", file=sys.stderr, flush=True)


def update_report(totals: dict) -> None:
    parts = [
        f"\n{plural(totals['ingested'], 'producto')}",
        f"{totals['new']} nuevos",
        f"{totals['restock']} restock",
    ]
    if totals.get("failed"):
        parts.append(f"{plural(totals['failed'], 'tienda')} con error")
    print("Listo: " + " · ".join(parts))

    regroup = totals.get("regroup") or {}
    if regroup.get("moved"):
        print(f"Agrupación: {plural(regroup['moved'], 'producto')} movidos, "
              f"{regroup['games']} juegos en total")

    rejected = totals.get("rejected") or {}
    if rejected:
        detail = ", ".join(f"{n} {reason}" for reason, n in sorted(rejected.items()))
        print(f"Precios rechazados: {detail}")


def cmd_update(args) -> int:
    from .scrape import sites

    # The first update builds the database; nothing else has to run before it.
    conn = db_mod.connect(create=True)
    db_mod.init_db(conn)
    try:
        targets = update_mod.select_sites(
            conn, sites, args.sites, args.incremental, args.max_age)
    except update_mod.UnknownSiteError as exc:
        raise CommandError(str(exc))

    if not targets:
        print("Todo al día. Nada que actualizar.")
        return 0

    print(f"Actualizando {plural(len(targets), 'tienda')} "
          f"con {plural(args.workers, 'worker')}…")
    totals = update_mod.update_stores(
        conn, targets, workers=args.workers, dry_run=args.dry_run,
        on_failure=lambda name, exc: print(f"  [{name}] falló: {exc}"),
        on_progress=progress,
    )
    update_report(totals)
    return 0


def cmd_bgg(args) -> int:
    from pathlib import Path

    path = Path(args.path) if args.path else bgg_mod.CSV_PATH
    if not path.exists():
        raise CommandError(f"No existe {path}. Descárgalo desde boardgamegeek.com/data_dumps/bg_ranks.")
    conn = _open_db(read_only=False)
    try:
        kept = bgg_mod.import_csv(conn, path)
    except ValueError as exc:
        raise CommandError(str(exc))
    if bgg_mod.ALIASES_PATH.exists():
        bgg_mod.load_aliases(conn)
    matched = bgg_mod.match_games(conn)
    total = conn.execute(
        f"SELECT count(*) FROM game WHERE kind IN ({','.join('?' * len(bgg_mod.MATCHED_KINDS))})",
        bgg_mod.MATCHED_KINDS).fetchone()[0]
    print(f"BGG: {kept} entradas importadas · {matched} de {total} juegos asociados")
    return 0


def cmd_snapshot(args) -> int:
    target = args.path or (paths.DATA_DIR / "exports" / time.strftime("tablero-%Y%m%d-%H%M.db"))
    conn = _open_db()
    try:
        written = db_mod.snapshot(conn, target)
    except FileExistsError as exc:
        raise CommandError(str(exc))
    finally:
        conn.close()
    size = written.stat().st_size / 1_000_000
    print(f"Copia escrita en {written} ({size:.1f} MB). Impórtala desde la app en Ajustes.")
    return 0


def cmd_fixtures(args) -> int:
    """
    Refresh the parity corpus the Android port is tested against.

    Sequential and paced like a scrape: this is ~50 requests, and getting a
    store's Cloudflare to start challenging over a test corpus would cost the
    real scrapes.
    """
    from . import fixtures
    from .runner import PAGE_DELAY_JITTER, PAGE_DELAY_MIN
    from .scrape import sites

    if args.reparse:
        wanted = {n.lower() for n in args.sites or ()}
        for page in fixtures.all_fixtures():
            if not wanted or page.parent.name.lower() in wanted:
                n = fixtures.reparse(page)
                print(f"  [{page.parent.name}] {page.name}: {n} productos")
    elif not args.vectors_only:
        try:
            targets = update_mod.select_sites(None, sites, args.sites)
        except update_mod.UnknownSiteError as exc:
            raise CommandError(str(exc))

        failed = []
        for site in targets:
            for page in range(1, args.pages + 1):
                time.sleep(PAGE_DELAY_MIN + random.uniform(0, PAGE_DELAY_JITTER))
                try:
                    got = fixtures.capture(site, page)
                except Exception as exc:
                    failed.append(site["name"])
                    print(f"  [{site['name']}] p{page} falló: {exc}")
                    break
                if not got["records"]:
                    print(f"  [{site['name']}] p{page}: sin productos, "
                          f"fixture anterior conservado")
                    if page == 1:
                        failed.append(site["name"])
                    break
                print(f"  [{site['name']}] p{page}: {got['records']} productos")
        if failed:
            print(f"Sin fixture nuevo: {', '.join(failed)}")

    for path in fixtures.write_vectors():
        print(f"  escrito {path.relative_to(paths.REPO_ROOT)}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="tablero",
        description="Datos para la app tablero: scraping, BGG y la copia que importa el teléfono.",
    )
    sub = parser.add_subparsers(dest="command", metavar="COMANDO", required=True)
    p = sub.add_parser("update", help="scrapear tiendas y actualizar la base")
    p.add_argument("--sites", nargs="+", metavar="NAME", help="solo estas tiendas")
    p.add_argument("-w", "--workers", type=int, default=DEFAULT_WORKERS, metavar="N",
                   help=f"hilos concurrentes (default: {DEFAULT_WORKERS}; "
                        f"subirlo agrava los bloqueos de Cloudflare)")
    p.add_argument("--dry-run", action="store_true",
                   help="solo la primera página por tienda, para probar")
    p.add_argument("--incremental", action="store_true",
                   help="solo tiendas sin scrape reciente (ver --max-age)")
    p.add_argument("--max-age", type=float, default=24, metavar="H",
                   help="antigüedad máxima en horas para --incremental (default: 24)")

    p = sub.add_parser("bgg",
                       help="importar el CSV de rankings de BoardGameGeek y asociar juegos")
    p.add_argument("path", nargs="?", metavar="CSV",
                   help="boardgames_ranks.csv (default: data/boardgames_ranks.csv)")

    p = sub.add_parser("snapshot",
                       help="copia de la base en un solo archivo, para importar en el teléfono")
    p.add_argument("path", nargs="?", metavar="ARCHIVO",
                   help="destino (default: data/exports/tablero-AAAAMMDD-HHMM.db)")

    p = sub.add_parser("fixtures",
                       help="guardar páginas y vectores de referencia (paridad con Android)")
    p.add_argument("--sites", nargs="+", metavar="NAME",
                   help="solo estas tiendas (default: todas)")
    p.add_argument("--pages", type=int, default=1, metavar="N",
                   help="páginas por tienda (default: 1)")
    p.add_argument("--vectors-only", action="store_true",
                   help="solo regenerar los vectores, sin descargar nada")
    p.add_argument("--reparse", action="store_true",
                   help="reprocesar las páginas ya guardadas con el parser actual, sin red")

    return parser


COMMANDS = {
    "update": cmd_update,
    "bgg": cmd_bgg,
    "snapshot": cmd_snapshot,
    "fixtures": cmd_fixtures,
}


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return COMMANDS[args.command](args)
    except CommandError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    except db_mod.SchemaVersionError as exc:
        print(f"Error: la base de datos es de otra versión del esquema.\n  {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print()
        return 130


if __name__ == "__main__":
    sys.exit(main())
