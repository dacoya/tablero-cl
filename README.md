# 🎲 tablero-cl

> Comparador de precios de juegos de mesa en Chile, como app Android.

---

## ¿Qué hace?

La app recorre los catálogos de las tiendas chilenas de juegos de mesa desde
el teléfono, guarda todo en una base SQLite local y te deja buscar, comparar,
seguir juegos con precio objetivo y ver qué cambió desde la última vez.

El código Python de `scripts/` ya no es para consultar: es la referencia contra
la que se prueba la app, y el lado PC que la alimenta (tiendas que el teléfono
no puede scrapear, rankings de BoardGameGeek y la copia que importa la app).

## App Android

`android/` es una app personal que scrapea directamente desde el teléfono, sin
el PC de por medio. Reutiliza `scripts/schema.sql` y el registro de tiendas, y
se prueba contra los mismos fixtures que la versión Python.

- **Inicio**: buscador (tolera errores de tipeo), categorías y vitrinas
  (mayores descuentos, novedades, mejor valor); cada una abre el catálogo ya filtrado.
- **Catálogo**: grilla con fotos y orden. "Por juego" deja solo la oferta más barata de cada
  uno. "Filtros" junta tipo, categoría y top de BGG, rango de precio,
  descuento mínimo, stock, ofertas y novedad a la vez; el botón
  dice cuántos productos quedan antes de aplicarlos.
- **Ficha**: mejor precio, oferta por tienda con su antigüedad, botón a la tienda,
  expansiones del juego y "Ya lo tengo". Si anotas tu costo de despacho de una
  tienda, las ofertas se ordenan por el total.
- **Mi radar**: cambios en lo que sigues, juegos seguidos con precio objetivo, tu
  colección y lo que cambió en el resto del mercado desde tu última revisión.
- **Ajustes**: **Tiendas** (actualizar una o todas; las que fallaron se marcan en
  rojo), actualización diaria (4:00, con WiFi y cargando, por defecto),
  avisos, valores por defecto de las listas, importar/exportar la base.

Scrapea 48 de las 50 tiendas. `calabozotienda` y `araucania` quedan "solo PC":
desde esta red no se pudo guardar ninguna página suya para probar el parser.

### Instalar

```bash
cd android
./gradlew :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk
```

El APK queda también en `dist/`. Cópialo al teléfono y ábrelo (Android pide
permitir "instalar apps desconocidas" la primera vez). Requiere Android 11+.

La firma usa `android/release.jks` con las claves en
`android/keystore.properties`; ninguno de los dos entra a git. **Respáldalos**:
sin ese keystore no se puede actualizar la app instalada, solo desinstalarla
(y perder su base) para poner una versión nueva.

### Pruebas

```bash
cd android
./gradlew :core:test                      # paridad con Python, en el PC
./gradlew :app:connectedDebugAndroidTest  # la misma paridad en un teléfono/emulador
```

### Empezar con el historial del PC

```bash
tablero snapshot                        # data/exports/tablero-AAAAMMDD-HHMM.db
```

Copia ese archivo al teléfono y elige **Ajustes → Importar**. Reemplaza la base
del teléfono; desde ahí cada una sigue por su cuenta. **Exportar** hace el
camino inverso. No copies `tablero.db` directamente: en modo WAL los últimos
cambios pueden estar todavía en `tablero.db-wal`.

---

---

## Lado PC

```bash
pip install -e .            # instala las dependencias y el comando `tablero`
python -m pytest            # tests (requiere pytest)
```

```
tablero update             scrapear tiendas y actualizar la base (la crea si no existe)
tablero bgg [CSV]          importar el CSV de rankings de BoardGameGeek y asociar juegos
tablero snapshot           copia de la base en un archivo, para importar en la app
tablero fixtures           guardar páginas y vectores de referencia (paridad con Android)
```

```bash
tablero update                          # todas las tiendas, 5 workers
tablero update --incremental            # solo las obsoletas (> 24 h)
tablero update --sites tertulia top8    # tiendas específicas
tablero update --dry-run                # solo la primera página (pruebas)
tablero update -w 3                     # más suave si Cloudflare bloquea
```

Cada tienda se escribe por separado, así que un scrape fallido nunca borra los
datos de otra. Un scrape vacío se trata como fallo: una caída de red no debe
parecerse a una tienda que vació su catálogo.

Los datos viven en `data/` (fuera de git), o en `TABLERO_DATA_DIR` si está
definida. Dependencias: `requests`, `beautifulsoup4`, `rapidfuzz`.

## Tiendas cubiertas

56 tiendas activas.

