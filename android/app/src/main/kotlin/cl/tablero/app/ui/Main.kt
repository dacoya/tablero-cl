package cl.tablero.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import cl.tablero.app.Notify
import cl.tablero.app.TableroApp
import cl.tablero.app.UpdateWorker
import cl.tablero.core.data.Filters
import cl.tablero.core.data.cartCount
import cl.tablero.core.data.sinceLastReview
import cl.tablero.core.data.watchEntries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A game opened from a list: what the detail screen needs to draw at once. */
data class GameRef(val id: Long, val title: String, val image: String? = null)

private data class Tab(val key: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("inicio", "Inicio", Icons.Filled.Home),
    Tab("catalogo", "Catálogo", Icons.AutoMirrored.Filled.List),
    Tab("radar", "Mi radar", Icons.Filled.Notifications),
    Tab("carrito", "Carrito", Icons.Filled.ShoppingCart),
    Tab("ajustes", "Ajustes", Icons.Filled.Settings),
)

/** A screen pushed over the tabs: a game, one store, or (neither) the list of stores. */
/** A snackbar drawn grey, for a warning that needs no action. */
private class Warning(override val message: String) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction = false
    override val duration = SnackbarDuration.Indefinite
}

private const val WARNING_MS = 3_000L
private val WarningGrey = Color(0xFF616161)

private data class Dest(val game: GameRef? = null, val store: String? = null)

// A rotation must not drop the open screens back to the tab. Flat: three values each.
private val StackSaver = listSaver<List<Dest>, Any?>(
    save = { stack -> stack.flatMap { listOf(it.game?.id, it.game?.title ?: it.store, it.game?.image) } },
    restore = { saved ->
        saved.chunked(3).map { (id, name, image) ->
            if (id != null) Dest(game = GameRef(id as Long, name as String, image as String?)) else Dest(store = name as String?)
        }
    },
)

class MainActivity : ComponentActivity() {
    // A notification tap names the tab to open, in a fresh start or a running app.
    private val requestedTab = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TableroApp
        requestedTab.value = intent.getStringExtra(Notify.EXTRA_TAB)
        // Sync on opening; unchanged since the last fetch, it downloads nothing and says nothing.
        if (savedInstanceState == null) UpdateWorker.start(this)
        setContent { Theme { App(app, requestedTab) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab.value = intent.getStringExtra(Notify.EXTRA_TAB)
    }
}

/**
 * Five tabs plus one stack of pushed screens over them: games, a store, the
 * list of stores. Any of them can open any other (a game's offer opens its
 * store, a store's catalogue opens a game) and back pops one.
 * Navigation is a few pieces of state rather than a navigation library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(app: TableroApp, requestedTab: MutableStateFlow<String?>) {
    val db = app.db
    var tab by rememberSaveable { mutableStateOf("inicio") }
    var browse by rememberSaveable(stateSaver = BrowseSaver) {
        mutableStateOf(browseFromJson(app.prefs.catalog)
            ?: Browse(filters = Filters(onSale = true, inStockOnly = app.prefs.inStockOnly.value)))
    }
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(emptyList<Dest>()) }
    val focus = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { scope.launch { snackbar.showSnackbar(it) } }

    val goTo: (String) -> Unit = { tab = it; stack = emptyList() }
    val wanted by requestedTab.collectAsState()
    LaunchedEffect(wanted) {
        val target = wanted ?: return@LaunchedEffect
        if (TABS.any { it.key == target }) goTo(target)
        requestedTab.value = null
    }

    val back: () -> Unit = { stack = stack.dropLast(1) }
    BackHandler(enabled = stack.isNotEmpty(), onBack = back)

    // What the tab bar's badges count: games in the cart, followed games that moved.
    // ponytail: the radar count reruns the whole changes query on every write;
    // a COUNT query of its own if the bar ever lags during an update.
    val version by db.version.collectAsState()
    val badges by produceState(emptyMap<String, Long>(), version) {
        value = db.read { conn ->
            val watched = watchEntries(conn).map { it["game_id"] }.toSet()
            val moved = sinceLastReview(conn, limit = null).map { it["game_id"] }.filter { it in watched }.toSet()
            mapOf("carrito" to cartCount(conn), "radar" to moved.size.toLong())
        }
    }

    // Cancelling showSnackbar dismisses it: that is the 3 seconds.
    val warn: (String) -> Unit = { scope.launch { withTimeoutOrNull(WARNING_MS) { snackbar.showSnackbar(Warning(it)) } } }
    val sync = rememberPullSync(notify, warn)

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                if (data.visuals is Warning) Snackbar(data, containerColor = WarningGrey, contentColor = Color.White)
                else Snackbar(data)
            }
        },
        bottomBar = {
            NavigationBar {
                TABS.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.key && stack.isEmpty(),
                        onClick = { goTo(t.key) },
                        icon = {
                            val n = badges[t.key] ?: 0L
                            BadgedBox(badge = { if (n > 0) Badge { Text("$n") } }) { Icon(t.icon, contentDescription = null) }
                        },
                        label = { Text(t.label, maxLines = 1, softWrap = false) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        // The keyboard from a search would otherwise stay up over the game and its buy button.
        val open: (GameRef) -> Unit = { focus.clearFocus(); stack = stack + Dest(game = it) }
        val openStore: (String) -> Unit = { stack = stack + Dest(store = it) }
        // The tab and every pushed screen stay composed under the top one, so
        // coming back lands on the same scroll position and loaded pages.
        Box(Modifier.fillMaxSize()) {
            // Pull down on any tab to sync the catalogue.
            PullToRefreshBox(sync.refreshing, sync.start, modifier.fillMaxSize()) {
                when (tab) {
                    "inicio" -> HomeScreen(db, app.prefs, open, show = { browse = it; tab = "catalogo" })
                    "catalogo" -> CatalogScreen(db, browse, change = { browse = it; app.prefs.catalog = it.toJson() }, open)
                    "radar" -> RadarScreen(db, open, notify)
                    "carrito" -> CartScreen(db, open, openStore, notify)
                    else -> SettingsScreen(app, notify, onStores = { stack = stack + Dest() })
                }
            }
            stack.forEachIndexed { depth, dest ->
                // Keyed by depth too: the same game can sit in the stack twice.
                key(depth, dest) {
                    Surface(Modifier.fillMaxSize()) {
                        when {
                            dest.game != null -> GameScreen(db, dest.game, notify, open, openStore, onBack = back, modifier)
                            dest.store != null -> StoreScreen(db, dest.store, open, notify, onBack = back, modifier)
                            else -> StoresScreen(db, onBack = back, onStore = openStore, modifier)
                        }
                    }
                }
            }
        }
    }
}
