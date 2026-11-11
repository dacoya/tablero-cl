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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import cl.tablero.core.data.cartRemove
import cl.tablero.core.data.cartSetQty
import cl.tablero.core.data.planCart
import cl.tablero.core.data.shippingCosts
import kotlinx.coroutines.launch

/**
 * The cart: what to buy, split by store the cheapest way once shipping is
 * counted. Shipping is the cost the user noted per store on a game's page.
 */
@Composable
fun CartScreen(db: Db, open: (GameRef) -> Unit, notify: (String) -> Unit, modifier: Modifier = Modifier) {
    val version by db.version.collectAsState()
    val plan by produceState<CartPlan?>(null, version) {
        value = db.read { planCart(cartItems(it), shippingCosts(it)) }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val setQty: (Long, Int) -> Unit = { id, qty -> scope.launch { db.write { cartSetQty(it, id, qty) } } }
    val p = plan ?: return

    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Carrito", style = MaterialTheme.typography.titleLarge)
                    if (p.orders.isNotEmpty()) Text("Total ${money(p.total)} en ${p.orders.size} tienda${if (p.orders.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
                if (p.orders.isNotEmpty()) OutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Carrito", listText(p)))
                    notify("Lista copiada.")
                }) { Text("Copiar lista") }
            }
        }
        if (p.orders.isEmpty() && p.unavailable.isEmpty()) item {
            Empty("El carrito está vacío. Agrega juegos con \"Al carrito\" en su ficha.")
        }
        items(p.orders, key = { it.store }) { order -> OrderCard(order, open, notify, setQty) }
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
        item {
            Text("Se compra cada juego donde el total con despacho queda más bajo. Anota el despacho de cada tienda " +
                "en la ficha de un juego; sin anotar cuenta como $0.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun OrderCard(order: StoreOrder, open: (GameRef) -> Unit, notify: (String) -> Unit, setQty: (Long, Int) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(order.store, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            order.picks.forEach { PickRow(it, open, notify, setQty) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Line("Subtotal", money(order.subtotal))
            Line("Despacho", order.shipping?.let { if (it == 0.0) "retiro gratis" else money(it) } ?: "sin anotar")
            Line("Total", money(order.total), bold = true)
        }
    }
}

@Composable
private fun PickRow(pick: CartPick, open: (GameRef) -> Unit, notify: (String) -> Unit, setQty: (Long, Int) -> Unit) {
    val uri = LocalUriHandler.current
    val item = pick.item
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Thumb(item.image, size = 40.dp)
        Column(Modifier.weight(1f).clickable { open(GameRef(item.gameId, item.title, item.image)) }) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${money(pick.offer.price)} c/u · ${money(pick.cost)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { setQty(item.gameId, item.qty - 1) }) { Text("−") }
        Text("${item.qty}", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { setQty(item.gameId, item.qty + 1) }) { Text("+") }
        TextButton(
            onClick = { runCatching { uri.openUri(pick.offer.url!!) }.onFailure { notify("No hay navegador para abrir el enlace.") } },
            enabled = isWebUrl(pick.offer.url),
        ) { Text("Ir") }
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

