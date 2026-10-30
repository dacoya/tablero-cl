package cl.tablero.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cl.tablero.app.Db
import cl.tablero.app.count
import cl.tablero.app.date
import cl.tablero.app.money
import cl.tablero.core.data.CHANGE_KINDS
import cl.tablero.core.data.SqlRow
import cl.tablero.core.data.getCursor
import cl.tablero.core.data.ownedEntries
import cl.tablero.core.data.setCursor
import cl.tablero.core.data.sinceLastReview
import cl.tablero.core.data.watchEntries
import kotlinx.coroutines.launch

// changes.CHANGE_LABELS
private val LABELS = mapOf(
    "nuevo" to "nuevo", "restock" to "volvió a stock", "agotado" to "se agotó",
    "oferta" to "entró en oferta", "fin_oferta" to "terminó oferta",
    "baja" to "bajó de precio", "alza" to "subió de precio",
)

private class Radar(val since: Long?, val changes: List<SqlRow>, val watched: List<SqlRow>, val owned: List<SqlRow>)

/**
 * Everything that wants a look, in one list: what moved in the games you follow,
 * those games, your collection, then what moved in the rest of the market.
 * "Marcar como revisado" moves the cursor to now.
 */
@Composable
fun RadarScreen(db: Db, open: (GameRef) -> Unit, modifier: Modifier = Modifier) {
    val version by db.version.collectAsState()
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var showOwned by rememberSaveable { mutableStateOf(false) }
    val radar by produceState<Radar?>(null, version) {
        value = db.read { conn ->
            Radar(getCursor(conn), sinceLastReview(conn, limit = null), watchEntries(conn), ownedEntries(conn))
        }
    }
    val markReviewed: () -> Unit = { scope.launch { db.write { setCursor(it) }; kind = null } }

    val r = radar
    if (r == null) { Empty("Cargando…"); return }
    val watchedIds = r.watched.map { it["game_id"] }.toSet()
    val (followed, market) = r.changes.partition { it["game_id"] in watchedIds }

    LazyColumn(modifier) {
        if (r.since == null) item { StartReview(markReviewed) }
        else item { ReviewHeader(r, markReviewed) }

        if (followed.isNotEmpty()) {
            item { SectionTitle("Cambios en lo que sigues") }
            items(followed, key = { "f${it["product_id"]}" }) { ChangeRow(it, open) }
        }

        item { SectionTitle("Sigues") }
        if (r.watched.isEmpty()) {
            item { Empty("Abre un juego y toca \"Seguir\" para verlo aquí. Con un precio objetivo, te avisamos cuando lo alcance.") }
        }
        items(r.watched, key = { "w${it["game_id"]}" }) { WatchRow(it, open) }

        owned(r.owned, showOwned, toggle = { showOwned = !showOwned }, open)

        if (r.since != null) {
            item {
                SectionTitle("Resto del mercado")
                KindChips(market, kind) { kind = it }
            }
            val shown = market.filter { kind == null || it["change"] == kind }
            if (shown.isEmpty()) item { Empty("Nada nuevo desde tu última revisión.") }
            items(shown, key = { "m${it["product_id"]}" }) { ChangeRow(it, open) }
        }
    }
}

/** The collection, folded: it does not change often and can be long. */
private fun LazyListScope.owned(rows: List<SqlRow>, shown: Boolean, toggle: () -> Unit, open: (GameRef) -> Unit) {
    if (rows.isEmpty()) return
    item {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tu colección (${count(rows.size)})", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = toggle) { Text(if (shown) "Ocultar" else "Ver") }
        }
    }
    if (shown) items(rows, key = { "o${it["game_id"]}" }) { row ->
        ListItem(
            leadingContent = { Thumb(row["image_url"]) },
            headlineContent = { Title(row["title"] as String) },
            trailingContent = { Text(money(row["min_price"])) },
            modifier = Modifier.clickable { open(gameRef(row)) },
        )
    }
}

private fun gameRef(row: SqlRow) = GameRef(row["game_id"] as Long, row["title"] as String, row["image_url"] as String?)

/** A watched game, its cheapest live price, and whether the target is met. */
@Composable
private fun WatchRow(row: SqlRow, open: (GameRef) -> Unit) {
    val hit = row["hit"] == true
    val facts = listOfNotNull(
        row["target"]?.let { "objetivo ${money(it)}" },
        when {
            row["min_price"] == null -> "ninguna tienda lo tiene ahora"
            row["in_stock"] == 0L -> "agotado"
            else -> "${row["n_stores"]} tienda${if (row["n_stores"] == 1L) "" else "s"}"
        },
    ).joinToString(" · ")
    ListItem(
        leadingContent = { Thumb(row["image_url"]) },
        overlineContent = if (hit) ({ Text("Precio objetivo alcanzado", color = MaterialTheme.colorScheme.primary) }) else null,
        headlineContent = { Title(row["title"] as String) },
        supportingContent = { Facts(facts) },
        trailingContent = {
            Text(money(row["min_price"]), fontWeight = if (hit) FontWeight.Bold else FontWeight.Normal)
        },
        modifier = Modifier.clickable { open(gameRef(row)) },
    )
}

/** No cursor yet: there is no "since" to compare against until one is set. */
@Composable
private fun StartReview(start: () -> Unit) {
    Column {
        Empty("Marca un punto de partida y aquí verás todo lo que cambie desde entonces: " +
            "precios, ofertas, stock y productos nuevos.")
        Button(onClick = start, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Empezar desde ahora") }
    }
}

@Composable
private fun ReviewHeader(r: Radar, markReviewed: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${count(r.changes.size)} cambios desde el ${date(r.since)}",
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = markReviewed) { Text("Marcar como revisado") }
    }
}

/** One chip per kind present, with its count; tapping the selected one clears it. */
@Composable
private fun KindChips(rows: List<SqlRow>, kind: String?, pick: (String?) -> Unit) {
    val counts = rows.groupingBy { it["change"] as String }.eachCount()
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(kind == null, { pick(null) }, { Text("Todos") })
        CHANGE_KINDS.filter { it in counts }.forEach { k ->
            FilterChip(kind == k, { pick(if (kind == k) null else k) }, { Text("${LABELS[k]} (${counts[k]})") })
        }
    }
}

@Composable
private fun ChangeRow(row: SqlRow, open: (GameRef) -> Unit) {
    val gameId = row["game_id"] as Long?
    val old = row["old_price"]
    ListItem(
        leadingContent = { Thumb(row["image_url"]) },
        overlineContent = { Text(LABELS[row["change"]] ?: "") },
        headlineContent = { Title(row["title"] as String) },
        supportingContent = { Facts(row["store"] as String) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(money(row["new_price"]))
                if (old != null && old != row["new_price"]) Facts("antes ${money(old)}")
            }
        },
        modifier = Modifier.clickable(enabled = gameId != null) {
            open(GameRef(gameId!!, row["title"] as String, row["image_url"] as String?))
        },
    )
}
