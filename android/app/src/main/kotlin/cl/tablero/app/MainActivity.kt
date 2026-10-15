package cl.tablero.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.sqlite.driver.AndroidSQLiteDriver
import cl.tablero.core.data.initDb
import cl.tablero.core.data.userVersion

/**
 * Skeleton screen: opens the on-device database and reports what the plan's
 * decisions depend on -- the framework SQLite version and the schema stamp.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ponytail: main-thread open, fine for an empty skeleton; moves to a
        // background dispatcher with the first real query.
        val status = runCatching { probe() }.getOrElse { "Error: ${it.message}" }
        Log.i("tablero", status)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Tablero", style = MaterialTheme.typography.headlineMedium)
                        Text(status)
                    }
                }
            }
        }
    }

    private fun probe(): String {
        // The raw driver, unlike SQLiteOpenHelper, does not create databases/.
        val path = getDatabasePath("tablero.db").apply { parentFile?.mkdirs() }
        return AndroidSQLiteDriver().open(path.path).use { conn ->
            initDb(conn)
            val version = conn.prepare("SELECT sqlite_version()").use { it.step(); it.getText(0) }
            "SQLite $version · esquema v${userVersion(conn)}"
        }
    }
}
