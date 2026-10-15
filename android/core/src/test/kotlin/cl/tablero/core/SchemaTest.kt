package cl.tablero.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cl.tablero.core.data.SCHEMA_VERSION
import cl.tablero.core.data.SchemaVersionError
import cl.tablero.core.data.initDb
import cl.tablero.core.data.userVersion
import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SchemaTest {
    private fun open() = BundledSQLiteDriver().open(":memory:")

    @Test
    fun versionMatchesPython() {
        val py = REPO.resolve("scripts/db.py").readText()
        val version = Regex("""(?m)^SCHEMA_VERSION = (\d+)""").find(py)!!.groupValues[1].toInt()
        assertEquals(version, SCHEMA_VERSION)
    }

    @Test
    fun createsEveryTableExceptFts() {
        open().use { conn ->
            initDb(conn)
            initDb(conn) // idempotent
            assertEquals(SCHEMA_VERSION, userVersion(conn))
            val tables = conn.prepare(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
            ).use { st -> buildList { while (st.step()) add(st.getText(0)) } }
            assertEquals(
                listOf("cursor", "game", "price_obs", "product", "scrape_run", "store", "watchlist"),
                tables,
            )
        }
    }

    @Test
    fun refusesAnotherSchemaVersion() {
        open().use { conn ->
            conn.execSQL("PRAGMA user_version = 3")
            assertFailsWith<SchemaVersionError> { initDb(conn) }
        }
    }
}
