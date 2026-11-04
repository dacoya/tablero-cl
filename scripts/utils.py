"""
Title normalization and price parsing.

These turn what a store wrote into the two things the rest of the tool needs:
a display title, and a `norm` key that decides which listings are the same
product. Nothing here touches a database or prints.

Discount maths, table rendering and pagination used to live here too; the
first moved into SQL and the others into render.py.
"""

import re
import unicodedata


# ---------------------------------------------------------------------------
# Title normalization
# ---------------------------------------------------------------------------

# Language tags appended by stores: "en español", "(en inglés)", "castellano", etc.
_LANG_TAG = re.compile(
    r'[\(\[]?\b(en\s+)?(espa[nñ]ol|ingl[eé]s|ingles|english|castellano'
    r'|multi-?lengua(je)?|multi-?language|multil[ií]ng[uü]e|tril[ií]ng[uü]e|bil[ií]ng[uü]e)\b[\)\]]?'
    r'|[\(\[]\s*(eng|esp|ing)\s*[\)\]]',
    re.IGNORECASE,
)

# Edition markers: "edición deluxe", "2da edición", "kickstarter edition", etc.
# Only the word goes: "deluxe" or "aniversario" after it names a different
# product and stays in the key.
_EDITION_TAG = re.compile(
    r'\b(\d+[aª]?|primera|segunda|tercera|cuarta|second|third)\s*(edici[oó]n|edition)\b'
    r'|\b(edici[oó]n|edition)\b',
    re.IGNORECASE,
)

# Edition ordinals left standing on their own: "Carcassonne 2da", "(2da ed.)",
# "Catan (4ta Edición)" once the word is gone. English "7th"/"15th" is left
# alone -- it names games ("7th Continent") -- and so is "15º Aniversario".
_EDITION_ORDINAL = re.compile(
    r'\b\d+\s*(da|ra|era|ta|va|na|ed|ª|º)\b(?!\s*(aniversario|anniversary|a[nñ]os))|\bed\b\.?',
    re.IGNORECASE,
)

# A revision of the same game: "Splendor (Nueva Versión)", "Dixit Nueva",
# "LCG (Ed. Revisada)". "nueva" counts only at the end, where it qualifies the
# whole title rather than naming something ("Nueva York").
_REVISION_TAG = re.compile(
    r'\bnuev[ao](\s+versi[oó]n)?\b(?=[\s\)\]]*$)|\brevisad[ao]\b|\breedici[oó]n\b',
    re.IGNORECASE,
)

# Publisher in brackets: "Catan - Base (Devir)". Only known names, because a
# bracket just as often carries what makes the product different
# ("(ampliación 5-6 jugadores)", "(+ El Viejo Oeste)").
_PUBLISHER_TAG = re.compile(
    r'[\(\[]\s*(devir|fractal(\s+juegos)?|eggertspiele|next\s+move|asmodee|maldito(\s+games)?'
    r'|ludonova|zacatrus|sd\s+games|gen\s?x(\s+games)?|arrakis(\s+games)?|tranjis(\s+games)?'
    r'|haba|kosmos|ravensburger|mercurio|2\s?tomatoes|gdm(\s+games)?|cmon|bgl'
    r'|edge(\s+entertainment)?|hasbro|mattel)\s*[\)\]]',
    re.IGNORECASE,
)

# "Básico" and a bare "juego" name the plain game: "Catan Básico", "Catan - El
# Juego". "Juego de ..." is kept -- "Juego de Cartas" is a different product.
_PLAIN_GAME = re.compile(
    r'\bb[aá]sico\b|\bjuego\b(?!\s+de\b)',
    re.IGNORECASE,
)

# Words that carry no identity once titles are compared token by token:
# "La Polilla Tramposa" vs "Polilla Tramposa", "Clank Catacumbas" vs "Clank en
# las Catacumbas".
_STOPWORDS = frozenset({'el', 'la', 'los', 'las', 'lo', 'de', 'del', 'y', 'e', 'en', 'the', 'of'})

