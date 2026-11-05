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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import cl.tablero.app.Notify
import cl.tablero.app.TableroApp
import cl.tablero.core.data.Filters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A game opened from a list: what the detail screen needs to draw at once. */
data class GameRef(val id: Long, val title: String, val image: String? = null)

private data class Tab(val key: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("inicio", "Inicio", Icons.Filled.Home),
    Tab("catalogo", "Catálogo", Icons.Filled.ShoppingCart),
    Tab("radar", "Mi radar", Icons.Filled.Notifications),
    Tab("ajustes", "Ajustes", Icons.Filled.Settings),
)

// A rotation must not drop the open games back to the list. Flat: three values per game.
private val GamesSaver = listSaver<List<GameRef>, Any?>(
    save = { refs -> refs.flatMap { listOf(it.id, it.title, it.image) } },
    restore = { saved -> saved.chunked(3).map { GameRef(it[0] as Long, it[1] as String, it[2] as String?) } },
)

class MainActivity : ComponentActivity() {
    // A notification tap names the tab to open, in a fresh start or a running app.
    private val requestedTab = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TableroApp
        requestedTab.value = intent.getStringExtra(Notify.EXTRA_TAB)
        setContent { Theme { App(app, requestedTab) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab.value = intent.getStringExtra(Notify.EXTRA_TAB)
    }
}

/**
 * Four tabs plus pushed screens: the stores and one of them, under Ajustes, and
 * games over any tab, a stack since a game links to its expansions and back.
 * Navigation is a few pieces of state rather than a navigation library.
 */
@Composable
private fun App(app: TableroApp, requestedTab: MutableStateFlow<String?>) {
    val db = app.db
    var tab by rememberSaveable { mutableStateOf("inicio") }
    var browse by rememberSaveable(stateSaver = BrowseSaver) {
        mutableStateOf(browseFromJson(app.prefs.catalog)
            ?: Browse(filters = Filters(onSale = true, inStockOnly = app.prefs.inStockOnly.value)))
    }
    var games by rememberSaveable(stateSaver = GamesSaver) { mutableStateOf(emptyList<GameRef>()) }
    var showStores by rememberSaveable { mutableStateOf(false) }
    var store by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { scope.launch { snackbar.showSnackbar(it) } }

    val goTo: (String) -> Unit = { tab = it; games = emptyList(); showStores = false; store = null }
    val wanted by requestedTab.collectAsState()
    LaunchedEffect(wanted) {
        val target = wanted ?: return@LaunchedEffect
        if (TABS.any { it.key == target }) goTo(target)
        requestedTab.value = null
    }

    val back: () -> Unit = {
        when {
            games.isNotEmpty() -> games = games.dropLast(1)
            store != null -> store = null
            else -> showStores = false
        }
    }
    BackHandler(enabled = games.isNotEmpty() || showStores, onBack = back)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                TABS.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.key && games.isEmpty(),
                        onClick = { goTo(t.key) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label, maxLines = 1, softWrap = false) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        // The keyboard from a search would otherwise stay up over the game and its buy button.
        val open: (GameRef) -> Unit = { focus.clearFocus(); games = games + it }
        val current = games.lastOrNull()
        // The tab stays composed under an open game, so coming back lands on
        // the same scroll position and loaded pages instead of the top.
        Box(Modifier.fillMaxSize()) {
            when {
                tab == "inicio" -> HomeScreen(db, app.prefs, open, show = { browse = it; tab = "catalogo" }, modifier)
                tab == "catalogo" -> CatalogScreen(db, browse, change = { browse = it; app.prefs.catalog = it.toJson() }, open, modifier)
                tab == "radar" -> RadarScreen(db, open, modifier)
                store != null -> StoreScreen(db, store!!, open, notify, onBack = { store = null }, modifier)
                showStores -> StoresScreen(db, onBack = { showStores = false }, onStore = { store = it }, modifier)
                else -> SettingsScreen(app, notify, onStores = { showStores = true }, modifier)
            }
            if (current != null) Surface(Modifier.fillMaxSize()) {
                // Its own scroll position per game: an expansion opens at the top.
                key(current.id) { GameScreen(db, current, notify, open, onBack = back, modifier) }
            }
        }
    }
}
