"""
Store-specific parsers: the stores no generic parser fits.

Each takes a parsed page and returns the store's records; scrape.py binds
them to the registry. Their quirks are documented where they live, and the
Kotlin ports (core/scrape/Bespoke.kt, LongTail.kt) mirror them one to one.
"""

from .parsers import (
    _img,
    _norm,
    _oos,
    _stock_cls,
    _txt,
    _url,
    _woo_prices,
)


def lafortalezapuq(html):
    """Custom CMS. Discounted price stored inside <i> tag within the price span."""
    res = []
    for item in (html.find_all('figure', class_='product') if html else []):
        try:
            t_elem = item.find('h5')
            if not t_elem:
                continue
            dp = item.find('span', class_='product-price-discount')
            if dp:
                sale = dp.find('i')
                curr = _txt(sale)
                if sale:
                    sale.extract()          # remove <i> so remaining text is the original
                orig = _txt(dp)
            else:
                orig, curr = _txt(item.find('span', class_='product-price')), None
            orig, curr = _norm(orig, curr)
            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': None,
                'url': _url(item.find('a', href=True), "https://www.lafortalezapuq.cl"),
                'image': _img(item, "https://www.lafortalezapuq.cl"),
            })
        except Exception as e:
            print(f"  [lafortalezapuq] skipping item: {e}")
    return res




def updown_juegos(html):
    """WooCommerce/Woodmart. Stock span lives on the parent container, not the product div."""
    res = []
    for item in (html.find_all('div', class_='product-element-bottom') if html else []):
        try:
            t_elem = item.find('h3', class_='wd-entities-title')
            if not t_elem:
                continue
            pc = item.find('span', class_='price')
            if not pc:
                continue
            orig, curr = _norm(*_woo_prices(pc))
            stock = "Agotado" if item.parent.find('span', class_='out-of-stock') else None
            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock, 'url': _url(t_elem.find('a', href=True)),
                'image': _img(item.parent),
            })
        except Exception as e:
            print(f"  [updown_juegos] skipping item: {e}")
    return res




def elpatiogeek(html):
    res = []
    for item in (html.find_all('div', class_='grid-item') if html else []):
        try:
            title = _txt(item.find('p'))
            if not title:
                continue

            pc = item.find('div', class_='product-item--price')
            pval, is_sale = None, False

            if pc:
                if sm := pc.find('small'):
                    pval = _txt(sm)
                elif sh1 := pc.find('span', class_='h1'):
                    spans = sh1.find_all('span', class_='visually-hidden')
                    pval  = _txt(spans[-1]) if spans else _txt(sh1)

                vh = pc.find('span', class_='visually-hidden')
                if vh and 'venta' in (_txt(vh) or '').lower():
                    is_sale = True

            sale_tag = item.find('div', class_='sale-tag')
            orig, curr = (_txt(sale_tag), pval) if (is_sale and sale_tag) else (pval, None)
            orig, curr = _norm(orig, curr)

            cls   = item.get('class', [])
            stock = "Agotado" if 'sold-out' in cls else ("Oferta" if 'on-sale' in cls or curr else None)

            res.append({
                'title': title, 'original_price': orig, 'current_price': curr,
                'stock_status': stock,
                'url': _url(item.find('a', class_='product-grid-item'), "https://www.elpatiogeek.cl"),
                'image': _img(item, "https://www.elpatiogeek.cl"),
            })
        except Exception as e:
            print(f"  [elpatiogeek] skipping item: {e}")
    return res


def cartonespesados(html):
    """WooCommerce Blocks. Uses wc-block-components-product-price."""
    res = []
    for item in (html.find_all('li', class_='wc-block-product') if html else []):
        try:
            t_elem = item.find('h3', class_='wp-block-post-title')
            if not t_elem:
                continue
            orig, curr = _norm(*_woo_prices(item.find('div', class_='wc-block-components-product-price')))
            cls   = item.get('class', [])
            stock = "Agotado" if 'outofstock' in cls else ("Oferta" if 'onsale' in cls or curr else None)
            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock, 'url': _url(t_elem.find('a', href=True)),
                'image': _img(item),
            })
        except Exception as e:
            print(f"  [cartonespesados] skipping item: {e}")
    return res






