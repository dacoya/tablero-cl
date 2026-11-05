package cl.tablero.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cl.tablero.app.Db
import cl.tablero.app.count
import cl.tablero.core.data.BGG_RANK_COLUMNS
import cl.tablero.core.data.Filters
import cl.tablero.core.data.SqlRow
import cl.tablero.core.data.browse
import cl.tablero.core.data.browseRows
import cl.tablero.core.data.withCheapest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val PAGE = 50
private val GUTTER = 12.dp

/** "Mejor oferta": most pesos saved, list price minus offer price, biggest first. */
const val BEST_DEAL = "savings"

// The sorts worth a tap on a phone.
private val SORT_LABELS = linkedMapOf(
    BEST_DEAL to "Mejor oferta",
    "discount" to "Mayor descuento",
    "price" to "Más barato",
    "price_desc" to "Más caro",
    "sale_desc" to "Oferta más cara",
    "title" to "Por nombre",
)

/** What the catalogue shows: an order and the filters. Home's tiles and shelves open it preset. */
data class Browse(val sort: String = BEST_DEAL, val filters: Filters = Filters())

@Suppress("UNCHECKED_CAST")
val BrowseSaver = listSaver<Browse, Any?>(
    save = { b ->
        with(b.filters) {
            listOf(b.sort, stores?.let { ArrayList(it) }, kinds?.let { ArrayList(it) }, inStockOnly, onSale,
                minPrice, maxPrice, minDiscount, flag, preorder, bggCategory, bggTop, hideOwned, ownedExpansions, onePerGame,
                players, maxPlayTime, mechanic)
        }
    },
    restore = {
        Browse(it[0] as String, Filters(
            stores = it[1] as List<String>?, kinds = it[2] as List<String>?, inStockOnly = it[3] as Boolean,
            onSale = it[4] as Boolean, minPrice = it[5] as Double?, maxPrice = it[6] as Double?,
            minDiscount = it[7] as Double?, flag = it[8] as String?, preorder = it[9] as Boolean,
            bggCategory = it[10] as String?, bggTop = it[11] as Int?, hideOwned = it[12] as Boolean,
            ownedExpansions = it[13] as Boolean, onePerGame = it[14] as Boolean,
            players = it[15] as Int?, maxPlayTime = it[16] as Int?, mechanic = it[17] as String?,
        ))
    },
)

/** [Browse] as JSON for Prefs.catalog. */
fun Browse.toJson(): String = with(filters) {
    JSONObject().put("sort", sort).put("kinds", kinds?.let { JSONArray(it) })
        .put("inStockOnly", inStockOnly).put("onSale", onSale).put("minPrice", minPrice).put("maxPrice", maxPrice)
        .put("minDiscount", minDiscount).put("flag", flag).put("preorder", preorder)
        .put("bggCategory", bggCategory).put("bggTop", bggTop).put("hideOwned", hideOwned)
        .put("ownedExpansions", ownedExpansions).put("onePerGame", onePerGame)
        .put("players", players).put("maxPlayTime", maxPlayTime).put("mechanic", mechanic).toString()
}

/**
 * The [Browse] saved by [toJson], or null when nothing usable was saved. Its
 * stores are dropped: a store's catalogue opens from Tiendas now, and the
 * sheet has no way left to clear an old pick.
 */
fun browseFromJson(json: String): Browse? = try {
    val o = JSONObject(json)
    fun list(key: String) = o.optJSONArray(key)?.let { a -> List(a.length()) { a.getString(it) } }
    fun double(key: String) = if (o.isNull(key)) null else o.getDouble(key)
    Browse(o.getString("sort").takeIf { it in SORT_LABELS } ?: BEST_DEAL, Filters(
        kinds = list("kinds"), inStockOnly = o.optBoolean("inStockOnly"),
        onSale = o.optBoolean("onSale"), minPrice = double("minPrice"), maxPrice = double("maxPrice"),
        minDiscount = double("minDiscount"), flag = if (o.isNull("flag")) null else o.getString("flag"),
        preorder = o.optBoolean("preorder"),
        // Checked here: the category becomes part of the SQL, and an unknown one is refused there.
        bggCategory = if (o.isNull("bggCategory")) null else o.getString("bggCategory").takeIf { it in BGG_RANK_COLUMNS },
        bggTop = if (o.isNull("bggTop")) null else o.getInt("bggTop"),
        hideOwned = o.optBoolean("hideOwned"), ownedExpansions = o.optBoolean("ownedExpansions"),
        onePerGame = o.optBoolean("onePerGame"),
        players = if (o.isNull("players")) null else o.getInt("players"),
        maxPlayTime = if (o.isNull("maxPlayTime")) null else o.getInt("maxPlayTime"),
        mechanic = if (o.isNull("mechanic")) null else o.getString("mechanic"),
    ))
} catch (e: JSONException) {
    null
}

