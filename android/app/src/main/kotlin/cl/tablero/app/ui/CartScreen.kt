package cl.tablero.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cl.tablero.app.Db
import cl.tablero.app.isWebUrl
import cl.tablero.app.money
import cl.tablero.core.data.CartPick
import cl.tablero.core.data.CartPlan
import cl.tablero.core.data.StoreOrder
import cl.tablero.core.data.cartItems
import cl.tablero.core.data.cartPick
import cl.tablero.core.data.cartUnpin
import cl.tablero.core.data.cartRemove
import cl.tablero.core.data.cartSetQty
import cl.tablero.core.data.planCart
import cl.tablero.core.data.shippingCosts
import kotlinx.coroutines.launch

/**
 * The cart: what to buy, split by store the cheapest way once shipping is
 * counted. Shipping is the cost the user noted per store on a game's page.
 * Each game can be moved to another store here; the rest of the plan follows.
 */
@Composable
fun CartScreen(db: Db, open: (GameRef) -> Unit, openStore: (String) -> Unit, notify: (String) -> Unit,
               modifier: Modifier = Modifier) {
    val version by db.version.collectAsState()
    val plan by produceState<CartPlan?>(null, version) {
        value = db.read { planCart(cartItems(it), shippingCosts(it)) }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val setQty: (Long, Int) -> Unit = { id, qty -> scope.launch { db.write { cartSetQty(it, id, qty) } } }
    // A store for one game: an offer's id, or null for wherever the total is lowest.
    val setStore: (Long, Long?) -> Unit = { id, product ->
        scope.launch { db.write { if (product == null) cartUnpin(it, id) else cartPick(it, id, product) } }
    }
    val p = plan
    if (p == null) { Loading(modifier); return }

    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            ScreenTitle("Carrito") {
                if (p.orders.isNotEmpty()) OutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Carrito", listText(p)))
                    notify("Lista copiada.")
                }) { Text("Copiar lista") }
            }
        }
        if (p.orders.isNotEmpty()) item {
            Text("Total ${money(p.total)} en ${p.orders.size} tienda${if (p.orders.size == 1) "" else "s"}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (p.orders.isEmpty() && p.unavailable.isEmpty()) item {
            Empty("El carrito está vacío. En la ficha de un juego, toca el carrito de la tienda que prefieras, " +
                "o \"Al carrito\" para la más barata.")
        }
        items(p.orders, key = { it.store }) { order -> OrderCard(order, open, openStore, notify, setQty, setStore) }
        if (p.unavailable.isNotEmpty()) {
            item { SectionTitle("Sin stock en ninguna tienda") }
            items(p.unavailable, key = { it.gameId }) { item ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, Modifier.weight(1f).clickable { open(GameRef(item.gameId, item.title, item.image)) },
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { scope.launch { db.write { cartRemove(it, item.gameId) } } }) { Text("Quitar") }
                }
            }
        }
        if (p.orders.isNotEmpty()) item {
            Text("Cada juego se compra donde el total con despacho queda más bajo, salvo los que fijaste en una tienda. " +
                "Anota el despacho de cada tienda en la ficha de un juego; sin anotar cuenta como $0.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun OrderCard(order: StoreOrder, open: (GameRef) -> Unit, openStore: (String) -> Unit, notify: (String) -> Unit,
                      setQty: (Long, Int) -> Unit, setStore: (Long, Long?) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(order.store, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { openStore(order.store) })
            order.picks.forEach { PickRow(it, open, notify, setQty, setStore) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Line("Subtotal", money(order.subtotal))
            Line("Despacho", order.shipping?.let { if (it == 0.0) "retiro gratis" else money(it) } ?: "sin anotar")
            Line("Total", money(order.total), bold = true)
        }
    }
}

/** One game: what it costs here, then how many, which store, and the way to its page there. */
@Composable
private fun PickRow(pick: CartPick, open: (GameRef) -> Unit, notify: (String) -> Unit, setQty: (Long, Int) -> Unit,
                    setStore: (Long, Long?) -> Unit) {
    val uri = LocalUriHandler.current
    val item = pick.item
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { open(GameRef(item.gameId, item.title, item.image)) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Thumb(item.image, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${money(pick.offer.price)} c/u" + if (item.picked) " · tienda fijada" else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(money(pick.cost), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { setQty(item.gameId, item.qty - 1) }) { Text(if (item.qty == 1) "Quitar" else "−") }
        Text("${item.qty}", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { setQty(item.gameId, item.qty + 1) }) { Text("+") }
        if (item.offers.size > 1) StoreMenu(pick, setStore)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = { runCatching { uri.openUri(pick.offer.url!!) }.onFailure { notify("No hay navegador para abrir el enlace.") } },
                enabled = isWebUrl(pick.offer.url),
            ) { Text("Ir a la tienda") }
        }
    }
}

/** Move one game to another store, or hand it back to the cheapest total. */
@Composable
private fun StoreMenu(pick: CartPick, setStore: (Long, Long?) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    val item = pick.item
    TextButton(onClick = { shown = true }) { Text("Cambiar tienda") }
    DropdownMenu(expanded = shown, onDismissRequest = { shown = false }) {
        DropdownMenuItem(
            text = { Text("La más barata en total", fontWeight = if (item.picked) FontWeight.Normal else FontWeight.Bold) },
            onClick = { shown = false; setStore(item.gameId, null) },
        )
        item.offers.sortedBy { it.price }.forEach { offer ->
            DropdownMenuItem(
                text = {
                    Text("${offer.store} · ${money(offer.price)}",
                        fontWeight = if (item.picked && offer.productId == item.pin) FontWeight.Bold else FontWeight.Normal)
                },
                onClick = { shown = false; setStore(item.gameId, offer.productId) },
            )
        }
    }
}

@Composable
private fun Line(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        val weight = if (bold) FontWeight.Bold else FontWeight.Normal
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = weight)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = weight)
    }
}

/** The plan as plain text, to paste in a note or a chat. */
private fun listText(plan: CartPlan): String = buildString {
    plan.orders.forEach { order ->
        appendLine("${order.store} — ${money(order.total)}")
        order.picks.forEach { appendLine("  ${it.item.qty} × ${it.item.title} ${money(it.offer.price)}${it.offer.url?.let { u -> "  $u" } ?: ""}") }
    }
    append("Total: ${money(plan.total)}")
}