def drjuegos(html):
    """PrestaShop variant. Uses article.product-container and availability div for stock."""
    res = []
    for item in (html.find_all('article', class_='product-container') if html else []):
        try:
            t_elem = item.find('h5', class_='product-name')
            if not t_elem:
                continue
            # 'price product-price' is a multi-class span, not the standard 'price'.
            price_elem = item.find('span', class_='price product-price')
            reg_elem   = item.find('span', class_='regular-price')
            orig, curr = _norm(
                _txt(reg_elem) or _txt(price_elem),
                _txt(price_elem) if reg_elem else None,
            )
            avail = _txt(item.find('div', class_='product-availability')) or ''
            stock = "Agotado" if _oos(avail) else ("Oferta" if item.find('div', class_='product-flags') else None)
            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock, 'url': _url(t_elem.find('a', href=True)),
                'image': _img(item),
            })
        except Exception as e:
            print(f"  [drjuegos] skipping item: {e}")
    return res




def piedrabruja(html):
    """Shopify. Inverted naming: price__regular = current sale price, price__sale = original."""
    res = []
    for item in (html.find_all('div', class_='product-card') if html else []):
        try:
            t_elem = item.find('a', class_='product-card__title')
            if not t_elem:
                continue
            pc     = item.find('div', class_='price')
            reg_p  = pc.find('span', class_='price__regular') if pc else None
            sale_p = pc.find('span', class_='price__sale')    if pc else None
            # Counter-intuitive naming: __regular holds the sale price, __sale holds the crossed-out original.
            orig, curr = _norm(
                _txt(sale_p) if sale_p else _txt(reg_p),
                _txt(reg_p) if sale_p else None,
            )
            btn     = item.find('button', class_='cowlendar-add-to-cart')
            btn_txt = btn.find('span', class_='hidden md:block') if btn else None
            oos     = bool(btn_txt and _oos(_txt(btn_txt)))
            badges  = item.find('div', class_='badges')
            stock   = "Agotado" if oos else (
                "Oferta" if curr or (badges and badges.find('span', class_='badge--onsale')) else None
            )
            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock, 'url': _url(t_elem, "https://piedrabruja.cl"),
                'image': _img(item, "https://piedrabruja.cl"),
            })
        except Exception as e:
            print(f"  [piedrabruja] skipping item: {e}")
    return res




def ludipuerto(html):
    """WooCommerce/Woodmart. Same container structure as updown but different item class."""
    res = []
    for item in (html.find_all('div', class_='product-grid-item') if html else []):
        try:
            t_elem = item.find('h3', class_='wd-entities-title')
            if not t_elem:
                continue
            orig, curr = _norm(*_woo_prices(item.find('span', class_='price')))
            stock = _stock_cls(item.get('class', []), curr)
            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock, 'url': _url(t_elem.find('a', href=True)),
                'image': _img(item),
            })
        except Exception as e:
            print(f"  [ludipuerto] skipping item: {e}")
    return res


def magicsur(html):
    res = []
    for item in (html.find_all('article', class_='product-miniature') if html else []):
        try:
            if not (t_elem := item.find('h2', class_='product-title')):
                continue

            pc       = item.find('div', class_='product-price-and-shipping')
            orig_txt = _txt(pc.find('span', class_='regular-price')) if pc else None
            curr_txt = _txt(pc.find('span', class_='product-price') or pc.find('span', class_='price')) if pc else None
            orig, curr = _norm(orig_txt, curr_txt)

            check = ' '.join(filter(None, [
                _txt(item.find('div', class_='product-availability')),
                _txt(item.find('ul',  class_='product-flags')),
            ]))
            stock = "Agotado" if _oos(check) else ("Oferta" if curr else None)

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock,
                'url': _url(t_elem.find('a', href=True), "https://www.magicsur.cl"),
                'image': _img(item, "https://www.magicsur.cl"),
            })
        except Exception as e:
            print(f"  [magicsur] skipping item: {e}")
    return res












