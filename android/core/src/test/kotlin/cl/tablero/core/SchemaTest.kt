package cl.tablero.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cl.tablero.core.data.SCHEMA_VERSION
import cl.tablero.core.data.SchemaVersionError
import cl.tablero.core.data.UPGRADES
import cl.tablero.core.data.loadSchema
import cl.tablero.core.data.query
import cl.tablero.core.data.schemaStatements
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
                listOf("bgg", "bgg_alias", "cursor", "game", "owned", "price_obs", "product", "scrape_run", "store", "store_shipping", "watchlist"),
                tables,
            )
        }
    }

    @Test
    fun refusesNewerOrUnreachableVersions() {
        open().use { conn ->
            conn.execSQL("PRAGMA user_version = ${SCHEMA_VERSION + 1}")
            assertFailsWith<SchemaVersionError> { initDb(conn) }
        }
        open().use { conn ->
            conn.execSQL("PRAGMA user_version = 2") // no step to v3 exists
            assertFailsWith<SchemaVersionError> { initDb(conn) }
        }
    }

    @Test
    fun upgradesAV4DatabaseInPlace() {
        open().use { conn ->
            // schema v4: the same file without the column v5 added
            schemaStatements(loadSchema().replace("    image_url      TEXT,\n", "")).forEach(conn::execSQL)
            // nor what v6 added
            conn.execSQL("DROP TABLE bgg")
            conn.execSQL("DROP TABLE bgg_alias")
            conn.execSQL("ALTER TABLE game DROP COLUMN bgg_id")
            conn.execSQL("INSERT INTO store(name) VALUES ('s')")
            conn.execSQL("""INSERT INTO product(store, url_canon, url, title_raw, title, norm, first_seen, last_seen)
                            VALUES ('s', 's.cl/x', 'https://s.cl/x', 'X', 'X', 'x', 1, 1)""")
            conn.execSQL("PRAGMA user_version = 4")
            initDb(conn)
            assertEquals(SCHEMA_VERSION, userVersion(conn))
            assertEquals(listOf(mapOf<String, Any?>("title" to "X", "image_url" to null)),
                conn.query("SELECT title, image_url FROM product"))
        }
    }

    @Test
    fun upgradeStepsMatchPython() {
        // Every statement in db._UPGRADES, in order, and nothing else.
        val py = REPO.resolve("scripts/db.py").readText()
        val pySteps = Regex(""""((?:ALTER|CREATE) [^"]+)"""").findAll(py).map { it.groupValues[1] }.toList()
        assertEquals(pySteps, UPGRADES.toSortedMap().values.flatten())
    }
}
