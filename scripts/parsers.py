"""
Shared parsing helpers and the generic parsers.

Text, URL, price, stock and image helpers, plus the six generic parsers that
most stores are a binding of (see the bindings in scrape.py). Pure: a parsed
page in, records out. The Kotlin port is core/scrape/Generic.kt and Soup.kt,
held to this file by the page fixtures.
"""


# ── Primitive helpers ──────────────────────────────────────────────────────────

_OOS_WORDS = ('agotado', 'sin stock', 'out of stock', 'fuera de stock', 'no disponible')


def _txt(el):
    """get_text(strip=True) → str, or None if el is None."""
    return el.get_text(strip=True) if el else None


def _url(el, base=""):
    """Extract href; prepend base if relative. Returns None if missing."""
    href = el.get('href') if el else None
    if not href:
        return None
    return href if href.startswith('http') else base + href


def _norm(orig, curr):
    """Nullify current_price when equal to original (no real discount).
    Also promotes a lone curr into orig when orig is None."""
    if orig is None and curr is not None:
        return curr, None
    return (orig, None) if orig == curr else (orig, curr)


def _oos(text):
    """Return True if text contains any out-of-stock phrase."""
    t = (text or '').lower()
    return any(w in t for w in _OOS_WORDS)


# Lazy-loading themes park the real image in a data-* attribute and put a
# placeholder (a 1x1 gif, a data: URI, a spinner) in src, so src is read last.
_IMG_ATTRS = ('data-src', 'data-lazy-src', 'data-original', 'data-srcset', 'srcset', 'src')

# The width filled into Shopify's "{width}" URL templates: enough for a list
# thumbnail on a phone without pulling the full-size photo.
_IMG_WIDTH = '360'


def _srcset_first(value):
    """The first URL of a src or srcset value ("a.jpg 1x, b.jpg 2x" -> "a.jpg")."""
    return value.split(',')[0].strip().split(' ')[0] if value else None


def _img(el, base=""):
    """
    URL of the first real image inside a product card, or None.

    Only the URL is kept, never the bytes: ~30k photos would put gigabytes in
    a database that is otherwise tens of megabytes. Whoever shows the image
    fetches and caches it.
    """
    if not el:
        return None
    for img in el.find_all(['img', 'source']):
        for attr in _IMG_ATTRS:
            url = _srcset_first(img.get(attr))
            if not url or url.startswith('data:'):
                continue
            url = url.replace('{width}', _IMG_WIDTH)
            if url.startswith('//'):
                return 'https:' + url
            if url.startswith('http'):
                return url
            return base.rstrip('/') + '/' + url.lstrip('/') if base else None
    return None


# ── Price helpers ──────────────────────────────────────────────────────────────

def _woo_prices(container):
    """
    WooCommerce standard price block.
    <del> = original, <ins> = sale. Falls back to <bdi> or raw text.
    Returns (original_price, current_price).
    """
    if not container:
        return None, None
    del_e, ins_e = container.find('del'), container.find('ins')
    if del_e and ins_e:
        return _txt(del_e), _txt(ins_e)
    bdi = container.find('bdi')
    raw = _txt(bdi) or container.get_text(strip=True).replace('IVA INC', '').strip() or None
    return raw, None


def _presta_prices(item):
    """
    PrestaShop standard price pair.
    <span class='regular-price'> = original, <span class='price'> = sale.
    Returns (original_price, current_price).
    """
    reg   = _txt(item.find('span', class_='regular-price'))
    price = _txt(item.find('span', class_='price'))
    return _norm(reg or price, price if reg else None)


# ── Stock helpers ──────────────────────────────────────────────────────────────

def _stock_flags(item, oos_cls='out_of_stock', discount_cls='discount', curr=None):
    """
    PrestaShop <ul class='product-flags'> pattern.
    Returns "Agotado", "Oferta", or None.
    """
    flags = item.find('ul', class_='product-flags')
    if flags:
        if flags.find('li', class_=oos_cls):
            return "Agotado"
        if flags.find('li', class_=discount_cls) or curr:
            return "Oferta"
    return None


def _stock_cls(cls, curr, sale_cls='sale', oos_cls='outofstock', extra_sale=False):
    """
    WooCommerce item-class pattern.
    Returns "Agotado", "Oferta", or None.
    """
    if oos_cls in cls:
        return "Agotado"
    if sale_cls in cls or curr or extra_sale:
        return "Oferta"
    return None


# ── Generic parsers ────────────────────────────────────────────────────────────

