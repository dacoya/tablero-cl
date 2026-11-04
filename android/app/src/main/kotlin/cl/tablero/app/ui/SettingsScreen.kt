package cl.tablero.app.ui

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.net.Uri
import cl.tablero.app.BuildConfig
import cl.tablero.app.Db
import cl.tablero.app.Prefs
import cl.tablero.app.Schedule
import cl.tablero.app.TableroApp
import cl.tablero.app.count
import cl.tablero.core.data.importBggCsv
import cl.tablero.core.data.query
import cl.tablero.core.data.userVersion
import kotlinx.coroutines.launch

/** Settings, the laptop round trip, and what is in the database. */
@Composable
fun SettingsScreen(app: TableroApp, notify: (String) -> Unit, onStores: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = app.prefs
    val reschedule = { Schedule.apply(context, prefs) }

    Column(modifier.verticalScroll(rememberScrollState())) {
        Text("Ajustes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))

        ListItem(
            headlineContent = { Text("Tiendas") },
            supportingContent = { Text("Actualizar ahora, ver cuáles fallaron y entrar a cada catálogo.") },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onStores),
        )

        Section("Actualización")
        Toggle("Actualizar cada día", "En segundo plano, de madrugada (4:00).", prefs.autoUpdate, reschedule)
        Toggle("Solo con WiFi", "Una actualización completa usa ~150 MB.", prefs.wifiOnly, reschedule)
        Toggle("Solo cargando", "La actualización diaria espera al cargador.", prefs.chargingOnly, reschedule)

        Section("Avisos")
        Toggle("Precio objetivo", "Cuando un juego que sigues llega a tu precio.", prefs.notifyTargets)
        Toggle("Cambios", "Cuando hay novedades desde tu última revisión.", prefs.notifyChanges)

        Section("Listas")
        Toggle("Accesorios en la búsqueda", "Fundas, dados y similares aparecen sin activarlos.", prefs.accessories)
        Toggle("Ofertas: solo disponibles", "Ocultar lo agotado de entrada.", prefs.inStockOnly)

        Section("Base de datos")
        DatabaseSection(app.db, notify)

        HorizontalDivider()
        About(app.db)
    }
}

/**
 * Import and export. Import is disabled while an update scrapes: the rest of
 * that update would otherwise write into the database just imported.
 */
@Composable
private fun DatabaseSection(db: Db, notify: (String) -> Unit) {
    val updating = rememberUpdates().running
    var confirmImport by remember { mutableStateOf(false) }
    val pickImport = rememberFileAction(ActivityResultContracts.OpenDocument(), notify) { context, uri ->
        val input = checkNotNull(context.contentResolver.openInputStream(uri)) { "No se pudo abrir el archivo." }
        "Base importada: ${count(db.import(input, context.cacheDir))} productos."
    }
    val pickBgg = rememberFileAction(ActivityResultContracts.OpenDocument(), notify) { context, uri ->
        val r = db.write { conn ->
            checkNotNull(context.contentResolver.openInputStream(uri)) { "No se pudo abrir el archivo." }
                .bufferedReader().useLines { importBggCsv(conn, it) }
        }
        "BGG: ${count(r.entries)} juegos" + (if (r.aliases >= 0) ", ${count(r.aliases)} nombres en español" else "") +
            ". ${count(r.matched)} juegos del catálogo con datos de BGG."
    }
    val pickExport = rememberFileAction(ActivityResultContracts.CreateDocument("application/octet-stream"), notify) { context, uri ->
        val out = checkNotNull(context.contentResolver.openOutputStream(uri)) { "No se pudo crear el archivo." }
        db.export(out, context.cacheDir)
        "Copia exportada."
    }

    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { confirmImport = true }, enabled = !updating) { Text("Importar") }
        OutlinedButton(onClick = { pickExport.launch("tablero-telefono.db") }) { Text("Exportar") }
        OutlinedButton(onClick = { pickBgg.launch(arrayOf("*/*")) }) { Text("CSV de BGG") }
    }
    Hint(
        if (updating) "Hay una actualización en curso: para importar, detenla primero en Tiendas, arriba."
        else "Importar trae la base del PC (créala con  tablero snapshot ). Exportar guarda una copia " +
            "de la del teléfono, que el PC puede usar como su tablero.db. CSV de BGG carga " +
            "boardgames_ranks_es.csv (rankings y nombres en español) y vuelve a emparejar los juegos.",
    )

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Importar base del PC") },
            text = { Text("Reemplaza toda la base del teléfono, incluido lo scrapeado y lo que sigues, por la copia elegida.") },
            confirmButton = {
                TextButton(onClick = { confirmImport = false; pickImport.launch(arrayOf("*/*")) }) { Text("Elegir archivo") }
            },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancelar") } },
        )
    }
}

/**
 * A system file picker whose result runs `action` and reports its message,
 * or the failure's. Cancelling the picker does nothing.
 */
@Composable
private fun <I> rememberFileAction(
    contract: androidx.activity.result.contract.ActivityResultContract<I, Uri?>,
    notify: (String) -> Unit,
    action: suspend (android.content.Context, Uri) -> String,
): ManagedActivityResultLauncher<I, Uri?> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return rememberLauncherForActivityResult(contract) { uri ->
        if (uri != null) scope.launch { notify(runCatching { action(context, uri) }.getOrElse { it.message ?: "No se pudo." }) }
    }
}

/** Version, schema, SQLite and how much the database holds. */
@Composable
private fun About(db: Db) {
    val version by db.version.collectAsState()
    val about by produceState("…", version) {
        value = db.read { conn ->
            val n = conn.query("""SELECT (SELECT COUNT(*) FROM product) AS products, (SELECT COUNT(*) FROM game) AS games,
                (SELECT COUNT(*) FROM price_obs) AS obs, sqlite_version() AS sqlite""").first()
            "Tablero ${BuildConfig.VERSION_NAME} · esquema v${userVersion(conn)} · SQLite ${n["sqlite"]}\n" +
                "${count(n["products"])} productos · ${count(n["games"])} juegos · ${count(n["obs"])} registros de precio"
        }
    }
    Hint(about)
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp))

@Composable
private fun Section(title: String) =
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))

@Composable
private fun Toggle(title: String, detail: String, flag: Prefs.Flag, changed: () -> Unit = {}) {
    val on by flag.flow.collectAsState()
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(checked = on, onCheckedChange = { flag.value = it; changed() }) },
    )
}
