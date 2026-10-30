package cl.tablero.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cl.tablero.app.Db
import cl.tablero.app.Prefs
import cl.tablero.app.count
import cl.tablero.core.data.Filters
import cl.tablero.core.data.SqlRow
import cl.tablero.core.data.browseRows
import cl.tablero.core.data.countProductsEach
import cl.tablero.core.data.searchGames
import kotlinx.coroutines.delay

/** A tile on Home: a name, a colour and the catalogue view it opens. */
private class Category(val label: String, val color: Color, val browse: Browse)

private val CATEGORIES = listOf(
    Category("Ofertas", Sale, Browse(filters = Filters(onSale = true))),
    Category("Novedades", Color(0xFF6A3FC0), Browse(filters = Filters(flag = "new"))),
    Category("Repuestos", Color(0xFF00897B), Browse(filters = Filters(flag = "restock"))),
) + KINDS.zip(listOf(0xFFD9480F, 0xFF1E6FD9, 0xFF3949AB, 0xFFC77700, 0xFF5D6B2E)) { (kind, label), color ->
    Category(label, Color(color), Browse("price", Filters(kinds = listOf(kind))))
} + BGG_USES.take(3).map { (column, label) ->
    Category(label, Color(0xFF00695C), Browse(BEST_DEAL, Filters(kinds = listOf("game"), bggCategory = column, onePerGame = true)))
} + Category("Top $BGG_TOP BGG", Color(0xFF1565C0), Browse(BEST_DEAL, Filters(kinds = listOf("game"), bggTop = BGG_TOP, onePerGame = true)))

/** A carousel on Home and the full view behind its "Ver todo". */
private class Shelf(val title: String, val browse: Browse)

// "Mejor oferta" is the most pesos saved (list price minus offer price): 20% off
// a $200.000 game beats 80% off a $10.000 one. Percentages are "Mayores descuentos".
private val SHELVES = listOf(
    Shelf("Expansiones de tus juegos", Browse("price", Filters(ownedExpansions = true))),
    // Board games only: booster displays carry big peso discounts and would fill it.
    Shelf("Mejores ofertas", Browse(BEST_DEAL, Filters(onSale = true, inStockOnly = true, kinds = listOf("game", "expansion"), hideOwned = true))),
    Shelf("Preventa", Browse("price", Filters(preorder = true, hideOwned = true))),
    Shelf("Novedades", Browse(BEST_DEAL, Filters(flag = "new", hideOwned = true))),
    Shelf("Volvieron al stock", Browse(BEST_DEAL, Filters(flag = "restock", hideOwned = true))),
    Shelf("Mayores descuentos", Browse("discount", Filters(onSale = true, inStockOnly = true, hideOwned = true))),
)

private const val SHELF_SIZE = 12

/**
 * The shop front: search on top; under it, while nothing is typed, the
 * categories and a few shelves, each opening the catalogue already filtered.
 */
@Composable
fun HomeScreen(db: Db, prefs: Prefs, open: (GameRef) -> Unit, show: (Browse) -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier) {
        Hero(query) { query = it }
        if (query.isBlank()) Feed(db, open, show) else Results(db, prefs, query, open)
    }
}

@Composable
private fun Hero(query: String, change: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Brush.linearGradient(Brand)).padding(16.dp)) {
        Text("tablero", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black,
            color = Color.White)
        Text("Juegos de mesa al mejor precio en Chile", style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f))
        SearchField(query, change)
    }
}

@Composable
private fun SearchField(query: String, change: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val container = MaterialTheme.colorScheme.surfaceContainerLowest
    TextField(
        value = query, onValueChange = change, singleLine = true,
        placeholder = { Text("Buscar juegos, expansiones…") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { change("") }) {
                Icon(Icons.Filled.Clear, contentDescription = "Borrar búsqueda")
            }
        },
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container, unfocusedContainerColor = container,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
}

@Composable
private fun Feed(db: Db, open: (GameRef) -> Unit, show: (Browse) -> Unit) {
    val updates by db.updates.collectAsState()
    val counts by produceState(emptyList<Long>(), updates) {
        value = db.read { conn -> countProductsEach(conn, CATEGORIES.map { it.browse.filters }) }
    }
    // Each shelf shows as soon as its own query is back, not when the slowest is.
    val shelves by produceState(List<List<SqlRow>?>(SHELVES.size) { null }, updates) {
        SHELVES.forEachIndexed { i, shelf ->
            val rows = db.read { conn -> browseRows(conn, shelf.browse.sort, shelf.browse.filters, limit = SHELF_SIZE) }
            value = value.toMutableList().apply { set(i, rows) }
        }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            SectionTitle("Categorías")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // A tile that would open an empty catalogue is left out once the counts are in.
                items(CATEGORIES.indices.filter { counts.getOrNull(it) != 0L }) { i ->
                    Tile(CATEGORIES[i], counts.getOrNull(i)) { show(CATEGORIES[i].browse) }
                }
            }
        }
        if (shelves.all { it != null && it.isEmpty() }) item {
            Empty("Todavía no hay productos. Actualiza las tiendas o importa una base en Ajustes.")
        }
        SHELVES.zip(shelves).forEach { (shelf, rows) -> shelf(shelf, rows.orEmpty(), open, show) }
    }
}

private fun LazyListScope.shelf(shelf: Shelf, rows: List<SqlRow>, open: (GameRef) -> Unit, show: (Browse) -> Unit) {
    if (rows.isEmpty()) return
    item(key = shelf.title) {
        SectionTitle(shelf.title) { show(shelf.browse) }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(rows, key = { it["product_id"] as Long }) { ProductCard(it, open, Modifier.width(156.dp)) }
        }
    }
}

@Composable
private fun Tile(c: Category, n: Long?, onClick: () -> Unit) {
    Surface(onClick = onClick, color = c.color, contentColor = Color.White, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.width(118.dp).height(76.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(c.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(n?.let { count(it) } ?: "", style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun Results(db: Db, prefs: Prefs, query: String, open: (GameRef) -> Unit) {
    var accessories by rememberSaveable { mutableStateOf(prefs.accessories.value) }
    val version by db.version.collectAsState()
    // Results carry the query they answer. While a new one is pending the
    // previous results stay up, and "Sin resultados" is only ever said about
    // the query actually on screen -- not flashed during the typing pause.
    val result by produceState<Pair<String, List<SqlRow>>?>(null, query, accessories, version) {
        delay(200) // typing pause: one query per word, not per keystroke
        value = query to db.read { searchGames(it, query, includeAccessories = accessories) }
    }
    val rows = result?.second.orEmpty()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = accessories, onClick = { accessories = !accessories; prefs.accessories.value = accessories },
                    label = { Text("Incluir accesorios") })
                if (result?.first == query) Text("${count(rows.size)} juego${if (rows.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (result?.first == query && rows.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Empty("Sin resultados para '$query'. Se toleran errores de tipeo, prueba con otra palabra.")
        }
        items(rows, key = { it["game_id"] as Long }) { GameCard(it, open) }
    }
}