| Tienda | URL | Ubicación |
|---|---|---|
| Aldea Juegos | aldeajuegos.cl | Santiago |
| Area 52 | area52.cl | Santiago |
| Café 2d6 | cafe2d6.cl | Santiago |
| Cartonazo | cartonazo.com | Santiago |
| Cartones Pesados | cartonespesados.cl | Santiago |
| DarkHobbies | darkhobbies.cl | Santiago |
| Demente Games | dementegames.cl | Santiago |
| Devir | devir.cl | Santiago |
| Diverti | diverti.cl | Santiago |
| DR Juegos | drjuegos.cl | Santiago |
| El Patio Geek | elpatiogeek.cl | Santiago |
| Geekz | geekz.cl | Santiago |
| Griffin Games | griffingames.cl | Santiago |
| Guildreams | guildreams.com | Santiago |
| Juegos Enroque | juegosenroque.cl | Santiago |
| Jugones | jugones.cl | Santiago |
| Ludoteka | ludoteka.cl | Santiago |
| Kaio Juegos | kaiojuegos.cl | Santiago |
| La Madriguera | tiendalamadriguera.cl | Santiago |
| Magic Sur | magicsur.cl | Santiago |
| Mana House | manahouse.cl | Santiago |
| Mangai Games | mangaigames.cl | Santiago |
| Ovniplay | ovniplay.cl | Santiago |
| Piedra Bruja | piedrabruja.cl | Santiago |
| Play Center | playcenter.cl | Santiago |
| PlayKingdom | playkingdom.cl | Santiago |
| Revaruk | revaruk.cl | Santiago |
| Shivano | shivano.cl | Santiago |
| Tentami | tentami.cl | Santiago |
| Tertulia | tertulia.cl | Santiago |
| Third Impact | thirdimpact.cl | Santiago |
| Updown Juegos | updown.cl | Santiago |
| Vudu Gaming | vudugaming.cl | Santiago |
| Wargaming | wargaming.cl | Santiago |
| Zona X Gamers | zonaxgamers.cl | Santiago |
| Calabozo Tienda | calabozotienda.cl | Concepción |
| Game of Magic Tienda | gameofmagictienda.cl | Concepción |
| Planeta Loz | planetaloz.cl | Concepción |
| Gato Arcano | gatoarcano.cl | Viña del Mar |
| La Loseta | laloseta.cl | Viña del Mar |
| Peak Games | peakgames.cl | Viña del Mar |
| Flexogames | flexogames.cl | La Serena |
| La Bóveda del Mago | labovedadelmago.cl | La Serena |
| Mirzu | mirzu.cl | Arica |
| Lautaro Juegos | lautarojuegos.cl | Villa Alemana |
| Lamesadevaras | lamesadevaras.cl | Puerto Varas |
| La Fortaleza PUQ | lafortalezapuq.cl | Punta Arenas |
| Winterland | winterland.cl | Puerto Montt |
| Ludi Puerto | ludipuerto.cl | Talcahuano |
| Juegos Gori | juegosgori.cl | — |
| Araucanía Gaming | araucaniagaming.cl | Temuco |
| Top 8 | top8.cl | Temuco |
| Búho Juegos de Mesa | buhojuegosdemesa.cl | Valparaíso |
| La Guarida del Dragón | laguaridadeldragon.cl | — |
| Chile Board Games | chileboardgames.com | — |
| Board Kingdom | boardkingdom.cl | — |



---

## Cómo funciona

### Scraping (`scrape.py`, `parsers.py`, `bespoke.py`)

Cada tienda tiene su parser. Cuatro plataformas cubren la mayoría, y las
tiendas que las usan son solo un *binding* de un parser genérico de
`parsers.py` en `scrape.py`; el resto tiene el suyo en `bespoke.py`:

```
WooCommerce   → <del>/<ins> para precios, clases CSS para stock
PrestaShop    → span.regular-price / span.price, ul.product-flags
Shopify       → varía por tema; clases en grid items
BS-Collection → estructura custom compartida por top8/gameofmagic
```

Los parsers comparten helpers:

```python
_txt(el)              # get_text seguro, retorna None si el es None
_url(el, base)        # extrae href, prepende base si es relativo
_norm(orig, curr)     # anula current_price si es igual al original
_woo_prices(pc)       # extrae del/ins/bdi de un contenedor WooCommerce
_presta_prices(item)  # extrae regular-price/price de PrestaShop
_img(el, base)        # URL de la foto (data-src/srcset antes que src)
```

El loop de paginación detecta páginas duplicadas (cuando el sitio repite la última página en vez de 404) y se detiene solo.

### Normalización de títulos (`utils.py`)

```python
normalize("Clank!: En las Catacumbas (En Español)")
# → "clank en las catacumbas"

normalize("Terraforming Mars Edición Kickstarter")
# → "terraforming mars"
```

El pipeline de normalización:
1. Minúsculas
2. Elimina tags de idioma (`en español`, `en inglés`, `castellano`, etc.)
3. Elimina marcadores de edición (`edición deluxe`, `2da edición`, etc.)
4. Descompone acentos (NFKD → elimina combining marks)
5. Reemplaza puntuación con espacio
6. Colapsa whitespace

