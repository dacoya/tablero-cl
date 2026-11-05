"""
scrape.py
---------
The HTTP session and fetch_html, which parser reads which store, and the
site registry. The parsers themselves live in parsers.py (shared helpers and
generics) and bespoke.py (store-specific); runner.py drives the page walk.
"""

from functools import partial
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
from bs4 import BeautifulSoup
from .parsers import (
    _oos,
    _parse_bs,
    _parse_dawn,
    _parse_presta,
    _parse_product_block,
    _parse_product_block_simple,
    _parse_shopify_card,
    _parse_woo_li,
    _txt,
)
from .bespoke import (
    araucania,
    buhojuegosdemesa,
    cafe2d6,
    calabozotienda,
    cartonespesados,
    darkhobbies,
    devir,
    drjuegos,
    elpatiogeek,
    enroque,
    geekz,
    guildreams,
    jugones,
    kaiojuegos,
    lafortalezapuq,
    lautarojuegos,
    fillerplace,
    ludoteka,
    ludopia,
    ludipuerto,
    magicsur,
    manahouse,
    piedrabruja,
    ruta12club,
    shivano,
    tentami,
    thirdimpact,
    updown_juegos,
)


# ── HTTP ───────────────────────────────────────────────────────────────────────
# Module-level session: one adapter, one connection pool, reused across all calls.

def _make_session():
    s = requests.Session()
    adapter = HTTPAdapter(max_retries=Retry(
        total=3,
        backoff_factor=1,
        status_forcelist=[429, 500, 502, 503, 504],
        raise_on_status=False,
    ))
    s.mount("http://",  adapter)
    s.mount("https://", adapter)
    # Fuller, realistic browser headers — standard scraping hygiene, reduces trivial
    # user-agent filtering. (Not a bot-detection bypass; a WAF/JS challenge still wins.)
    s.headers.update({
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        ),
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language": "es-CL,es;q=0.9,en;q=0.8",
    })
    return s

_SESSION = _make_session()

# Substrings that mark a full-page anti-bot / WAF challenge INTERSTITIAL (not just a
# widget script embedded in a normal page). Kept specific to Cloudflare/Sucuri/DataDome
# block pages; generic tokens like "g-recaptcha" are avoided because real store pages
# embed them in forms. Detection is further gated on page size below.
_CHALLENGE_MARKERS = (
    "just a moment",          # Cloudflare interstitial
    "checking your browser",  # Cloudflare
    "cf-chl-",                # Cloudflare challenge script
    "/cdn-cgi/challenge",     # Cloudflare
    "sucuri_cloudproxy",      # Sucuri WAF
    "captcha-delivery.com",   # DataDome
)

# Real catalog pages run 30-400 KB. Anything past this is not a catalog page,
# and the phone, which applies the same limit, must not hold it in memory.
_MAX_PAGE_BYTES = 10 * 1024 * 1024

# Challenge interstitials are tiny; real catalog pages are 100 KB+. Only treat a page
# as a challenge when a marker appears AND the page is suspiciously small — this keeps
# large real pages that merely reference a captcha script from false-positiving.
_CHALLENGE_MAX_LEN = 30000

def _is_cloudflare(resp) -> bool:
    """True when a response was served by Cloudflare's edge (cf-ray / server header)."""
    return 'cf-ray' in resp.headers or 'cloudflare' in resp.headers.get('server', '').lower()


def fetch_html(url):
    try:
        r = _SESSION.get(url, timeout=20)
    except requests.exceptions.RequestException as e:
        print(f"Network error on {url}: {e}")
        return None

    # Cloudflare bot-management: a 403/429/503 from the CF edge is a challenge or
    # rate-limit, not a parseable page. Report it clearly and skip (existing data is
    # preserved). Retrying the same request won't help — it needs a gentler request
    # rate; bursty/concurrent scraping degrades the IP's reputation and causes more.
    if r.status_code in (403, 429, 503) and _is_cloudflare(r):
        print(f"  Cloudflare block ({r.status_code}) on {url} — skipping. "
              f"Use fewer --workers and rerun later.")
        return None

    # A 404 is how most stores say "there is no page N" -- it is the normal
    # end of pagination, not a fault, and announcing it made a clean full run
    # look like eleven failures. A 404 on page 1 is a real problem, but it
    # surfaces anyway: the store ends with no products and is reported as a
    # failed scrape by name.
    if r.status_code == 404:
        return None

    try:
        r.raise_for_status()
    except requests.exceptions.RequestException as e:
        print(f"Network error on {url}: {e}")
        return None

    if len(r.content) > _MAX_PAGE_BYTES:
        print(f"  Page too large on {url} ({len(r.content)} bytes) — skipping")
        return None

    text = r.text
    if len(text) < _CHALLENGE_MAX_LEN:
        low = text.lower()
        if any(marker in low for marker in _CHALLENGE_MARKERS):
            print(f"  Anti-bot challenge detected on {url} — skipping page (data left intact)")
            return None

    return BeautifulSoup(text, 'html.parser')