# A trailing "expansión" once the title already names something after the
# game ("Heat Leyendas Expansión" = "Heat Leyendas"). Not on two-word keys, or
# "Catan Expansión" would collapse into "Catan".
_TRAILING_EXPANSION = frozenset({'expansion', 'expansiones'})


# Sales-status words stores prepend or append: "PREVENTA Clank", "Clank (Pre-Order)".
# They say nothing about which product it is, and left in they split one game
# into a "preventa" row and a normal row.
_PREORDER_TAG = re.compile(
    r'\bpre[\s\-]?(venta|order|orden)\b|\breservas?\b',
    re.IGNORECASE,
)

# "Base game" qualifiers. These name the plain game, so "Clank", "Clank Base"
# and "Clank (juego base)" are one product listed three ways -- searching Clank
# returned all three as separate results with different prices.
_BASE_QUALIFIER = re.compile(
    r'[\(\[]?\b(juego\s+)?base\b[\)\]]?\s*$',
    re.IGNORECASE,
)

# A leading "expansión:" qualifier. What follows is the expansion's actual name,
# so "Clank! Expansión: Tesoros Sumergidos" and "Clank! Tesoros Sumergidos" are
# the same product. Requires a following word, or "Catan Expansión" alone would
# collapse into "Catan".
_EXPANSION_QUALIFIER = re.compile(
    r'\b(mini\s?)?expansi[oó]n(es)?\b(?=[\s:,\-]+\w)',
    re.IGNORECASE,
)


def normalize(text: str) -> str:
    """
    Return a cleaned, lowercase, accent-free, punctuation-free version of a
    title for fuzzy matching. Original title is NOT modified.

    Pipeline:
      1. Lowercase
      2. Strip language tags   ("en español", "en inglés", etc.)
      3. Strip edition markers ("edición deluxe", "2da edición", etc.)
         and sales status ("preventa", "reserva")
      4. Strip qualifiers that do not change which product it is
         ("Clank Base" -> "clank"; "Clank Expansión: X" -> "clank x";
          "Catan Básico (Devir)" -> "catan"; "Dixit 2da" -> "dixit")
      5. Remove accents        (é→e, ñ→n, ü→u, etc.)
      6. Replace punctuation   with space (!, :, -, (, ), /, etc.)
      7. Drop stopwords and a trailing "expansión", unless nothing is left

    Note this only affects the MATCH key. The title shown on screen keeps
    whatever the store wrote.
    """
    if not isinstance(text, str):
        return ''

    text = text.lower()
    text = _LANG_TAG.sub(' ', text)
    text = _EDITION_TAG.sub(' ', text)
    text = _EDITION_ORDINAL.sub(' ', text)
    text = _REVISION_TAG.sub(' ', text)
    text = _PREORDER_TAG.sub(' ', text)
    text = _PUBLISHER_TAG.sub(' ', text)
    text = _EXPANSION_QUALIFIER.sub(' ', text)
    text = _BASE_QUALIFIER.sub(' ', text.strip())
    text = _PLAIN_GAME.sub(' ', text)

    # NFKD decomposition separates base letters from combining marks (accents).
    text = unicodedata.normalize('NFKD', text)
    text = ''.join(ch for ch in text if not unicodedata.combining(ch))

    text = re.sub(r'[^\w\s]', ' ', text)
    return _drop_filler(text.split())


def _drop_filler(tokens: list[str]) -> str:
    """Join tokens without stopwords or a trailing "expansión"; keep all if that empties it."""
    kept = [t for t in tokens if t not in _STOPWORDS] or tokens
    if len(kept) >= 3 and kept[-1] in _TRAILING_EXPANSION:
        kept = kept[:-1]
    return ' '.join(kept)


# ---------------------------------------------------------------------------
# Title cleaning (display)
# ---------------------------------------------------------------------------