def calabozotienda(html):
    res = []
    for item in (html.find_all('div', class_='card mb-3 box-shadow') if html else []):
        try:
            p_elem = item.find('p', class_='card-text')
            t_elem = p_elem.find('span') if p_elem else None
            if not t_elem:
                continue

            orig = _txt(item.find('span', style=lambda s: s and 'line-through' in s))
            curr = _txt(item.find('span', class_='font-color'))
            orig, curr = _norm(orig or curr, curr if orig else None)

            stock = "Agotado" if item.find('span', class_='badge-danger') else (
                "Oferta" if curr or item.find('span', class_='burbuja-descuento') else None
            )
            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock,
                'url': _url(item.find('a', href=True), "https://www.calabozotienda.cl"),
                'image': _img(item, "https://www.calabozotienda.cl"),
            })
        except Exception as e:
            print(f"  [calabozotienda] skip: {e}")
    return res




def cafe2d6(html):
    res = []
    for item in (html.find_all('li', class_='item') if html else []):
        try:
            t_elem = item.find('h3')
            if not t_elem:
                continue

            orig, curr = None, None
            pc = item.find('p', class_='price')
            if pc:
                span = pc.find('span')
                if span:
                    orig = _txt(span)
                    span.extract()          # remove <span> so remaining text is the sale price
                    curr = _txt(pc)
                else:
                    orig = _txt(pc)
            orig, curr = _norm(orig, curr)

            cls   = item.get('class', [])
            oos   = 'sold-out' in cls or bool(item.find(class_='add-to-agotado'))
            stock = "Agotado" if oos else ("Oferta" if 'on-sale' in cls or curr else None)

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock,
                'url': _url(item.find('a', href=True), "https://www.cafe2d6.cl"),
                'image': _img(item, "https://www.cafe2d6.cl"),
            })
        except Exception as e:
            print(f"  [cafe2d6] skip: {e}")
    return res






def enroque(html):
    res = []
    for item in (html.find_all('li', class_='js-pagination-result') if html else []):
        try:
            t_elem = item.find('a', class_='card-link')
            if not t_elem:
                continue

            orig, curr = None, None
            pc = item.find('div', class_='price__default')
            if pc:
                was = pc.find('s', class_='price__was')
                cur = pc.find('strong', class_='price__current')
                orig, curr = _norm(
                    _txt(was) or _txt(cur),
                    _txt(cur) if _txt(was) else None,
                )

            oos = bool(
                item.find('span', class_='product-label--sold-out') or
                item.find('div', class_=lambda c: c and 'price--sold-out' in c)
            )
            stock = "Agotado" if oos else ("Oferta" if curr or item.find('span', class_='product-label--sale') else None)

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock, 'url': _url(t_elem, "https://juegosenroque.cl"),
                'image': _img(item, "https://juegosenroque.cl"),
            })
        except Exception as e:
            print(f"  [enroque] skip: {e}")
    return res


def kaiojuegos(html):
    res = []
    for item in (html.find_all('div', class_='thumbnail-container') if html else []):
        try:
            if not (t_elem := item.find('h3', class_='product-title')):
                continue

            pc    = item.find('div', class_='product-price-and-shipping')
            reg   = _txt(pc.find('span', class_='regular-price')) if pc else None
            price = _txt(pc.find('span', class_='price'))          if pc else None
            orig, curr = _norm(reg or price, price if reg else None)

            flags = _txt(item.find('ul', class_='product-flags')) or ''
            oos   = _oos(flags) or bool((btn := item.find('button', class_='add-to-cart')) and btn.has_attr('disabled'))
            stock = "Agotado" if oos else ("Oferta" if curr or 'oferta' in flags.lower() else None)

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock,
                'url': _url(t_elem.find('a', href=True), "https://kaiojuegos.cl"),
                'image': _img(item, "https://kaiojuegos.cl"),
            })
        except Exception as e:
            print(f"  [kaiojuegos] skip: {e}")
    return res


def manahouse(html):
    res = []
    for item in (html.find_all('div', class_='product-grid-item') if html else []):
        try:
            if not (t_elem := item.find('a', class_='product-grid-item__title')):
                continue

            orig, curr = None, None
            if pc := item.find(class_='product-grid-item__price'):
                was = pc.find(['s', 'del'])
                if was:
                    orig_text = _txt(was)
                    was.extract()
                    orig, curr = _norm(orig_text, _txt(pc))
                else:
                    orig, curr = _norm(_txt(pc), None)

            btn    = item.find('button', attrs={'name': 'add'})
            badges = item.find_all(class_=lambda c: c and ('badge' in c.lower() or 'label' in c.lower()))
            oos    = bool(btn and btn.has_attr('disabled')) or any(_oos(_txt(b)) for b in badges)
            stock  = "Agotado" if oos else ("Oferta" if curr else None)

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock, 'url': _url(t_elem, "https://manahouse.cl"),
                'image': _img(item, "https://manahouse.cl"),
            })
        except Exception as e:
            print(f"  [manahouse] skip: {e}")
    return res


