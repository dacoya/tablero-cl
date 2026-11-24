package cl.tablero.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.Data
import androidx.work.WorkInfo
import cl.tablero.app.Db
import cl.tablero.app.UpdateWorker
import cl.tablero.app.ago
import cl.tablero.app.count
import cl.tablero.app.isWebUrl
import cl.tablero.core.data.CATALOG_CURSOR
import cl.tablero.core.data.SqlRow
import cl.tablero.core.data.favSet
import cl.tablero.core.data.favStores
import cl.tablero.core.data.getCursor
import cl.tablero.core.data.stores
import cl.tablero.core.scrape.registry
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * The stores as a grid of cards, favourites first, and the catalogue sync:
 * one download of what the daily scrape workflow published. A store's own
 * page has the button to scrape it from the phone.
 */
@Composable
fun StoresScreen(db: Db, onBack: () -> Unit, onStore: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val list = rememberStoreRows(db)
    val scope = rememberCoroutineScope()
    val updates = rememberUpdates()
    val start = rememberStartUpdate()

    Column(modifier) {
        ScreenTitle("Tiendas", onBack) {
            if (updates.active == null) Button(onClick = { start(null) }) { Text("Sincronizar") }
            else OutlinedButton(onClick = { updates.stop(context) }) { Text("Detener") }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(GUTTER),
            horizontalArrangement = Arrangement.spacedBy(GUTTER), verticalArrangement = Arrangement.spacedBy(GUTTER),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Facts(list.catalog?.let { "Catálogo con datos de ${ago(it)}." } ?: "Aún sin sincronizar.")
                    updates.shown?.let { UpdateStatus(it) }
                }
            }
            items(list.rows, key = { it["name"] as String }) { store ->
                val name = store["name"] as String
                val favourite = name in list.favourites
                StoreCard(store, favourite, open = onStore,
                    toggleFavourite = { scope.launch { db.write { favSet(it, name, !favourite) } } })
            }
        }
    }
}

private class StoreList(
    val rows: List<SqlRow> = emptyList(),
    val favourites: Set<String> = emptySet(),
    /** Newest scrape in the synced catalogue, unix seconds; null before the first sync. */
    val catalog: Long? = null,
)

/**
 * Every store with data, plus registry stores with none yet, so a fresh
 * install shows them all instead of an empty screen. Favourites first,
 * failed stores next.
 */
@Composable
private fun rememberStoreRows(db: Db): StoreList {
    val version by db.version.collectAsState()
    val list by produceState(StoreList(), version) {
        value = db.read { conn ->
            val known = stores(conn)
            val names = known.map { it["name"] }.toSet()
            val favourites = favStores(conn)
            val rows = (known + registry().filter { it.first !in names }
                .map { mapOf("name" to it.first, "n_products" to 0L, "last_scrape" to null, "last_success" to null) })
                // Favourites, then stores whose last scrape failed, then the rest.
                .sortedWith(compareBy({ it["name"] !in favourites }, { it["last_success"] != 0L }, { it["name"] as String }))
            StoreList(rows, favourites, getCursor(conn, CATALOG_CURSOR))
        }
    }
    return list
}

/** A store laid out like a product card: its logo as the photo, the star in the corner. */
@Composable
private fun StoreCard(store: SqlRow, favourite: Boolean, open: (String) -> Unit, toggleFavourite: () -> Unit) {
    val name = store["name"] as String
    val failed = store["last_success"] == 0L
    Card(
        onClick = { open(name) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box {
            Logo(name, Modifier.fillMaxWidth().aspectRatio(1f))
            IconButton(onClick = toggleFavourite, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(if (favourite) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = if (favourite) "Quitar de favoritas" else "Marcar favorita",
                    tint = when {
                        favourite -> MaterialTheme.colorScheme.primary
                        STORE_LOGOS[name]?.dark == true -> Color(0xCCFFFFFF)
                        else -> Color(0x99222222)
                    })
            }
        }
        CardText(store["city"] as String?, null, name) {
            Text("${count(store["n_products"])} productos", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            Text((if (failed) "Falló " else "") + ago(store["last_scrape"]),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UpdateStatus(info: WorkInfo) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> Text("Esperando conexión…")
                WorkInfo.State.RUNNING -> Running(info.progress)
                WorkInfo.State.SUCCEEDED -> Finished(info.outputData)
                WorkInfo.State.FAILED -> Finished(info.outputData, failedRun = true)
                WorkInfo.State.CANCELLED -> Text("Actualización detenida.")
            }
        }
    }
}

@Composable
private fun Running(progress: Data) {
    val done = progress.getInt(UpdateWorker.KEY_DONE, 0)
    val total = progress.getInt(UpdateWorker.KEY_TOTAL, 0)
    val store = progress.getString(UpdateWorker.KEY_STORE)
    Text(when {
        store == "catálogo" -> "Descargando el catálogo…"
        total == 0 -> "Empezando…"
        done == 0 -> "Actualizando ${store ?: "$total tiendas"}…"
        else -> "[$done/$total] $store · ${count(progress.getInt(UpdateWorker.KEY_PRODUCTS, 0))} productos"
    })
    LinearProgressIndicator(
        progress = { if (total == 0) 0f else done.toFloat() / total },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun Finished(out: Data, failedRun: Boolean = false) {
    val failed = out.getNullableStringArray(UpdateWorker.KEY_FAILED).orEmpty().filterNotNull()
    when {
        out.getBoolean(UpdateWorker.KEY_SKIPPED, false) ->
            Text("Había otra actualización o una importación en curso; esta no se hizo.")
        out.getBoolean(UpdateWorker.KEY_CURRENT, false) -> Text("El catálogo ya estaba al día.")
        failedRun -> Text("La actualización falló.")
        else -> Text("Última actualización: ${count(out.getInt(UpdateWorker.KEY_PRODUCTS, 0))} productos, " +
            "${count(out.getInt(UpdateWorker.KEY_NEW, 0))} nuevos, ${failed.size} con error.")
    }
    failed.forEach { Facts(it) }
}

/** The store's own logo on its background, or its initial on a tile when there is none. */
@Composable
private fun Logo(name: String, modifier: Modifier) {
    val logo = STORE_LOGOS[name]
    if (logo != null) {
        AsyncImage(
            model = logo.url.takeIf(::isWebUrl), contentDescription = null, contentScale = ContentScale.Fit,
            modifier = modifier.background(if (logo.dark) Color.Black else Color.White).padding(16.dp),
        )
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), fontSize = 64.sp, fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f))
        }
    }
}
