package cl.tablero.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha
import cl.tablero.app.ago
import cl.tablero.core.data.Freshness
import cl.tablero.core.data.gameBase
import cl.tablero.core.data.gameExpansions
import cl.tablero.core.data.isOwned
import cl.tablero.core.data.offersByTotal
import cl.tablero.core.data.ownedAdd
import cl.tablero.core.data.ownedRemove
import cl.tablero.core.data.shippingCosts
import cl.tablero.core.data.shippingSet
import cl.tablero.core.data.storeFreshness
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import cl.tablero.app.pct
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cl.tablero.app.Db
import cl.tablero.app.count
import cl.tablero.app.isWebUrl
import cl.tablero.app.money
import cl.tablero.core.data.SqlRow
import cl.tablero.core.data.gameBgg
import cl.tablero.core.data.gamePrices
import cl.tablero.core.data.gameTrends
import cl.tablero.core.data.watchAdd
import cl.tablero.core.data.watchOf
import cl.tablero.core.data.watchRemove
import kotlinx.coroutines.launch

private class Detail(
    val offers: List<SqlRow>, val trends: List<SqlRow>, val watch: SqlRow?, val bgg: SqlRow?, val owned: Boolean,
    val fresh: Map<String, Freshness>, val expansions: List<SqlRow>, val base: SqlRow?,
)

/**
 * One game across stores, cheapest in total first; a row opens the store's
 * page. The first screen is what a decision needs: photo, name, best price, and
 * the way to buy it stays on screen. Below, the rest: BGG, expansions, history.
 */
@Composable
fun GameScreen(db: Db, game: GameRef, notify: (String) -> Unit, open: (GameRef) -> Unit, onBack: () -> Unit,
               modifier: Modifier = Modifier) {
    val version by db.version.collectAsState()
    val detail by produceState<Detail?>(null, game.id, version) {
        value = db.read {
            Detail(offersByTotal(gamePrices(it, game.id), shippingCosts(it)), gameTrends(it, game.id, minPoints = 2),
                watchOf(it, game.id), gameBgg(it, game.id), isOwned(it, game.id), storeFreshness(it),
                gameExpansions(it, game.id), gameBase(it, game.id))
        }
    }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var shippingOf by remember { mutableStateOf<String?>(null) }
    var allOffers by rememberSaveable { mutableStateOf(false) }
    val d = detail
    // Offers come cheapest first; the headline price is one that can be bought.
    val best = d?.offers?.let { o -> o.firstOrNull { it["in_stock"] != 0L } ?: o.firstOrNull() }

    Column(modifier) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Hero(game.image ?: d?.offers?.firstNotNullOfOrNull { it["image_url"] as String? }, onBack) }
            item {
                Text(game.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            if (d == null) return@LazyColumn
            item {
                best?.let { BestPrice(it, d.offers.size, d.fresh[it["store"]]) }
                OwnControls(d.watch, d.owned, onEdit = { editing = true }, onUnfollow = {
                    scope.launch { db.write { watchRemove(it, game.id) }; notify("Ya no lo sigues.") }
                }, onOwned = { own ->
                    scope.launch {
                        db.write { if (own) ownedAdd(it, game.id) else ownedRemove(it, game.id) }
                        notify(if (own) "Agregado a tu colección." else "Quitado de tu colección.")
                    }
                })
            }
            item { BggBadges(d.bgg, Modifier.padding(top = 8.dp)) }
            if (d.offers.isEmpty()) item { Empty("Ninguna tienda lo tiene en su último scrape.") }
            else item { SectionTitle("Precios por tienda") }
            items(if (allOffers) d.offers else d.offers.take(OFFERS_SHOWN), key = { it["product_id"] as Long }) { offer ->
                OfferCard(offer, d.fresh[offer["store"]], notify) { shippingOf = offer["store"] as String }
            }
            if (!allOffers && d.offers.size > OFFERS_SHOWN) item {
                TextButton(onClick = { allOffers = true }, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text("Ver las ${d.offers.size} tiendas")
                }
            }
            d.base?.let { base ->
                item {
                    SectionTitle("Es expansión de")
                    TextButton(onClick = { open(GameRef(base["game_id"] as Long, base["title"] as String)) },
                        modifier = Modifier.padding(horizontal = 4.dp)) { Text(base["title"] as String) }
                }
            }
            if (d.expansions.isNotEmpty()) item {
                SectionTitle("Expansiones (${d.expansions.size})")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(d.expansions, key = { it["game_id"] as Long }) { GameCard(it, open, Modifier.width(156.dp)) }
                }
            }
            item { SectionTitle("BoardGameGeek") }
            item { BggCard(d.bgg, notify) }
            history(d.trends)
        }
        best?.let { BuyBar(it, notify) }
    }

    if (editing) {
        FollowDialog(game.title, current = detail?.watch?.get("target") as Double?, onDismiss = { editing = false }) { target ->
            editing = false
            scope.launch {
                db.write { watchAdd(it, game.id, target) }
                notify(if (target != null) "Te avisamos bajo ${money(target)}." else "Siguiendo ${game.title}.")
            }
        }
    }
    shippingOf?.let { store ->
        val current = detail?.offers?.firstOrNull { it["store"] == store }?.get("shipping") as Double?
        ShippingDialog(store, current, onDismiss = { shippingOf = null }) { cost ->
            shippingOf = null
            scope.launch { db.write { shippingSet(it, store, cost) } }
        }
    }
}