### Qué cuenta como "el mismo juego"

Los títulos se normalizan para agrupar el mismo juego entre tiendas, pero solo
se descarta lo que de verdad no identifica nada: "Juego de Mesa" es redundante
en una tienda de juegos de mesa, y "cooperativo" o "familiar" describen sin
nombrar.

En cambio **"Juego de Cartas", "Juego de Dados" y "Party" se conservan**: nombran
un producto distinto, no una categoría. Al eliminarlos, "Catan" y "Catan: Juego
de Cartas" quedaban como una sola entrada y el precio del juego de cartas
($10.990) se anunciaba como el del juego base ($29.990). Fusionar de más inventa
un precio equivocado; fusionar de menos solo muestra dos filas honestas.

Algunas tiendas además truncan los títulos ("Terraforming Mars -..."). En esos
casos el nombre se completa desde el slug de la URL para poder distinguirlos,
conservando el título tal como lo escribió la tienda para mostrarlo.

Las variantes del mismo juego entre tiendas quedan agrupadas en la tabla `game`.

### Precios chilenos

```python
parse_price("$69.990")    # → 69990.0
parse_price("$69.990,50") # → 69990.5
parse_price("69,990")     # → 69990.0
```

Detecta formato por posición del último separador: si la última coma viene después del último punto, es separador decimal.

---

## Agregar una tienda

1. Si la tienda usa una plataforma conocida, basta un binding en `scrape.py`
   (p. ej. `partial(_parse_woo_li, link_cls=...)`). Si no, escribir su parser en
   `bespoke.py`:

```python
def mi_tienda(html):
    res = []
    for item in (html.find_all('article', class_='product') if html else []):
        try:
            t_elem = item.find('h2', class_='product-title')
            if not t_elem:
                continue
            orig, curr = _norm(*_woo_prices(item.find('span', class_='price')))
            res.append({
                'title':          _txt(t_elem),
                'original_price': orig,
                'current_price':  curr,
                'stock_status':   "Agotado" if 'outofstock' in item.get('class', []) else None,
                'url':            _url(t_elem.find('a', href=True)),
                'image':          _img(item),
            })
        except Exception as e:
            print(f"  [mi_tienda] skipping item: {e}")
    return res
```

2. Registrar en `sites`:

```python
{
    'name':       'mitienda',
    'base_url':   'https://www.mitienda.cl/juegos-de-mesa',
    'parser':     mi_tienda,
    'pagination': 'woo',        # 'shopify' | 'woo' | 'page_param' | 'p' | 'product-page' | 'gatoarcano' | 'calabozo'
    'output':     '../data/mitienda_jdm.csv',
},
```

3. Guardar su fixture: `tablero fixtures --sites mitienda`. Escribe la página
   y lo que el parser sacó de ella en `tests/fixtures/mitienda/`, y regenera
   los vectores. Desde ahí `pytest` falla si el parser deja de producir lo
   mismo, y el port Android se prueba contra esos mismos archivos.

4. Para que el teléfono la scrapee, portar el parser a
   `android/core/src/main/kotlin/cl/tablero/core/scrape/` y agregarlo a
   `PARSERS` en `Stores.kt`. `./gradlew :core:test` lo compara con el fixture;
   sin ese paso la tienda aparece en la app como "solo PC".

### Fixtures y vectores (paridad con Android)

`tests/fixtures/` es la referencia contra la que se prueban las dos
implementaciones, Python y Kotlin:

| Archivo | Contiene |
|---|---|
| `<tienda>/pageN.html.gz` + `.expected.json` | página real y los registros que produce su parser |
| `derive_vectors.json` | registro scrapeado → fila de base (limpieza de título, `norm`, tipo, precios, URL canónica) |
| `url_vectors.json` | URL de cada página según el estilo de paginación |
| `ingest_scenario.json` | tres scrapes de una tienda → tablas resultantes y vista de cambios |

Los vectores salen del código Python, así que un cambio de comportamiento a
propósito se regenera (`tablero fixtures --vectors-only`, sin red) y queda en
el diff. Si no se regenera, `pytest` falla.

---

## Notas

- El scraper espera 1–2.5 s (con jitter) entre páginas por cortesía con los servidores.
- Los precios reflejan lo que el sitio muestra; precios originales inflados artificialmente son responsabilidad de cada tienda.
- **Cloudflare:** varias tiendas usan bot-management de Cloudflare, que es sensible a la
  reputación de tu IP: el scraping agresivo (mucha concurrencia) la degrada y provoca más
  bloqueos `403`/`429`. El default ya es conservador (5 workers); si aún ves "Cloudflare
  block", baja más (`tablero update -w 3`) y reintenta más tarde. Los bloqueos se registran y **no borran**
  los datos previos. El scraper **no** intenta evadir Cloudflare.