# Generic noise phrases stores append to product titles.
#
# Only phrases that carry NO information in a board-game store belong here.
# "Juego de Mesa" is tautological; attributes like "cooperativo" or "familiar"
# describe a game without identifying it.
#
# Deliberately absent: "juego de cartas", "juego de dados", "juego de rol" and
# their English forms. Those name a different PRODUCT, not a category, and
# stripping them merged distinct games under one title -- "Catan" and
# "Catan: Juego de Cartas" became the same entry, so the card game's $10.990
# was advertised as the price of the board game. Over-merging invents a wrong
# price; under-merging just shows two honest rows, so the tie goes to keeping.
_NOISE_PHRASE = (
    r'juegos?\s+de\s+mesa'
    r'|board\s+game'
    r'|juego\s+familiar|juego\s+educativo|juego\s+cooperativo'
    r'|juego\s+de\s+estrategia|party\s+game'
)

# Standalone category words that appear as comma/separator-delimited tokens.
#
# Only pure ATTRIBUTES belong here -- words that describe a game without ever
# naming one. "party", "dados", "cartas" and "rol" were removed because each
# also ends a real product name: "Sushi Go - Party" was being reduced to
# "Sushi Go" and merged with that different, cheaper game, even though 23
# stores sell Sushi Go Party as its own title.
#
# Some stores do use these as genuine tags ("...!Juego de Mesa, Party, Juego de
# Cartas"), so a trailing tag now survives into the display title. That is the
# cheaper mistake: a slightly noisy label, rather than a wrong price.
_NOISE_WORD = (
    r'cooperativo|familiar|educativo|estrategia|competitivo|abstracto'
)

# Pass 1: noise phrase preceded by a proper word boundary (space, separator, or start).
# The optional (?:el|la|los|las) group pulls the Spanish definite article INTO the
# match so that _strip_unless_subtitle can decide whether to keep or drop the whole
# "El Juego de Mesa" cluster in one shot — preventing an orphaned "El" from being
# left behind after the noise phrase is stripped.
# Note: the leading separator (e.g. "- " before "Juego de Mesa") is intentionally
# NOT consumed here.  Leaving it in place lets pass 3 recognise any noise words that
# follow (e.g. "Cooperativo" in "- Juego de Mesa Cooperativo") as punct-separated
# tags that it can strip independently.
_TITLE_NOISE = re.compile(
    r'(?:^|(?<=[\s\-\|,;:]))(?:\b(?:el|la|los|las)\b\s+)?(?:' + _NOISE_PHRASE + r')\s*[\-\|,;:]?',
    re.IGNORECASE,
)

# Pass 2: noise phrase directly concatenated to a preceding word without space
# ("La CuraJuego de Mesa") — matched when the phrase runs to end-of-string or separator.
_TITLE_NOISE_CONCAT = re.compile(
    r'(?<=[A-Za-záéíóúüñÁÉÍÓÚÜÑ])(?:' + _NOISE_PHRASE + r')(?:\s*[\-\|,;:].*)?$',
    re.IGNORECASE,
)

# Pass 3: trailing comma/dash-separated noise words left after primary phrase removal.
# The preceding separator MUST contain at least one punctuation char (comma, colon,
# dash, pipe, semicolon) — pure whitespace is NOT a valid separator here.  This is
# the key guard that prevents stripping noise words that are part of the actual game
# name: "El Rey De Los Dados" has only a space between "Los" and "Dados" (a natural
# word boundary), whereas "Pandemic - Cooperativo, dados" has "-" and "," (real
# tag delimiters).  The lookahead mirrors the same requirement so that chaining
# (" - Cooperativo, Familiar") is also recognised correctly.
_TRAILING_NOISE = re.compile(
    r'(?:[\-\|,;:]\s*|\s+[\-\|,;:]\s*)(?:' + _NOISE_WORD + r')'
    r'(?=(?:[\-\|,;:]\s*|\s+[\-\|,;:]\s*)(?:' + _NOISE_WORD + r')|\s*$)',
    re.IGNORECASE,
)