/** The way to buy, always on screen. */
@Composable
private fun BuyBar(best: SqlRow, notify: (String) -> Unit) {
    val uri = LocalUriHandler.current
    val url = best["url"]
    Surface(tonalElevation = 3.dp) {
        Button(
            onClick = { runCatching { uri.openUri(url as String) }.onFailure { notify("No hay navegador para abrir el enlace.") } },
            enabled = isWebUrl(url),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text("Ir a ${best["store"]} · ${money(best["total"])}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** How old an offer's price is; a store that failed since then is said outright. */
private fun freshness(f: Freshness?): String = when {
    f?.lastOk == null -> "Sin fecha de revisión"
    f.failed -> "No se pudo revisar · dato ${ago(f.lastOk)}"
    else -> "Revisado ${ago(f.lastOk)}"
}

/** The photo, full width, with the way back over its corner. */
@Composable
private fun Hero(url: String?, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1.6f).background(Color.White)) {
        Photo(url, Modifier.fillMaxSize().padding(vertical = 8.dp), crop = false)
        FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
        }
    }
}

/** The cheapest offer, big: what the page is for. */
@Composable
private fun BestPrice(best: SqlRow, stores: Int, fresh: Freshness?) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if ((best["shipping"] as Double? ?: 0.0) > 0) "Mejor total con despacho" else "Mejor precio",
                    style = MaterialTheme.typography.labelMedium)
                Text(money(best["total"]), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary)
                Text(listOfNotNull("en ${best["store"]}", stock(best["in_stock"]),
                    "$stores tienda${if (stores == 1) "" else "s"} lo venden").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall)
                Text(freshness(fresh), style = MaterialTheme.typography.bodySmall,
                    color = if (fresh?.dim() != false) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
            pct(best["discount_pct"])?.let { Tag(it, Sale) }
        }
    }
}

/** "Seguir" for a game not watched, its target and "Dejar de seguir" for one that is; and "Ya lo tengo". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OwnControls(watch: SqlRow?, owned: Boolean, onEdit: () -> Unit, onUnfollow: () -> Unit, onOwned: (Boolean) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (watch == null) {
            Button(onClick = onEdit) { Text("Seguir") }
        } else {
            OutlinedButton(onClick = onEdit) {
                Text(watch["target"]?.let { "Objetivo ${money(it)}" } ?: "Siguiendo · sin objetivo")
            }
            TextButton(onClick = onUnfollow) { Text("Dejar de seguir") }
        }
        FilterChip(owned, { onOwned(!owned) }, { Text("Ya lo tengo") })
    }
}

/** What shipping adds to an offer, as the user entered it. */
private fun shippingLabel(cost: Double?, total: Any?): String = when (cost) {
    null -> "Anotar despacho"
    0.0 -> "Retiro gratis"
    else -> "+ ${money(cost)} despacho = ${money(total)}"
}

/**
 * One store's offer; a tap opens the store's page, when it is a plain web link.
 * A price that may be out of date is drawn faded and says how old it is.
 */