def devir(html):
    res = []
    for item in (html.find_all('li', class_='product-item') if html else []):
        try:
            if not (t_elem := item.find('a', class_='product-item-link')):
                continue

            orig, curr = None, None
            pc = item.find('div', class_='price-box')
            if pc:
                old_p = pc.find('span', class_='old-price')
                new_p = pc.find('span', class_='special-price')
                if old_p and new_p:
                    orig, curr = _norm(
                        _txt(old_p.find('span', class_='price')),
                        _txt(new_p.find('span', class_='price')),
                    )
                else:
                    orig, curr = _norm(_txt(pc.find('span', class_='price')), None)

            action_area = item.find('div', class_='actions-primary')
            action_text = (_txt(action_area) or '').lower()
            oos   = 'avísame' in action_text or 'avisame' in action_text or bool(item.find('div', class_='stock unavailable'))
            stock = "Agotado" if oos else ("Oferta" if curr else None)

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock, 'url': _url(t_elem, "https://devir.cl"),
                'image': _img(item, "https://devir.cl"),
            })
        except Exception as e:
            print(f"  [devir] skip: {e}")
    return res


def thirdimpact(html):
    res = []
    for item in (html.find_all('section', class_='grid__item') if html else []):
        try:
            if not (t_elem := item.find('h3', class_='bs-collection__product-title')):
                continue

            orig, curr = None, None
            pc = item.find('div', class_='bs-collection__product-price')
            if pc:
                del_e = pc.find('del', class_='bs-collection__old-price')
                fin_e = pc.find('div', class_=lambda c: c and 'bs-collection__product-final-price' in c)
                orig, curr = _norm(_txt(del_e) or _txt(fin_e), _txt(fin_e) if del_e else None)

            btn   = item.find('button', attrs={'data-bs': 'cart.add.collection'})
            oos   = not btn or btn.has_attr('disabled')
            stock = "Agotado" if oos else ("Oferta" if curr else None)

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock,
                'url': _url(item.find('a', href=True), "https://www.thirdimpact.cl"),
                'image': _img(item, "https://www.thirdimpact.cl"),
            })
        except Exception as e:
            print(f"  [thirdimpact] skip: {e}")
    return res


def buhojuegosdemesa(html):
    res = []
    for item in (html.find_all('a', class_='product-card') if html else []):
        try:
            if not (t_elem := item.find('div', class_='product-card__name')):
                continue

            orig, curr = None, None
            pc = item.find('div', class_='product-card__price')
            if pc:
                for hidden in pc.find_all('span', class_='visually-hidden'):
                    hidden.extract()
                was = pc.find('s')
                if was:
                    orig_val = _txt(was)
                    was.extract()
                    orig, curr = _norm(orig_val, _txt(pc))
                else:
                    orig, curr = _norm(_txt(pc), None)

            btn  = item.find('span', class_='product-card__overlay-btn')
            tags = item.find_all('div', class_='product-tag')
            check_text = ' '.join(filter(None, [_txt(btn)] + [_txt(t) for t in tags]))
            oos   = _oos(check_text)
            stock = "Agotado" if oos else ("Oferta" if curr else None)

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock, 'url': _url(item, "https://buhojuegosdemesa.cl"),
                'image': _img(item, "https://buhojuegosdemesa.cl"),
            })
        except Exception as e:
            print(f"  [buhojuegosdemesa] skip: {e}")
    return res