# Guards that prevent stripping noise phrases that are actually part of a
# product's subtitle (e.g. "Arkham Horror: El Juego de Cartas" → keep whole).
# Spanish definite article immediately before a noise phrase signals a genuine
# subtitle ("El Juego de Cartas" = "The Card Game") not a generic category tag.
_ARTICLE_PREFIX = re.compile(r'\b(?:el|la|los|las)\s*$', re.IGNORECASE)
_SUBTITLE_TAIL  = re.compile(r'\b(?:el|la|los|las)\s+juego\s+de\s*$', re.IGNORECASE)


# Marketing blurb after a spaced dash ("La Corte del Zar – de cartas, intriga y
# deducción"). Cut it with everything after it, publisher parens included.
#
# Narrow on purpose, for the same reason "juego de cartas" is not noise above:
# a bare "- Juego de Cartas", "- El Juego de Cartas" or "- Juego de Cartas 2da
# Edición" names a different product and must survive. Only tails that read as
# a description match: lowercase "de <word>", a leading "deducción", "un/el
# <adj> juego de ...", or "Juego de <Cartas|Dados|..>" followed by a qualifier.
_DESCRIPTION_TAIL = re.compile(
    r'\s+[–—-]\s+(?:'
    r'(?-i:de)\s+\w+'
    r'|deducci[oó]n\b'
    r'|(?:el|un|una)\s+\w+\s+juego\s+de\b'
    r'|juegos?\s+de\s+(?:cartas|dados|tablero|mesa)\s+(?:'
    r'de\s+(?:deducci[oó]n|faroleo|rapidez|habilidad|intriga|humor|cultura|historia|combate|aventura|estrategia|roles|palabras)'
    r'|infantil|familiar|cooperativo|r[aá]pido|estrat[eé]gico)\b'
    r').*$',
    re.IGNORECASE,
)


def _strip_unless_subtitle(m: re.Match) -> str:
    """Pass-1 callback: keep the noise phrase when introduced by a definite article,
    UNLESS the phrase is 'Juego de Mesa'/'Board Game' — those are always redundant
    category labels in a board-game store, never a distinguishing subtitle.

    The optional article group in _TITLE_NOISE may now be INSIDE the match, so we
    check both the matched text itself and the preceding context for an article."""
    phrase = m.group(0)
    if re.search(r'juego\s+de\s+mesa|board\s+game', phrase, re.IGNORECASE):
        return ' '
    article_in_phrase = bool(re.match(r'[\-\|,;:\s]*\b(?:el|la|los|las)\b', phrase, re.IGNORECASE))
    article_before    = bool(_ARTICLE_PREFIX.search(m.string[:m.start()]))
    return phrase if (article_in_phrase or article_before) else ' '


def _keep_if_subtitle_tail(m: re.Match) -> str:
    """Pass-3 callback: keep a trailing noise word when it follows 'article juego de'."""
    return m.group(0) if _SUBTITLE_TAIL.search(m.string[:m.start()]) else ''


# Lowercase (or ")") running straight into an uppercase letter: where some
# stores glue a second copy of the name or a tag onto the title
# ("Wingspan Juego BaseWingspan", "Heat - LeyendasExpansión").
_GLUE = re.compile(r'(?<=[a-záéíóúñ)])(?=[A-ZÁÉÍÓÚÑ])')
_GLUED_TAG = re.compile(r'(?<=[a-záéíóúñ])(?=(Expansi[oó]n|Producto)\b)')
_PRODUCT_LANG = re.compile(r'\s*\bproducto\s+en\s+\w+', re.IGNORECASE)


def _words(text: str) -> list[str]:
    return re.findall(r'\w+', text.lower())


def _unglue(text: str) -> str:
    """
    Undo glued titles: split a glued tag off, drop "Producto en Inglés", and
    cut a repeated copy of the name ("Monopoly: Cartoon NetworkCartoon Network").

    A tail counts as a copy when it starts with the title's first word or
    repeats its last words. The head must be two words or more, so camel-case
    names ("KareKare", "BrainBox", "DoDeLiDo") are never cut.
    """
    text = _GLUED_TAG.sub(' ', text)
    text = _PRODUCT_LANG.sub('', text)
    for m in _GLUE.finditer(text):
        head, tail = _words(text[:m.start()]), _words(text[m.start():])
        if len(head) < 2 or not tail or len(tail[0]) < 4:
            continue
        if tail[0] == head[0] or head[-len(tail):] == tail:
            return text[:m.start()]
    return text


