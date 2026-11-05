package cl.tablero.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.tablero.app.Db
import cl.tablero.app.ago
import cl.tablero.app.count
import cl.tablero.app.isWebUrl
import cl.tablero.core.data.Filters
import cl.tablero.core.data.SqlRow
import cl.tablero.core.data.stores
import cl.tablero.core.scrape.sites
import coil3.compose.AsyncImage

/**
 * One store: its cover, what we know of it, then its catalogue with the same
 * sorts and filters as Catálogo. Its own [Browse], so a look at one store
 * leaves the main catalogue as it was.
 */
@Composable
fun StoreScreen(db: Db, name: String, open: (GameRef) -> Unit, notify: (String) -> Unit, onBack: () -> Unit,
                modifier: Modifier = Modifier) {
    var browse by rememberSaveable(name, stateSaver = BrowseSaver) {
        mutableStateOf(Browse(filters = Filters(stores = listOf(name))))
    }
    CatalogScreen(db, browse, change = { browse = it }, open, modifier) {
        Cover(name, onBack)
        About(db, name, notify)
    }
}

/**
 * The logo filling the width, cropped where it must, fading into the page at
 * both edges like a profile cover; the name sits on the lower fade.
 */
@Composable
private fun Cover(name: String, onBack: () -> Unit) {
    val logo = STORE_LOGOS[name]
    val page = MaterialTheme.colorScheme.background
    val behind = when {
        logo == null -> MaterialTheme.colorScheme.secondaryContainer
        logo.dark -> Color(0xFF222222)
        else -> Color.White
    }
    Box(Modifier.fillMaxWidth().height(180.dp).background(behind)) {
        if (logo != null) {
            AsyncImage(model = logo.url.takeIf(::isWebUrl), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        } else {
            Text(name.take(1).uppercase(), fontSize = 96.sp, fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.4f),
                modifier = Modifier.align(Alignment.Center))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to page.copy(alpha = 0.7f), 0.3f to Color.Transparent, 0.5f to Color.Transparent, 1f to page)))
        FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
        }
        Text(name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp))
    }
}

/** Products, freshness with its update button, city, and the way to the store's own site. */
@Composable
private fun About(db: Db, name: String, notify: (String) -> Unit) {
    val version by db.version.collectAsState()
    val store by produceState<SqlRow?>(null, version) {
        value = db.read { stores(it) }.firstOrNull { it["name"] == name }
    }
    val canScrape = remember(name) { sites().any { it.name == name } }
    val updates = rememberUpdates()
    val start = rememberStartUpdate()
    val uri = LocalUriHandler.current
    val failed = store?.get("last_success") == 0L

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${count(store?.get("n_products"))} productos · ${count(store?.get("n_in_stock"))} en stock",
            style = MaterialTheme.typography.bodyMedium)
        Text((if (failed) "Falló, " else "Actualizada ") + ago(store?.get("last_scrape")),
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        (store?.get("city") as String?)?.let { Facts(it) }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canScrape) {
                OutlinedButton(onClick = { start(listOf(name)) }, enabled = updates.active == null) {
                    Text(if (updates.active == null) "Actualizar" else "Actualizando…")
                }
            } else {
                Text("solo PC", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
            }
            val site = store?.get("base_url")
            if (isWebUrl(site)) OutlinedButton(onClick = {
                runCatching { uri.openUri(site as String) }.onFailure { notify("No hay navegador para abrir el enlace.") }
            }) { Text("Abrir web") }
        }
    }
}
