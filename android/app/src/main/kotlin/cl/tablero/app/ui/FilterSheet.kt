package cl.tablero.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cl.tablero.app.Db
import cl.tablero.app.count
import cl.tablero.core.data.bggMechanics
import cl.tablero.core.data.Filters
import cl.tablero.core.data.countProducts
import kotlinx.coroutines.delay

/** The product kinds, as the filter and Home's tiles name them. */
val KINDS = listOf(
    "game" to "Juegos", "expansion" to "Expansiones", "tcg" to "TCG",
    "puzzle" to "Puzzles", "accessory" to "Accesorios",
)

/** What Home's tiles and the filter offer of BGG's categories: bgg column -> label. */
val BGG_USES = listOf(
    "familygames_rank" to "Familiares", "partygames_rank" to "Party", "strategygames_rank" to "Estrategia",
    "thematic_rank" to "Temáticos", "abstracts_rank" to "Abstractos", "childrensgames_rank" to "Infantiles",
)

/** "Top 500 BGG": how far down the overall ranking the filter reaches. */
const val BGG_TOP = 500

private val PLAYERS = 1..6
private val PLAY_TIMES = listOf(30, 60, 120)
/** Mechanics shown before "Ver todas": the full list is too long for the sheet. */
private const val TOP_MECHANICS = 12

private val DISCOUNTS = listOf(null, 10.0, 25.0, 50.0, 70.0)
private val NOVELTY = listOf(null to "Todos", "new" to "Nuevos", "restock" to "Repuestos")

private fun <T> List<T>?.toggle(item: T): List<T>? =
    (if (this != null && item in this) this - item else orEmpty() + item).takeIf { it.isNotEmpty() }

/**
 * Every filter at once, applied together: the count on the button is what
 * the catalogue will show, so a combination that leaves nothing is seen
 * before it is applied, not after.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(db: Db, initial: Filters, onDismiss: () -> Unit, apply: (Filters) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    val total by produceState<Long?>(null, draft) {
        delay(150) // typing a price: one count per pause, not per digit
        value = db.read { countProducts(it, draft) }
    }

    val mechanics by produceState(emptyList<String>()) { value = db.read { bggMechanics(it) } }
    var allMechanics by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("Filtros", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Group("Tipo") {
                KINDS.forEach { (key, label) ->
                    FilterChip(draft.kinds?.contains(key) == true, { draft = draft.copy(kinds = draft.kinds.toggle(key)) },
                        { Text(label) })
                }
            }
            Group("Para jugar") {
                BGG_USES.forEach { (column, label) ->
                    FilterChip(draft.bggCategory == column,
                        { draft = draft.copy(bggCategory = column.takeIf { draft.bggCategory != it }) }, { Text(label) })
                }
                FilterChip(draft.bggTop != null, { draft = draft.copy(bggTop = if (draft.bggTop == null) BGG_TOP else null) },
                    { Text("Top $BGG_TOP BGG") })
            }
            Group("Jugadores") {
                PLAYERS.forEach { n ->
                    FilterChip(draft.players == n, { draft = draft.copy(players = n.takeIf { draft.players != it }) }, { Text("$n") })
                }
            }
            Group("Duración") {
                PLAY_TIMES.forEach { m ->
                    FilterChip(draft.maxPlayTime == m, { draft = draft.copy(maxPlayTime = m.takeIf { draft.maxPlayTime != it }) },
                        { Text("Hasta $m min") })
                }
            }
            if (mechanics.isNotEmpty()) Group("Mecánica") {
                val shown = if (allMechanics) mechanics else (mechanics.take(TOP_MECHANICS) + listOfNotNull(draft.mechanic)).distinct()
                shown.forEach { m ->
                    FilterChip(draft.mechanic == m, { draft = draft.copy(mechanic = m.takeIf { draft.mechanic != it }) }, { Text(m) })
                }
                if (mechanics.size > TOP_MECHANICS) {
                    FilterChip(allMechanics, { allMechanics = !allMechanics }, { Text(if (allMechanics) "Ver menos" else "Ver todas") })
                }
            }
            Group("Precio") { PriceRange(draft) { draft = it } }
            Group("Descuento mínimo") {
                DISCOUNTS.forEach { d ->
                    FilterChip(draft.minDiscount == d, { draft = draft.copy(minDiscount = d) },
                        { Text(d?.let { "${it.toInt()}% o más" } ?: "Cualquiera") })
                }
            }
            Group("Disponibilidad") {
                FilterChip(draft.inStockOnly, { draft = draft.copy(inStockOnly = !draft.inStockOnly) }, { Text("Solo disponibles") })
                FilterChip(draft.onSale, { draft = draft.copy(onSale = !draft.onSale) }, { Text("Solo ofertas") })
            }
            Group("Tu colección") {
                FilterChip(draft.hideOwned, { draft = draft.copy(hideOwned = !draft.hideOwned) }, { Text("Ocultar los que tengo") })
                FilterChip(draft.ownedExpansions, { draft = draft.copy(ownedExpansions = !draft.ownedExpansions) },
                    { Text("Expansiones de mis juegos") })
            }
            Group("Novedad") {
                NOVELTY.forEach { (key, label) ->
                    FilterChip(draft.flag == key, { draft = draft.copy(flag = key) }, { Text(label) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // A store page keeps its store: that is picked in Tiendas, not here.
            OutlinedButton(onClick = { draft = Filters(stores = initial.stores) }) { Text("Limpiar") }
            Button(onClick = { apply(draft) }, enabled = total != 0L, modifier = Modifier.weight(1f)) {
                Text(when (val n = total) { null -> "Contando…"; 0L -> "Sin resultados"; else -> "Ver ${count(n)} productos" })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** Two whole-peso fields; an empty one means no bound on that side. */
@Composable
private fun PriceRange(f: Filters, change: (Filters) -> Unit) {
    @Composable
    fun Bound(label: String, value: Double?, set: (Double?) -> Unit, modifier: Modifier) = OutlinedTextField(
        value = value?.toLong()?.toString() ?: "", singleLine = true, label = { Text(label) }, prefix = { Text("$") },
        onValueChange = { v -> set(v.filter { it.isDigit() }.take(9).toDoubleOrNull()) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Bound("Mínimo", f.minPrice, { change(f.copy(minPrice = it)) }, Modifier.weight(1f))
        Bound("Máximo", f.maxPrice, { change(f.copy(maxPrice = it)) }, Modifier.weight(1f))
    }
}