def darkhobbies(html):
    res = []
    for item in (html.find_all('product-card') if html else []):
        try:
            t_elem = item.find('h3', class_='h4') or item.find('a', ref='productTitleLink')
            if not t_elem:
                continue

            orig, curr = None, None
            pc = item.find('product-price')
            if pc:
                orig, curr = _norm(
                    _txt(pc.find('span', class_='compare-at-price')),
                    _txt(pc.find('span', class_='price')),
                )

            btn    = item.find('button', attrs={'name': 'add'})
            badges = ' '.join(_txt(b) for b in item.find_all(class_='product-badges__badge'))
            oos    = (btn and btn.has_attr('disabled')) or _oos(badges) or _oos(_txt(btn))
            stock  = "Agotado" if oos else ("Oferta" if curr else None)

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock,
                'url': _url(item.find('a', class_='product-card__link'), "https://www.darkhobbies.cl"),
                'image': _img(item, "https://www.darkhobbies.cl"),
            })
        except Exception as e:
            print(f"  [darkhobbies] skip: {e}")
    return res

def tentami(html):
    """
    Shopify (Tentami). Product-card grid with h3.h4 titles and product-price spans.

    A sale renders two spans: `span.price` with the offer and
    `span.compare-at-price` with the list price, each prefixed by a
    visually-hidden label ("Precio de oferta", "Precio habitual"). Reading only
    `span.price` stored the offer as the list price -- 7 of 24 products on a
    page had their discount silently dropped.
    """
    res = []
    for item in (html.find_all('product-card') if html else []):
        try:
            t_elem = item.find('h3', class_='h4')
            if not t_elem:
                continue

            orig, curr = None, None
            pc = item.find('product-price')
            if pc:
                for label in pc.find_all('span', class_='visually-hidden'):
                    label.extract()
                # "$17,990 CLP": parse_price reads the number, the suffix is ignored.
                orig, curr = _norm(_txt(pc.find('span', class_='compare-at-price')),
                                   _txt(pc.find('span', class_='price')))

            # Product link
            link = item.find('a', href=True)
            url = _url(link, "https://tentami.cl") if link else None

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': None,
                'url': url,
                'image': _img(item),
            })
        except Exception as e:
            print(f"  [tentami] skipping item: {e}")
    return res


def shivano(html):
    res = []
    for item in (html.find_all('li', class_='ajax_block_product') if html else []):
        try:
            if not (t_elem := item.find('a', class_='product-name')): 
                continue

            title_str = t_elem.get('title')
            if not title_str:
                title_str = _txt(t_elem.find('span', class_='list-name'))

            orig, curr = None, None
            pc = item.find('div', class_='content_price')
            if pc:
                orig_txt = _txt(pc.find('span', class_='old-price'))
                curr_txt = _txt(pc.find('span', class_='price'))
                orig, curr = _norm(orig_txt, curr_txt)
                
                if orig is None and curr is not None:
                    orig, curr = curr, None

            btn = item.find('a', class_='ajax_add_to_cart_button')
            avail = _txt(item.find('span', class_='availability'))
            
            oos = not btn or _oos(avail)
            stock = "Agotado" if oos else ("Oferta" if curr else None)

            res.append({
                'title': title_str,
                'original_price': orig,
                'current_price': curr,
                'stock_status': stock,
                'url': _url(t_elem, "https://shivano.cl"),
                'image': _img(item, "https://shivano.cl"),
            })
        except Exception as e:
            print(f"  [shivano] skip: {e}")
    return res

def guildreams(html):
    res = []
    for item in (html.find_all('div', class_='bs-product') if html else []):
        try:
            if not (t_elem := item.find('h2')):
                continue

            orig_txt, curr_txt = None, None
            if pc := item.find('div', class_='bs-product-price'):
                orig_txt = _txt(pc.find('div', class_='bs-product-old-price'))
                curr_txt = _txt(pc.find('div', class_='bs-product-final-price'))
            orig, curr = _norm(orig_txt, curr_txt)

            btn        = item.find('button', attrs={'data-bs': 'cart.add.collection'})
            oos        = _oos(_txt(item)) or not btn
            stock      = "Agotado" if oos else ("Oferta" if curr else None)

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock,
                'url': _url(item.find('a', href=True), "https://www.guildreams.com"),
                'image': _img(item, "https://www.guildreams.com"),
            })
        except Exception as e:
            print(f"  [guildreams] skip: {e}")
    return res