def _parse_woo_li(html, link_cls='woocommerce-LoopProduct-link',
                  extra_oos=None, extra_sale=None):
    """
    Generic WooCommerce parser for <li class='product'> grids.
    Used by: cartonazo, mangaigames, revaruk, gatoarcano, labovedadelmago,
             laloseta, lamadriguera, tertulia, griffingames.

    link_cls:   CSS class for the product anchor (None = first <a href>).
    extra_oos:  callable(item) -> bool for store-specific OOS signals.
    extra_sale: callable(item) -> bool for store-specific sale signals.
    """
    res = []
    for item in (html.find_all('li', class_='product') if html else []):
        try:
            t_elem = item.find('h2', class_='woocommerce-loop-product__title')
            if not t_elem:
                continue
            orig, curr = _norm(*_woo_prices(item.find('span', class_='price')))
            cls  = item.get('class', [])
            oos  = 'outofstock' in cls or bool(extra_oos and extra_oos(item))
            sale = bool(extra_sale and extra_sale(item))
            link = (item.find('a', class_=link_cls, href=True) if link_cls else None) \
                   or item.find('a', href=True)
            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': "Agotado" if oos else _stock_cls(cls, curr, extra_sale=sale),
                'url': _url(link),
                'image': _img(item),
            })
        except Exception as e:
            print(f"  [_parse_woo_li] skipping item: {e}")
    return res


def _parse_presta(html, title_tag='h2', title_cls='product-title',
                  item_cls='product-miniature', oos_flag='out_of_stock', extra_oos=None):
    """
    Generic PrestaShop parser for <article class='product-miniature'> grids.
    Used by: aldeajuegos, dementegames, planetaloz, lamesadevaras, diverti.

    title_tag / title_cls: heading element and class for the product name.
    oos_flag:  class on the <li> inside product-flags that signals out-of-stock.
    extra_oos: callable(item) -> bool for store-specific OOS signals.
    """
    res = []
    for item in (html.find_all('article', class_=item_cls) if html else []):
        try:
            t_elem = item.find(title_tag, class_=title_cls)
            if not t_elem:
                continue
            orig, curr = _presta_prices(item)
            stock = ("Agotado" if extra_oos and extra_oos(item)
                     else _stock_flags(item, oos_cls=oos_flag, curr=curr))
            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock, 'url': _url(t_elem.find('a', href=True)),
                'image': _img(item),
            })
        except Exception as e:
            print(f"  [_parse_presta] skipping item: {e}")
    return res


def _parse_bs(html, item_tag='section', item_cls='grid__item', base_url=''):
    """
    Generic BS-collection parser.
    Used by: top8, gameofmagictienda, peakgames, wargaming.

    item_tag / item_cls: container element and class for each product card.
    base_url: prepended to relative hrefs.
    """
    res = []
    for item in (html.find_all(item_tag, class_=item_cls) if html else []):
        try:
            t_elem = item.find('h3', class_='bs-collection__product-title')
            if not t_elem:
                continue

            orig, curr = None, None
            pc = item.find(['div', 'section'], class_='bs-collection__product-price')
            if pc:
                # BeautifulSoup calls a class_ function once per class token
                # (and once with the joined string), never with the list. The
                # previous `any('old-price' in x for x in c)` therefore walked
                # the characters of one token and could never match, so a sale
                # here would have been recorded as a full-price product.
                del_e = pc.find('del', class_=lambda c: bool(c and 'old-price' in c))
                fin_e = pc.find('div', class_='bs-collection__product-final-price')
                orig, curr = (_txt(del_e), _txt(fin_e)) if del_e else (_txt(fin_e), None)
            orig, curr = _norm(orig, curr)

            pw  = item.find('div', class_='bs-collection__product')
            cls = list(item.get('class', [])) + list(pw.get('class', []) if pw else [])
            check = ' '.join(filter(None, [
                _txt(item.find('div', class_='bs-collection__product-notice')),
                _txt(item.find('div', class_='bs-stock')),
                _txt(item.find('div', class_='bs-collection__stock')),
            ]))
            oos   = _oos(check) or 'out-of-stock' in cls or 'outStock' in cls
            stock = "Agotado" if oos else ("Oferta" if curr or 'has-discount' in cls else None)

            link = t_elem.find('a', href=True) or item.find('a', href=True)
            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock, 'url': _url(link, base_url),
                'image': _img(item, base_url),
            })
        except Exception as e:
            print(f"  [_parse_bs] skipping item: {e}")
    return res


def _parse_product_block(html, item_tag='article', item_cls='product-block',
                          name_cls='product-block__name', pricing_cls='product-block__pricing',
                          old_cls='product-block__price--old', new_cls='product-block__price--new',
                          single_cls='product-block__price', label_cls='product-block__label--status',
                          base_url=''):
    """
    Generic parser for stores using the 'product-block' card pattern.
    Used by: vudugaming, playkingdom, zonaxgamers.

    Handles optional old/new price pair or single price.
    OOS detected via label text (_oos) and disabled add-to-cart button.
    """
    res = []
    for item in (html.find_all(item_tag, class_=item_cls) if html else []):
        try:
            t_elem = item.find('a', class_=name_cls)
            if not t_elem:
                continue

            orig_txt, curr_txt = None, None
            pc = item.find(class_=pricing_cls) if pricing_cls else item
            if pc:
                old_node = pc.find(class_=old_cls)
                new_node = pc.find(class_=new_cls)
                if old_node and new_node:
                    orig_txt, curr_txt = _txt(old_node), _txt(new_node)
                else:
                    curr_txt = _txt(pc.find(class_=single_cls)) or _txt(pc)
            orig, curr = _norm(orig_txt, curr_txt)

            # find(class_=None) does not mean "no filter": it matches the first
            # element with no class attribute at all, whose text then decided
            # stock. Harmless only while that element happens to be empty.
            status_label = _txt(item.find(class_=label_cls)) if label_cls else None
            btn = item.find('button', class_='product-block__button--add-to-cart')
            oos = (
                _oos(status_label)
                or any(_oos(_txt(l)) for l in item.find_all('div', class_='product-block__label'))
                or bool(btn and btn.has_attr('disabled'))
            )
            stock = "Agotado" if oos else ("Oferta" if curr else None)

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock, 'url': _url(t_elem, base_url),
                'image': _img(item, base_url),
            })
        except Exception as e:
            print(f"  [_parse_product_block] skipping item: {e}")
    return res