# ── Parser bindings ────────────────────────────────────────────────────────────
#
# A store whose parser is a generic with fixed arguments is a binding, not a
# function. Five stores had an identical one-line wrapper around the same
# WooCommerce call; they now share it.

_woo = partial(_parse_woo_li, link_cls='woocommerce-LoopProduct-link')    # cartonazo, griffingames, laloseta, lamadriguera, tertulia
_woo_astra = partial(_parse_woo_li, link_cls='ast-loop-product__link')    # mangaigames, mirzu, playcenter
_presta = _parse_presta    # aldeajuegos, lamesadevaras


def _bs_grid(base_url):
    """BS-collection store: same section.grid__item markup, only the host differs."""
    return partial(_parse_bs, item_tag='section', item_cls='grid__item',
                   base_url=base_url)

# PrestaShop. h1 title variant.
planetaloz = partial(_parse_presta, title_tag='h1', title_cls='product-title')
# PrestaShop. OOS flag uses hyphenated class 'out-of-stock'.
dementegames = partial(_parse_presta, oos_flag='out-of-stock')
# PrestaShop, h3 titles. OOS is a red "Agotado!" in div.ago, never a product flag.
diverti = partial(_parse_presta, title_tag='h3',
                  extra_oos=lambda item: _oos(_txt(item.find('div', class_='ago'))))
# Custom store using product-block card pattern.
vudugaming = partial(
    _parse_product_block, item_tag='article', item_cls='product-block',
    pricing_cls=None, old_cls='product-block__price--old',
    new_cls='product-block__price--new', single_cls='product-block__price',
    label_cls=None, base_url='https://www.vudugaming.cl')
# WooCommerce with custom AJAX pagination. Extra OOS via span.now_sold.
gatoarcano = partial(
    _parse_woo_li, link_cls=None,
    extra_oos=lambda item: bool(item.find('span', class_='now_sold')))
# WooCommerce/Astra. Extra OOS via ast-shop-product-out-of-stock; extra sale via ast-onsale-card.
revaruk = partial(
    _parse_woo_li, link_cls='ast-loop-product__link',
    extra_oos=lambda item: bool(
        (s := item.find('span', class_='ast-shop-product-out-of-stock'))
        and _oos(_txt(s))),
    extra_sale=lambda item: bool(item.find('span', class_='ast-onsale-card')))
top8 = _bs_grid('https://www.top8.cl')
gameofmagictienda = _bs_grid('https://www.gameofmagictienda.cl')
# WooCommerce generic. Extra sale via span.onsale.
labovedadelmago = partial(
    _parse_woo_li, link_cls='woocommerce-LoopProduct-link',
    extra_sale=lambda item: item.find('span', class_='onsale') is not None)
# Custom store sharing the product-block price/label conventions.
zonaxgamers = partial(
    _parse_product_block, item_tag='div', item_cls='product-block__wrapper',
    name_cls='product-block__name', pricing_cls=None,
    old_cls='product-block__price--old', new_cls='product-block__price--new',
    single_cls='product-block__price', label_cls=None,
    base_url='https://zonaxgamers.cl')
peakgames = _bs_grid('https://www.peakgames.cl')
wargaming = _bs_grid('https://www.wargaming.cl')
laguaridadeldragon = _bs_grid('https://www.laguaridadeldragon.cl')

# Shopify Debut-family grids. flexogames had its own copy of this parser until
# the generic was proven to produce byte-identical rows on its catalogue.
flexogames = partial(_parse_shopify_card, base_url='https://www.flexogames.cl')
chileboardgames = partial(_parse_shopify_card, base_url='https://chileboardgames.com')