def jugones(html):
    res = []
    for item in (html.find_all('div', class_='producto') if html else []):
        try:
            if not (t_elem := item.find('a', class_='modelo')):
                continue

            orig_txt, curr_txt = None, None
            is_sale = False

            pc = item.find('a', class_='precio')
            if pc:
                is_sale = 'oferta' in pc.get('class', [])
                if orig_node := pc.find('small'):
                    if is_sale:
                        orig_txt = (_txt(orig_node) or '').replace('Antes:', '').strip() or None
                    orig_node.extract()
                curr_txt = _txt(pc)

            orig, curr = _norm(orig_txt, curr_txt)
            if not is_sale:
                orig, curr = orig or curr, None

            stock = "Agotado" if _oos(_txt(item)) else ("Oferta" if curr else None)

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock, 'url': _url(t_elem, "https://www.jugones.cl"),
                'image': _img(item, "https://www.jugones.cl"),
            })
        except Exception as e:
            print(f"  [jugones] skip: {e}")
    return res




def lautarojuegos(html):
    res = []
    for item in (html.find_all('div', class_='product-block') if html else []):
        try:
            if not (t_elem := item.find('h4')):
                continue
            a_tag = t_elem.find('a')
            if not a_tag:
                continue

            orig_txt, curr_txt = None, None
            if pc := item.find('div', class_='list-price'):
                orig_node = pc.find('span', class_='product-block-discount')
                curr_node = pc.find('span', class_='product-block-normal')
                if orig_node and curr_node:
                    orig_txt, curr_txt = _txt(orig_node), _txt(curr_node)
                else:
                    curr_txt = _txt(pc)
            orig, curr = _norm(orig_txt, curr_txt)

            btn  = item.find('button', class_='adc')
            tags = item.find_all('span', class_='status-tag')
            check_text = ' '.join(filter(None, [_txt(btn)] + [_txt(t) for t in tags]))
            oos   = _oos(check_text) or btn is None
            stock = "Agotado" if oos else ("Oferta" if curr else None)

            res.append({
                'title': _txt(a_tag), 'original_price': orig, 'current_price': curr,
                'stock_status': stock, 'url': _url(a_tag, "https://www.lautarojuegos.cl"),
                'image': _img(item, "https://www.lautarojuegos.cl"),
            })
        except Exception as e:
            print(f"  [lautarojuegos] skip: {e}")
    return res


def araucania(html):
    res = []
    for item in (html.find_all('li', class_='product') if html else []):
        try:
            if not (t_elem := item.find('h3', class_='woocommerce-loop-product__title')):
                continue

            orig_txt, curr_txt = None, None
            if pc := item.find('span', class_='price'):
                orig_node = pc.find('del')
                curr_node = pc.find('ins')
                if orig_node and curr_node:
                    orig_txt = _txt(orig_node)
                    curr_txt = _txt(curr_node)
                else:
                    curr_txt = _txt(pc)
            orig, curr = _norm(orig_txt, curr_txt)

            oos   = 'outofstock' in item.get('class', []) or _oos(_txt(item.find('span', class_='stock-label')))
            stock = "Agotado" if oos else ("Oferta" if curr else None)

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': stock,
                'url': _url(t_elem.find('a'), "https://araucaniagaming.cl"),
                'image': _img(item, "https://araucaniagaming.cl"),
            })
        except Exception as e:
            print(f"  [araucania] skip: {e}")
    return res


def geekz(html):
    """
    Odoo website_sale. The list price sits in a <strike> that the theme hides
    with `d-none` whenever there is no discount, repeating the current price.
    The cards carry no out-of-stock signal; only stocked products are listed.
    """
    res = []
    for item in (html.find_all('form', class_='oe_product_cart') if html else []):
        try:
            if not (t_elem := item.find('h6', attrs={'itemprop': 'name'})):
                continue

            pc  = item.find('div', class_='product_price')
            now = pc.find('span', class_='text-delta') if pc else None
            was = pc.find('strike') if pc else None
            if was and 'd-none' in was.get('class', []):
                was = None
            orig, curr = _norm(_txt(was), _txt(now))

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': "Oferta" if curr else None,
                'url': _url(item.find('a', attrs={'itemprop': 'url'}), "https://geekz.cl"),
                'image': _img(item, "https://geekz.cl"),
            })
        except Exception as e:
            print(f"  [geekz] skip: {e}")
    return res


def _shown(el):
    """el unless an inline style hides it."""
    return el if el and 'display: none' not in (el.get('style') or '') else None