@Composable
private fun OfferCard(offer: SqlRow, fresh: Freshness?, notify: (String) -> Unit, onShipping: () -> Unit) {
    val uri = LocalUriHandler.current
    val url = offer["url"]
    val available = offer["in_stock"] != 0L
    val shipping = offer["shipping"] as Double?
    OutlinedCard(
        onClick = { runCatching { uri.openUri(url as String) }.onFailure { notify("No hay navegador para abrir el enlace.") } },
        enabled = isWebUrl(url),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp).alpha(if (fresh?.dim() != false) DIM_ALPHA else 1f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Thumb(offer["image_url"], size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(offer["store"] as String, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(offer["title"] as String, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stock(offer["in_stock"]) ?: "disponible", style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (available) InStock else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Price(offer)
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(freshness(fresh), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f),
                color = if (fresh?.dim() != false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onShipping) { Text(shippingLabel(shipping, offer["total"]), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** The shipping editor. `onSave` gets a cost (0 is free pickup), or null to forget it. */
@Composable
private fun ShippingDialog(store: String, current: Double?, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var text by remember { mutableStateOf(current?.toLong()?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Despacho desde $store") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { v -> text = v.filter { it.isDigit() }.take(7) }, singleLine = true,
                label = { Text("Lo que te cuesta") }, prefix = { Text("$") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("0 si retiras gratis. Vacío para olvidarlo. Se suma al comparar tiendas.") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.toDoubleOrNull()) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// Offers listed before "Ver las N tiendas": a popular game has 36, and what follows them would never be seen.
private const val OFFERS_SHOWN = 5

// A price that may be out of date still reads, but does not look current.
private const val DIM_ALPHA = 0.55f

private const val UNKNOWN = "Desconocido"

// CSV column -> label, in the order shown.
private val BGG_CATEGORIES = listOf(
    "strategygames_rank" to "Estrategia", "familygames_rank" to "Familiares", "partygames_rank" to "Party",
    "thematic_rank" to "Temáticos", "abstracts_rank" to "Abstractos", "childrensgames_rank" to "Infantiles",
    "wargames_rank" to "Wargames", "cgs_rank" to "Cartas coleccionables",
)

private fun score(v: Any?): String = (v as? Number)?.let { "%.2f".format(java.util.Locale.ROOT, it.toDouble()) } ?: UNKNOWN
private fun rank(v: Any?): String = (v as? Number)?.let { "#${count(it)}" } ?: UNKNOWN
private fun players(min: Any?, max: Any?): String = when {
    min == null && max == null -> UNKNOWN
    min == null || max == null || min == max -> "${min ?: max}"
    else -> "$min–$max"
}

/**
 * BGG's ranks CSV and bgg_names_es.csv for the matched entry: what the badges above do not already
 * say first, every other field behind "Ver más".
 */
@Composable
private fun BggCard(bgg: SqlRow?, notify: (String) -> Unit) {
    val uri = LocalUriHandler.current
    var more by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (bgg == null) {
                Facts("Sin coincidencia en BoardGameGeek.")
                return@Column
            }
            val categories = BGG_CATEGORIES.mapNotNull { (key, label) -> (bgg[key] as? Number)?.let { "$label ${rank(it)}" } }
            val main = listOf(
                "Año" to (bgg["yearpublished"]?.toString() ?: UNKNOWN),
                "Tipo" to when (bgg["is_expansion"]) { 1L -> "Expansión"; 0L -> "Juego base"; else -> UNKNOWN },
                "Votos" to ((bgg["usersrated"] as? Number)?.let { count(it) } ?: UNKNOWN),
                "Jugadores" to players(bgg["min_players"], bgg["max_players"]),
                "Duración" to (bgg["play_time"]?.let { "$it min" } ?: UNKNOWN),
                "Edad" to (bgg["min_age"]?.let { "$it+" } ?: UNKNOWN),
                "Peso" to score(bgg["complexity"]),
            )
            val rest = listOf(
                "Nombre" to (bgg["name"] as String? ?: UNKNOWN),
                "Ranking" to rank(bgg["rank"]),
                "Nota geek" to score(bgg["bayesaverage"]),
                "Nota promedio" to score(bgg["average"]),
                "Por categoría" to (categories.joinToString(" · ").ifEmpty { UNKNOWN }),
                "Mecánicas" to (bgg["mechanics"] as String? ?: UNKNOWN),
            )
            (if (more) main + rest else main).forEach { (label, value) ->
                Row {
                    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.4f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(0.6f),
                        color = if (value == UNKNOWN) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
                }
            }
            if (bgg["facts_id"] != null) Facts("Ficha: BGG")
            TextButton(onClick = { more = !more }) { Text(if (more) "Ver menos" else "Ver más") }
            bgg["id"]?.let { id ->
                TextButton(onClick = {
                    runCatching { uri.openUri("https://boardgamegeek.com/boardgame/$id") }
                        .onFailure { notify("No hay navegador para abrir el enlace.") }
                }) { Text("Powered by BGG · ver en BoardGameGeek") }
            }
        }
    }
}

/** How each listing's price moved, most-moved first; nothing when no listing moved. */
private fun LazyListScope.history(trends: List<SqlRow>) {
    if (trends.isEmpty()) return
    item {
        Text("Historial de precios", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
    }
    // Unkeyed: two listings of one title in one store share a label.
    items(trends) { t ->
        val change = t["change_pct"] as Double
        ListItem(
            headlineContent = { Title(t["label"] as String) },
            supportingContent = { Facts("${t["spark"]}  ${money(t["first"])} → ${money(t["last"])} · ${t["n"]} registros") },
            trailingContent = {
                Text(if (change == 0.0) "=" else "%+.0f%%".format(java.util.Locale.ROOT, change),
                    color = if (change < 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            },
        )
    }
}

/** The target price editor. `onSave` gets a positive target, or null for "just follow it". */
@Composable
private fun FollowDialog(title: String, current: Double?, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var text by remember { mutableStateOf(current?.toLong()?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Seguir $title") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { v -> text = v.filter { it.isDigit() }.take(9) }, singleLine = true,
                label = { Text("Precio objetivo (opcional)") }, prefix = { Text("$") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("Te avisamos cuando alguna tienda lo tenga a este precio o menos.") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.toDoubleOrNull()?.takeIf { it > 0 }) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
