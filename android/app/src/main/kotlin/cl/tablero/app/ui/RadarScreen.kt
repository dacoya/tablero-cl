package cl.tablero.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
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
 * Everything that wants a look: the games you follow as cards (a red "!" on
 * the ones that moved), your collection, then what moved in the rest of the
 * market as a list.
 * "Ya los vi" moves the cursor to now: the "!" marks and the list start over.
 */
@Composable
fun RadarScreen(db: Db, open: (GameRef) -> Unit, notify: (String) -> Unit, modifier: Modifier = Modifier) {
    val version by db.version.collectAsState()
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var showOwned by rememberSaveable { mutableStateOf(false) }
    val radar by produceState<Radar?>(null, version) {
        value = db.read { conn ->
            Radar(getCursor(conn), sinceLastReview(conn, limit = null), watchEntries(conn), ownedEntries(conn))
        }
    }
    val markReviewed: () -> Unit = {
        scope.launch { db.write { setCursor(it) }; kind = null; notify("Radar al día. Verás lo que cambie desde ahora.") }
    }

    val r = radar
    if (r == null) { Loading(modifier); return }
    val watchedIds = r.watched.map { it["game_id"] }.toSet()
    val (followed, market) = r.changes.partition { it["game_id"] in watchedIds }

    LazyColumn(modifier) {
        item { ScreenTitle("Mi radar") }
        if (r.since == null) item { StartReview(markReviewed) }
        else item { ReviewHeader(r, followed.map { it["game_id"] }.toSet().size, markReviewed) }

        item { SectionTitle("Sigues") }
        if (r.watched.isEmpty()) {
            item { Empty("Abre un juego y toca \"Seguir\" para verlo aquí. Con un precio objetivo, te avisamos cuando lo alcance.") }
        }
        // Shop-window cards, two a row, the ones that moved first with a red "!".
        val changed = followed.map { it["game_id"] }.toSet()
        val watched = r.watched.sortedBy { it["game_id"] !in changed }
        items(watched.chunked(2), key = { pair -> "w${pair.first()["game_id"]}" }) { pair ->
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { WatchCard(it, changed = it["game_id"] in changed, open, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

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

/** A watched game as a shop card: its cheapest live price, the target, and a red "!" when it moved. */
@Composable
private fun WatchCard(row: SqlRow, changed: Boolean, open: (GameRef) -> Unit, modifier: Modifier) {
    val hit = row["hit"] == true
    val stores = row["n_stores"] as Long?
    val facts = when {
        row["min_price"] == null -> "ninguna tienda"
        row["in_stock"] == 0L -> "agotado"
        else -> "$stores tienda${if (stores == 1L) "" else "s"}"
    }
    Card(
        onClick = { open(gameRef(row)) }, modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box {
            Photo(row["image_url"], Modifier.fillMaxWidth().aspectRatio(1f))
            if (changed) Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("!", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Black)
            }
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(facts.uppercase(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(row["title"] as String, style = MaterialTheme.typography.bodyMedium, maxLines = 2, minLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text(money(row["min_price"]), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = if (hit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            row["target"]?.let {
                Text("objetivo ${money(it)}" + if (hit) " ✓" else "", style = MaterialTheme.typography.labelSmall,
                    color = if (hit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
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

/** What changed since the last look, and the way to say it has been seen. */
@Composable
private fun ReviewHeader(r: Radar, followed: Int, markReviewed: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (r.changes.isEmpty()) "Sin cambios" else "${count(r.changes.size)} cambios",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("desde el ${date(r.since)}" + if (followed > 0) " · $followed en juegos que sigues" else "",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (r.changes.isNotEmpty()) TextButton(onClick = markReviewed) { Text("Ya los vi") }
        }
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