def ludoteka(html):
    """
    Custom store. Every product renders three cards -- the live one, a "JUEGO
    USADO" twin and a list-price twin -- and inline styles hide two of them,
    so only the visible card is read. Prices inside it toggle the same way.
    """
    res = []
    for item in (html.find_all('div', class_='ltk-card-col') if html else []):
        try:
            if not _shown(item):
                continue
            if not (t_elem := item.find('h5', class_='ltk-card-title')):
                continue

            old = _shown(item.find('span', class_='ltk-price-old'))
            now = next((n for n in item.find_all('div', class_='ltk-price-now') if _shown(n)), None)
            orig, curr = _norm(_txt(old), _txt(now))

            res.append({
                'title': _txt(t_elem), 'original_price': orig, 'current_price': curr,
                'stock_status': "Oferta" if curr else None,
                'url': _url(item.find('a', class_='ltk-card-link'), "https://www.ludoteka.cl"),
                'image': _img(item, "https://www.ludoteka.cl"),
            })
        except Exception as e:
            print(f"  [ludoteka] skip: {e}")
    return res


def fillerplace(html):
    """
    WooCommerce on a LA-Studio theme: li.product_item, not li.product's
    woocommerce-loop-product__title. The h2 title also wraps the price and an
    excerpt, so the name is read from its link alone.
    """
    res = []
    for item in (html.find_all('li', class_='product_item') if html else []):
        try:
            t_elem = item.find('h2', class_='product_item--title')
            link = t_elem.find('a', href=True) if t_elem else None
            if not link:
                continue
            orig, curr = _norm(*_woo_prices(item.find('span', class_='price')))
            res.append({
                'title': _txt(link), 'original_price': orig, 'current_price': curr,
                'stock_status': _stock_cls(item.get('class', []), curr),
                'url': _url(link),
                'image': _img(item),
            })
        except Exception as e:
            print(f"  [fillerplace] skip: {e}")
    return res


def ruta12club(html):
    """
    Jumpseller, a different theme from boardkingdom's: div.product-block with
    the name in div.post-prev-title and <del>list</del> <strong>offer</strong>
    in div.post-prev-text; a lone <strong> is the list price. Sold out is a
    span.unavailable badge over the photo.
    """
    res = []
    for item in (html.find_all('div', class_='product-block') if html else []):
        try:
            title_box = item.find('div', class_='post-prev-title')
            link = title_box.find('a', href=True) if title_box else None
            if not link:
                continue
            prices = item.find('div', class_='post-prev-text')
            old = prices.find('del') if prices else None
            now = prices.find('strong') if prices else None
            orig, curr = _norm(_txt(old), _txt(now)) if old else (_txt(now), None)
            oos = item.find('span', class_='unavailable') is not None
            res.append({
                'title': _txt(link), 'original_price': orig, 'current_price': curr,
                'stock_status': "Agotado" if oos else ("Oferta" if curr else None),
                'url': _url(link, "https://www.ruta12club.cl"),
                'image': _img(item, "https://www.ruta12club.cl"),
            })
        except Exception as e:
            print(f"  [ruta12club] skip: {e}")
    return res


def ludopia(html):
    """
    Jumpseller product-block cards without the add-to-cart button or status
    label the generic reads: sold out is a div.product-block__disabled over
    the photo, and a discount is product-block__price--discount holding
    <span>offer</span> <span>list</span>, offer first.
    """
    res = []
    for item in (html.find_all('div', class_='product-block') if html else []):
        try:
            link = item.find('a', class_='product-block__name')
            if not link:
                continue
            price = item.find('div', class_='product-block__price')
            spans = price.find_all('span') if price else []
            if price and 'product-block__price--discount' in price.get('class', []) and len(spans) >= 2:
                orig, curr = _norm(_txt(spans[1]), _txt(spans[0]))
            else:
                orig, curr = _txt(price), None
            oos = item.find('div', class_='product-block__disabled') is not None
            res.append({
                'title': _txt(link), 'original_price': orig, 'current_price': curr,
                'stock_status': "Agotado" if oos else ("Oferta" if curr else None),
                'url': _url(link, "https://www.ludopia.cl"),
                'image': _img(item, "https://www.ludopia.cl"),
            })
        except Exception as e:
            print(f"  [ludopia] skip: {e}")
    return res