# Shopify Dawn grids.
area52 = partial(_parse_dawn, base_url='https://area52.cl')
ovniplay = partial(_parse_dawn, base_url='https://ovniplay.cl')
juegosgori = partial(_parse_dawn, base_url='https://juegosgori.cl')

winterland = _bs_grid('https://www.winterland.cl')

# Jumpseller product-block grid, same shape as vudugaming and zonaxgamers.
boardkingdom = partial(_parse_product_block, base_url='https://www.boardkingdom.cl')
# By keyword: positionally the URL landed in `html`, and every call died on
# 'str' object has no attribute 'find_all' -- unseen while the store is offline.
playkingdom = partial(_parse_product_block_simple, base_url='https://playkingdom.cl')


# ── Site registry ──────────────────────────────────────────────────────────────
#
# pagination styles:
#   'shopify'    → ?page=N        (page 1 uses bare URL)
#   'page_param' → ?page=N        (PrestaShop and others, same pattern)
#   'woo'        → /page/N/       (WooCommerce, Odoo)
#   'pagina'     → ?pagina=N      (ludoteka)
#   'gatoarcano' → custom AJAX    (handled explicitly in build_url)

sites = [
    {'name': 'flexo',            'base_url': 'https://www.flexogames.cl/collections/juegos-de-mesa',            'parser': flexogames,       'pagination': 'shopify'},
    {'name': 'lafortalezapuq',   'base_url': 'https://www.lafortalezapuq.cl/jdm',                               'parser': lafortalezapuq,   'pagination': 'shopify'},
    {'name': 'planetaloz',       'base_url': 'https://www.planetaloz.cl/14-juegos-de-mesa',                      'parser': planetaloz,       'pagination': 'page_param'},
    {'name': 'updown',           'base_url': 'https://www.updown.cl/categoria-producto/juegos-de-mesa',          'parser': updown_juegos,    'pagination': 'woo'},
    {'name': 'aldeajuegos',      'base_url': 'https://www.aldeajuegos.cl/7-juegos-de-mesa',                      'parser': _presta,      'pagination': 'page_param'},
    {'name': 'elpatiogeek',      'base_url': 'https://www.elpatiogeek.cl/collections/all',                       'parser': elpatiogeek,      'pagination': 'shopify'},
    {'name': 'mangaigames',      'base_url': 'https://mangaigames.cl/tienda',                                    'parser': _woo_astra,      'pagination': 'woo'},
    {'name': 'cartonespesados',  'base_url': 'https://cartonespesados.cl/product-category/juegos-de-mesa',       'parser': cartonespesados,  'pagination': 'woo'},
    {'name': 'cartonazo',        'base_url': 'https://cartonazo.com/categoria-producto/juego-de-mesa',           'parser': _woo,        'pagination': 'woo'},
    {'name': 'dementegames',     'base_url': 'https://dementegames.cl/10-juegos-de-mesa',                        'parser': dementegames,     'pagination': 'page_param'},
    {'name': 'drjuegos',         'base_url': 'https://www.drjuegos.cl/2-todos-los-productos',                    'parser': drjuegos,         'pagination': 'page_param'},
    {'name': 'vudugaming',       'base_url': 'https://www.vudugaming.cl/juegos-de-mesa',                         'parser': vudugaming,       'pagination': 'page_param'},
    {'name': 'piedrabruja',      'base_url': 'https://piedrabruja.cl/collections/juegos-de-mesa',                'parser': piedrabruja,      'pagination': 'shopify'},
    {'name': 'gatoarcano',       'base_url': 'https://gatoarcano.cl/product-category/juegos-de-mesa',            'parser': gatoarcano,       'pagination': 'gatoarcano'},
    {'name': 'ludipuerto',       'base_url': 'https://www.ludipuerto.cl/categoria-producto/juegos-de-mesa',      'parser': ludipuerto,       'pagination': 'woo'},
    {'name': 'magicsur',         'base_url': 'https://www.magicsur.cl/15-juegos-de-mesa-magicsur-chile',         'parser': magicsur,         'pagination': 'page_param'},
    {'name': 'gameofmagictienda','base_url': 'https://www.gameofmagictienda.cl/collection/juegos-de-mesa',       'parser': gameofmagictienda,'pagination': 'page_param'},
    {'name': 'top8',             'base_url': 'https://www.top8.cl/collection/juegos-de-mesa',                    'parser': top8,             'pagination': 'page_param'},
    {'name': 'revaruk',          'base_url': 'https://revaruk.cl/product-category/juegos-de-mesa',               'parser': revaruk,          'pagination': 'woo'},
    {'name': 'labovedadelmago',  'base_url': 'https://www.labovedadelmago.cl/categoria-producto/juegos-de-mesa', 'parser': labovedadelmago,  'pagination': 'woo'},
    {'name': 'calabozotienda',   'base_url': 'https://www.calabozotienda.cl/tienda/familia/JUEGOS%20DE%20MESA',  'parser': calabozotienda,   'pagination': 'calabozo'},
    {'name': 'zonaxgamers',      'base_url': 'https://zonaxgamers.cl/juegos-de-mesa',                            'parser': zonaxgamers,      'pagination': 'page_param'},
    {'name': 'cafe2d6',          'base_url': 'https://www.cafe2d6.cl/collections/all',                           'parser': cafe2d6,          'pagination': 'shopify'},
    {'name': 'griffingames',     'base_url': 'https://www.griffingames.cl/categoria-producto/juegos-de-mesa',    'parser': _woo,     'pagination': 'woo'},
    {'name': 'playcenter',       'base_url': 'https://playcenter.cl/categoria-producto/juegos-de-mesa',          'parser': _woo_astra,       'pagination': 'woo'},
    {'name': 'enroque',          'base_url': 'https://juegosenroque.cl/collections/todos-los-juegos-de-mesa',    'parser': enroque,          'pagination': 'shopify'},
    {'name': 'kaiojuegos',       'base_url': 'https://kaiojuegos.cl/18-juegos-de-mesa',                          'parser': kaiojuegos,       'pagination': 'page_param'},
    {'name': 'manahouse',        'base_url': 'https://manahouse.cl/collections/juegos-de-mesa',                  'parser': manahouse,        'pagination': 'shopify'},
    {'name': 'devir',            'base_url': 'https://devir.cl/juegos-de-mesa',                                  'parser': devir,            'pagination': 'p'},
    {'name': 'thirdimpact_asmodee', 'base_url': 'https://www.thirdimpact.cl/brand/asmodee',                      'parser': thirdimpact,      'pagination': 'page_param'},
    {'name': 'thirdimpact_devir',   'base_url': 'https://www.thirdimpact.cl/brand/devir',                        'parser': thirdimpact,      'pagination': 'page_param'},
    {'name': 'buho',             'base_url': 'https://buhojuegosdemesa.cl/collections/catalogo',                 'parser': buhojuegosdemesa, 'pagination': 'shopify'},
    {'name': 'mirzu',            'base_url': 'https://mirzu.cl/tienda',                                          'parser': _woo_astra,            'pagination': 'woo'},
    {'name': 'peakgames',        'base_url': 'https://www.peakgames.cl/collection/juegos-de-mesa',               'parser': peakgames,        'pagination': 'page_param'},
    {'name': 'laloseta',         'base_url': 'https://laloseta.cl/categoria-producto/juego-de-mesa',             'parser': _woo,         'pagination': 'woo'},
    {'name': 'lamadriguera',     'base_url': 'https://tiendalamadriguera.cl/product-category/juegos-de-mesa',    'parser': _woo,     'pagination': 'woo'},
    {'name': 'lamesadevaras',    'base_url': 'https://lamesadevaras.cl/9-juegos-de-mesa',                        'parser': _presta,    'pagination': 'page_param'},
    {'name': 'wargaming',        'base_url': 'https://www.wargaming.cl/collection/juegos-de-mesa',               'parser': wargaming,        'pagination': 'page_param'},
    {'name': 'laguaridadeldragon', 'base_url': 'https://www.laguaridadeldragon.cl/collection/juegos',        'parser': laguaridadeldragon, 'pagination': 'page_param'},
    {'name': 'chileboardgames',   'base_url': 'https://chileboardgames.com/collections/all',                    'parser': chileboardgames,  'pagination': 'shopify'},
    {'name': 'boardkingdom',      'base_url': 'https://www.boardkingdom.cl/juegos-de-mesa',                     'parser': boardkingdom,     'pagination': 'page_param'},
    {'name': 'darkhobbies',      'base_url': 'https://www.darkhobbies.cl/collections/all',                       'parser': darkhobbies,      'pagination': 'shopify'},
    {'name': 'shivano',          'base_url': 'https://shivano.cl/12-juegos-de-mesa',                             'parser': shivano,          'pagination': 'p'},
    {'name': 'guildreams',       'base_url': 'https://www.guildreams.com/collection/juegos-de-mesa',             'parser': guildreams,       'pagination': 'page_param'},
    {'name': 'playkingdom',      'base_url': 'https://playkingdom.cl/juegos-de-mesa',                            'parser': playkingdom,      'pagination': 'page_param'},
    {'name': 'jugones',          'base_url': 'https://www.jugones.cl/juegos-de-mesa',                            'parser': jugones,          'pagination': 'page_param'},
    {'name': 'tertulia',         'base_url': 'https://tertulia.cl/categoria-producto/juego-de-mesa',             'parser': _woo,         'pagination': 'product-page'},
    {'name': 'lautarojuegos',    'base_url': 'https://www.lautarojuegos.cl/juegos-de-mesa',                      'parser': lautarojuegos,    'pagination': 'page_param'},
    {'name': 'araucania',        'base_url': 'https://araucaniagaming.cl/productos/juegosdemesa',                 'parser': araucania,        'pagination': 'woo'},
    {'name': 'tentami',          'base_url': 'https://tentami.cl/collections/juegos-de-mesa',                      'parser': tentami,          'pagination': 'page_param'},
    {'name': 'area52',           'base_url': 'https://area52.cl/collections/all',                                'parser': area52,           'pagination': 'shopify'},
    {'name': 'ovniplay',         'base_url': 'https://ovniplay.cl/collections/%F0%9F%93%A6-catalogo-completo',   'parser': ovniplay,         'pagination': 'shopify'},
    {'name': 'juegosgori',       'base_url': 'https://juegosgori.cl/collections/juegos-de-mesa-1',               'parser': juegosgori,       'pagination': 'shopify'},
    {'name': 'geekz',            'base_url': 'https://geekz.cl/shop/category/juegos-de-mesa-34',                 'parser': geekz,            'pagination': 'woo'},
    {'name': 'ludoteka',         'base_url': 'https://www.ludoteka.cl/juegos-de-mesa',                           'parser': ludoteka,         'pagination': 'pagina'},
    {'name': 'winterland',       'base_url': 'https://www.winterland.cl/collection/todos',                       'parser': winterland,       'pagination': 'page_param'},
    {'name': 'diverti',          'base_url': 'https://www.diverti.cl/juegos-de-mesa-16',                         'parser': diverti,          'pagination': 'page_param'},
    {'name': 'fillerplace',      'base_url': 'https://fillerplace.cl/tienda',                                 'parser': fillerplace,      'pagination': 'woo'},
    {'name': 'spells',           'base_url': 'https://spells.cl/categoria-producto/juegos-de-mesa',           'parser': _woo,             'pagination': 'woo'},
    {'name': 'ludipacha',        'base_url': 'https://ludipacha.cl/tienda',                                   'parser': _woo,             'pagination': 'woo'},
    {'name': 'mundoimaginario',  'base_url': 'https://mundoimaginario.cl/product-category/juegos-de-mesa',    'parser': _woo,             'pagination': 'woo'},
    {'name': 'ruta12club',       'base_url': 'https://www.ruta12club.cl/juegos-de-mesa',                      'parser': ruta12club,       'pagination': 'page_param'},
    {'name': 'ludopia',          'base_url': 'https://www.ludopia.cl/search?q=',                              'parser': ludopia,          'pagination': 'page_param'},
]


def build_url(base_url, pagination, page):
    """Construct the paginated URL for a given page number and pagination style."""
    sep = '&' if '?' in base_url else '?'

    if pagination == 'gatoarcano':
        return f"{base_url}/?jsf=epro-archive-products&pagenum={page}"
    if pagination == 'calabozo':
        return f"{base_url}/{page}"
    if page == 1:
        return base_url
    if pagination in ('shopify', 'page_param'):
        # sep, not '?': a base that already has a query (ludopia's /search?q=)
        # takes the page as a second parameter.
        return f"{base_url}{sep}page={page}"
    if pagination == 'p':
        return f"{base_url}?p={page}"
    if pagination == 'pagina':
        return f"{base_url}?pagina={page}"
    if pagination == 'woo':
        return f"{base_url}/page/{page}/"
    if pagination == 'product-page':
        return f"{base_url}{sep}product-page={page}"
    raise ValueError(f"Unknown pagination style: {pagination}")