/** How many filters the sheet holds beyond the two quick chips: the number on its button. */
private fun Filters.extra(): Int = listOf(
    !kinds.isNullOrEmpty(), minPrice != null || maxPrice != null,
    minDiscount != null, flag != null, preorder, bggCategory != null, bggTop != null, hideOwned, ownedExpansions,
    players != null, maxPlayTime != null, mechanic != null,
).count { it }

/**
 * Every product under the chosen filters, as a photo grid. Everything, not a
 * capped top N: pages load as the grid nears its end, and the total is always
 * shown so nothing is silently missing. A [header] (a store's cover) scrolls
 * away with the grid, the controls under it; without one the controls stay put.
 */
@Composable
fun CatalogScreen(db: Db, browse: Browse, change: (Browse) -> Unit, open: (GameRef) -> Unit,
                  modifier: Modifier = Modifier, header: (@Composable () -> Unit)? = null) {
    val f = browse.filters
    val grid = rememberLazyGridState()
    val paged = rememberPaged(db, f, browse.sort, grid)
    var sheet by rememberSaveable { mutableStateOf(false) }
    val controls = @Composable {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(f.extra() > 0, { sheet = true },
                { Text(if (f.extra() > 0) "Filtros · ${f.extra()}" else "Filtros ▾") })
            Menu(SORT_LABELS.getValue(browse.sort), SORT_LABELS.toList()) { change(browse.copy(sort = it)) }
            FilterChip(f.onePerGame, { change(browse.copy(filters = f.copy(onePerGame = !f.onePerGame))) },
                { Text("Por juego") })
            FilterChip(f.onSale, { change(browse.copy(filters = f.copy(onSale = !f.onSale))) }, { Text("Ofertas") })
            FilterChip(f.inStockOnly, { change(browse.copy(filters = f.copy(inStockOnly = !f.inStockOnly))) },
                { Text("Disponibles") })
        }
        Text(
            paged.total?.let { n ->
                val noun = if (f.onePerGame) "juego" else "producto"
                "${count(n)} $noun${if (n == 1L) "" else "s"}" + if (f.onePerGame) ", el más barato de cada uno" else ""
            } ?: "Cargando…",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    Column(modifier) {
        if (header == null) controls()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp), state = grid,
            contentPadding = PaddingValues(start = GUTTER, end = GUTTER, bottom = GUTTER, top = if (header == null) GUTTER else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(GUTTER), verticalArrangement = Arrangement.spacedBy(GUTTER),
        ) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fullBleed(GUTTER)) { header(); controls() }
            }
            if (paged.total == 0L) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Empty("Nada con estos filtros.")
                    // A store page keeps its store.
                    Button(onClick = { change(Browse(browse.sort, Filters(stores = f.stores))) }) { Text("Quitar filtros") }
                }
            }
            items(paged.rows, key = { it["product_id"] as Long }) { ProductCard(it, open) }
        }
    }

    if (sheet) FilterSheet(db, f, onDismiss = { sheet = false }) { change(browse.copy(filters = it)); sheet = false }
}

private class Paged(val rows: List<SqlRow>, val total: Long?)

/**
 * The rows loaded so far for these filters, a page more each time the grid
 * nears its end. Keyed on finished updates, not on db.version: an update
 * rewrites a store every few seconds, and restarting the grid each time would
 * yank the scroll position away mid-read.
 */
@Composable
private fun rememberPaged(db: Db, filters: Filters, sort: String, grid: LazyGridState): Paged {
    val updates by db.updates.collectAsState()
    var rows by remember(filters, sort, updates) { mutableStateOf(emptyList<SqlRow>()) }
    var total by remember(filters, sort, updates) { mutableStateOf<Long?>(null) }
    var exhausted by remember(filters, sort, updates) { mutableStateOf(false) }

    LaunchedEffect(filters, sort, updates) {
        grid.scrollToItem(0)
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
            if (!exhausted && last >= rows.size - 10) {
                // The total only changes with the filters: counted with the first page, not every page.
                val (page, n) = db.read {
                    val (found, n) = if (total == null) browse(it, sort, filters, limit = PAGE)
                        else browseRows(it, sort, filters, limit = PAGE, offset = rows.size) to total
                    withCheapest(it, found) to n
                }
                rows = rows + page
                total = n
                exhausted = page.size < PAGE
            }
        }
    }
    return Paged(rows, total)
}

/** Widens an item by [gutter] on both sides, back over the grid's content padding. */
private fun Modifier.fullBleed(gutter: Dp) = layout { measurable, constraints ->
    val extra = gutter.roundToPx()
    val placeable = measurable.measure(constraints.copy(
        minWidth = constraints.minWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

@Composable
private fun Menu(label: String, options: List<Pair<String, String>>, pick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(false, { expanded = true }, { Text("$label ▾") })
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem({ Text(text) }, { pick(key); expanded = false })
            }
        }
    }
}
