package cl.tablero.app.ui

import coil3.compose.AsyncImage
import cl.tablero.app.isWebUrl
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.Data
import androidx.work.WorkInfo
import cl.tablero.app.Db
import cl.tablero.app.TableroApp
import cl.tablero.app.UpdateWorker
import cl.tablero.app.ago
import cl.tablero.app.count
import cl.tablero.core.data.SqlRow
import cl.tablero.core.data.dueAt
import cl.tablero.core.data.favSet
import cl.tablero.core.data.favStores
import cl.tablero.core.data.lastSuccess
import cl.tablero.core.data.stores
import kotlinx.coroutines.launch
import cl.tablero.core.scrape.registry
import cl.tablero.core.scrape.sites

/**
 * Store status and updates. Stores the phone cannot scrape yet still list
 * with their imported data, marked "solo PC", so it is clear where those
 * prices came from; a store whose last scrape failed says so.
 */
@Composable
fun StoresScreen(db: Db, onBack: () -> Unit, onStore: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = (context.applicationContext as TableroApp).prefs
    val favDays by prefs.favoriteDays.flow.collectAsState()
    val otherDays by prefs.otherDays.flow.collectAsState()
    val list = rememberStoreRows(db)
    val scope = rememberCoroutineScope()
    val scrapeable = remember { sites().map { it.name }.toSet() }
    val updates = rememberUpdates()
    val start = rememberStartUpdate()

    Column(modifier) {
        Row(Modifier.padding(end = 16.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Back(onBack)
            Text("Tiendas", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (updates.active == null) {
                Button(onClick = { start(emptyList()) }) { Text("Actualizar ${scrapeable.size}") }
            } else {
                OutlinedButton(onClick = { updates.stop(context) }) { Text("Detener") }
            }
        }
        updates.shown?.let { UpdateStatus(it) }
        LazyColumn {
            items(list.rows, key = { it["name"] as String }) { store ->
                val name = store["name"] as String
                val favourite = name in list.favourites
                StoreRow(store, canScrape = name in scrapeable, idle = updates.active == null,
                    favourite = favourite,
                    due = dueAt(name, list.favourites, list.last, favDays, otherDays).takeIf { list.last[name] != null },
                    toggleFavourite = { scope.launch { db.write { favSet(it, name, !favourite) } } },
                    open = onStore) { start(listOf(it)) }
            }
        }
    }
}

private class StoreList(
    val rows: List<SqlRow> = emptyList(),
    val favourites: Set<String> = emptySet(),
    val last: Map<String, Long> = emptyMap(),
)

/**
 * Every store with data, plus registry stores with none yet, so a fresh
 * install shows what it can scrape instead of an empty screen. Favourites first.
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
                .sortedWith(compareBy({ it["name"] !in favourites }, { it["name"] as String }))
            StoreList(rows, favourites, lastSuccess(conn))
        }
    }
    return list
}

@Composable
private fun StoreRow(store: SqlRow, canScrape: Boolean, idle: Boolean, favourite: Boolean, due: Long?,
                     toggleFavourite: () -> Unit, open: (String) -> Unit, update: (String) -> Unit) {
    val name = store["name"] as String
    val failed = store["last_success"] == 0L
    ListItem(
        modifier = Modifier.clickable { open(name) },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = toggleFavourite) {
                    Icon(if (favourite) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = if (favourite) "Quitar de favoritas" else "Marcar favorita",
                        tint = if (favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Logo(name)
            }
        },
        headlineContent = { Title(name) },
        supportingContent = {
            Text(
                "${count(store["n_products"])} productos · " + (if (failed) "falló " else "") + ago(store["last_scrape"]) +
                    (if (canScrape && due != null) " · ${dueIn(due)}" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            if (canScrape) TextButton(onClick = { update(name) }, enabled = idle) { Text("Actualizar") }
            else Text("solo PC", style = MaterialTheme.typography.labelSmall)
        },
    )
}

/** When the scheduled run takes a store again: "toca hoy", "en 3 d". */
private fun dueIn(due: Long): String {
    val days = (due - System.currentTimeMillis() / 1000 + 86_399) / 86_400
    return if (days <= 0) "toca hoy" else "toca en $days d"
}

@Composable
private fun UpdateStatus(info: WorkInfo) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> Text("Esperando conexión WiFi… (cámbialo en Ajustes)")
                WorkInfo.State.RUNNING -> Running(info.progress)
                WorkInfo.State.SUCCEEDED -> Finished(info.outputData)
                WorkInfo.State.FAILED -> Text("La actualización falló.")
                WorkInfo.State.CANCELLED -> Text("Actualización detenida. Lo ya scrapeado quedó guardado.")
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
private fun Finished(out: Data) {
    if (out.getBoolean(UpdateWorker.KEY_SKIPPED, false)) {
        Text("Había otra actualización o una importación en curso; esta no se hizo.")
        return
    }
    val failed = out.getNullableStringArray(UpdateWorker.KEY_FAILED).orEmpty().filterNotNull()
    Text("Última actualización: ${count(out.getInt(UpdateWorker.KEY_PRODUCTS, 0))} productos, " +
        "${count(out.getInt(UpdateWorker.KEY_NEW, 0))} nuevos, ${failed.size} con error.")
    failed.forEach { Facts(it) }
}

/** The store's own logo, or its initial on a tile when there is none. */
@Composable
private fun Logo(name: String) {
    val tile = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
    val logo = STORE_LOGOS[name]
    if (logo != null) {
        AsyncImage(
            model = logo.url.takeIf(::isWebUrl), contentDescription = null, contentScale = ContentScale.Fit,
            modifier = tile.background(if (logo.dark) Color.Black else Color.White).padding(2.dp),
        )
    } else {
        Box(tile.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