def clean_title(text: str) -> str:
    """
    Remove generic category noise from a store title for display.
    Preserves original casing and accent marks — only strips noise tokens.

    Handles:
    - Standard suffixes:   "Catan - Juego de Mesa"       → "Catan"
    - Separator variants:  "Carcassonne | Board Game"    → "Carcassonne"
    - No-space joins:      "Pandemic La CuraJuego de Mesa, Cooperativo, Juego de dados"
                                                         → "Pandemic La Cura"
    - Mid-title noise:     "Clank! Juego de Mesa Aventura" → "Clank! Aventura"
    - Subtitle protection: "Arkham Horror: El Juego de Cartas" → unchanged
    """
    if not isinstance(text, str):
        return text
    cleaned = _TITLE_NOISE.sub(_strip_unless_subtitle, _unglue(text))
    # After pass 1: "X – Juego de mesa de cartas, intriga" only exposes its blurb once
    # "Juego de mesa" is gone.
    cleaned = _DESCRIPTION_TAIL.sub('', cleaned)
    cleaned = _TITLE_NOISE_CONCAT.sub('', cleaned)
    cleaned = _TRAILING_NOISE.sub(_keep_if_subtitle_tail, cleaned)
    cleaned = re.sub(r'\s{2,}', ' ', cleaned)
    # Pass 4: drop a trailing orphaned article left behind by noise stripping
    # (e.g. "Catan el [Juego de Mesa stripped]" → "Catan el" → "Catan").
    cleaned = re.sub(r'\s+\b(?:el|la|los|las)\b\s*$', '', cleaned, flags=re.IGNORECASE)
    return cleaned.strip(' –—-|,:;')


# ---------------------------------------------------------------------------
# Price parsing
# ---------------------------------------------------------------------------

# Matches the numeric part of Chilean price strings.
_PRICE_RE = re.compile(r'[\d.,]+')


def parse_price(text) -> float | None:
    """
    Extract a numeric price from a raw price string.
    Returns a float or None if unparseable.

    Handles both Chilean and mixed store formats:
      "$69.990"      -> 69990.0   (dot = thousands separator)
      "$69.990,50"   -> 69990.5   (dot = thousands, comma = decimal)
      "$49,000"      -> 49000.0   (comma = thousands separator, 3-digit group)
      "69,990"       -> 69990.0   (comma = thousands separator, 3-digit group)
    """
    if not isinstance(text, str) or not text.strip():
        return None

    m = _PRICE_RE.search(text)
    if not m:
        return None

    raw = m.group()

    has_dot   = '.' in raw
    has_comma = ',' in raw

    if has_dot and has_comma:
        # Both separators present.
        if raw.rfind('.') > raw.rfind(','):
            # Dot comes last → decimal dot, comma is thousands: "1,234.56"
            raw = raw.replace(',', '')
        else:
            # Comma comes last → decimal comma, dot is thousands: "1.234,56"
            raw = raw.replace('.', '').replace(',', '.')
    elif has_comma and not has_dot:
        # Comma only. If the part after the comma is exactly 3 digits → thousands separator.
        # e.g. "49,000" or "9,990" → thousands. "9,5" → decimal.
        after_comma = raw.rsplit(',', 1)[-1]
        if len(after_comma) == 3:
            raw = raw.replace(',', '')
        else:
            raw = raw.replace(',', '.')
    elif has_dot and not has_comma:
        # Dot only. If the part after the dot is exactly 3 digits → thousands separator.
        # e.g. "9.990" → 9990. "9.5" → 9.5.
        after_dot = raw.rsplit('.', 1)[-1]
        if len(after_dot) == 3:
            raw = raw.replace('.', '')
        # else: leave as-is (decimal dot)

    try:
        return float(raw)
    except ValueError:
        return None