def _parse_shopify_card(html, base_url=''):
    """
    Generic Shopify "Debut"-family collection grid.

    Two price layouts ship with this theme family and stores use both. The
    older one puts the pre-sale price in `div.price__compare`; the newer one
    puts it in a bare <s class="price-item--regular"> inside `div.price__sale`.
    Both also render the discounted figure a second time under
    `div.price__regular`, so reading that first turns a 50% sale into a
    full-price product with no discount -- chileboardgames showed Pendulum at
    its $27.500 offer with the $55.000 list price dropped entirely.

    Stock comes only from the `price--sold-out` class on the <dl>.
    `div.price__availability` looks like the obvious source and is a trap: the
    theme renders it reading "Agotado" on every card and hides it with CSS
    unless the product really is sold out, so believing it marked all 333
    chileboardgames products out of stock.
    """
    res = []
    for item in (html.find_all('li', class_='grid__item') if html else []):
        try:
            t_elem = item.find('div', class_='grid-view-item__title')
            if not t_elem:
                continue

            # The struck-through figure is the only unambiguous signal that a
            # sale is running, so it is checked before anything else.
            was = item.find('s', class_='price-item--regular')
            if was and _txt(was):
                orig, curr = _norm(_txt(was), _txt(item.find('span', class_='price-item--sale')))
            else:
                reg = item.find('div', class_='price__regular')
                orig, curr = _norm(
                    _txt(reg.find('span', class_='price-item--regular') if reg else None), None)

            dl = item.find('dl', class_='price')
            stock = ("Agotado" if dl and 'price--sold-out' in dl.get('class', [])
                     else None)

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock,
                'url': _url(item.find('a', class_='grid-view-item__link'), base_url),
                'image': _img(item, base_url),
            })
        except Exception as e:
            print(f"  [_parse_shopify_card] skipping item: {e}")
    return res


def _parse_dawn(html, base_url=''):
    """
    Generic Shopify Dawn collection grid (li.grid__item > h3.card__heading).

    Same price markup as the newer Debut layout: a struck-through
    <s class="price-item--regular"> beside `span.price-item--sale`. Dawn renders
    the <s> on every card, sale or not, repeating the list price; _norm turns
    those equal pairs back into "no discount". Stock comes from the
    `price--sold-out` class on div.price.
    """
    res = []
    for item in (html.find_all('li', class_='grid__item') if html else []):
        try:
            t_elem = item.find('h3', class_='card__heading')
            if not t_elem:
                continue

            was = item.find('s', class_='price-item--regular')
            if was and _txt(was):
                orig, curr = _norm(_txt(was), _txt(item.find('span', class_='price-item--sale')))
            else:
                reg = item.find('div', class_='price__regular')
                orig, curr = _norm(
                    _txt(reg.find('span', class_='price-item--regular') if reg else None), None)

            price = item.find('div', class_='price')
            stock = ("Agotado" if price and 'price--sold-out' in price.get('class', [])
                     else None)

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock,
                'url': _url(t_elem.find('a'), base_url),
                'image': _img(item, base_url),
            })
        except Exception as e:
            print(f"  [_parse_dawn] skipping item: {e}")
    return res


def _parse_product_block_simple(html, base_url):
    """Product-block grid parser (used by playkingdom)."""
    res = []
    for item in (html.find_all('article', class_='product-block') if html else []):
        try:
            if not (t_elem := item.find('a', class_='product-block__name')):
                continue

            orig_txt, curr_txt = None, None
            if pc := item.find('div', class_='product-block__pricing'):
                old_node = pc.find('div', class_='product-block__price--old')
                new_node = pc.find('div', class_='product-block__price--new')
                if old_node and new_node:
                    orig_txt, curr_txt = _txt(old_node), _txt(new_node)
                else:
                    curr_txt = _txt(pc.find('div', class_='product-block__price'))
            orig, curr = _norm(orig_txt, curr_txt)

            status_label = _txt(item.find('div', class_='product-block__label--status'))
            stock = "Agotado" if _oos(status_label) else ("Oferta" if curr else None)

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock, 'url': _url(t_elem, base_url),
                'image': _img(item, base_url),
            })
        except Exception as e:
            print(f"  [{base_url}] skip: {e}")
    return res
