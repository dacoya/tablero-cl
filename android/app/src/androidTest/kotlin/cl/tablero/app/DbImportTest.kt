package cl.tablero.app

import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cl.tablero.core.data.exec
import cl.tablero.core.data.initDb
import cl.tablero.core.data.query
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** What Db.import accepts and refuses: it replaces the user's whole database. */
@RunWith(AndroidJUnit4::class)
class DbImportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = context.cacheDir.resolve("db-import-test")
    private lateinit var db: Db

    @Before fun setUp() {
        dir.deleteRecursively(); dir.mkdirs()
        db = Db(dir.resolve("tablero.db"))
    }

    @After fun tearDown() { dir.deleteRecursively() }

    /** A valid database file with one store and `products` products. */
    private fun copy(name: String, products: Int, extra: String? = null): File = dir.resolve(name).also { f ->
        AndroidSQLiteDriver().open(f.path).use { conn ->
            initDb(conn)
            conn.exec("INSERT INTO store(name) VALUES ('s')")
            repeat(products) { i ->
                conn.exec("""INSERT INTO product(store, url_canon, url, title_raw, title, norm, first_seen, last_seen)
                             VALUES ('s', ?, ?, 'X', 'X', 'x', 1, 1)""", "s.cl/$i", "https://s.cl/$i")
            }
            if (extra != null) conn.execSQL(extra)
        }
    }

    private fun import(file: File): Int = runBlocking { db.import(file.inputStream(), dir) }

    private fun refused(file: File, reason: String) = try {
        import(file); fail("imported a file that should be refused: $reason")
    } catch (e: IllegalArgumentException) {
        assertTrue("${e.message} should mention $reason", e.message!!.contains(reason))
    }

    @Test
    fun acceptsAValidCopyAndReplacesTheData() {
        assertEquals(3, import(copy("a.db", 3)))
        assertEquals(2, import(copy("b.db", 2)))
        val n = runBlocking { db.read { it.query("SELECT COUNT(*) AS n FROM product").first()["n"] } }
        assertEquals(2L, n)
    }

    @Test
    fun refusesTriggersViewsAndNonDatabases() {
        refused(copy("t.db", 1, "CREATE TRIGGER t AFTER INSERT ON product BEGIN SELECT 1; END"), "objetos")
        refused(copy("v.db", 1, "CREATE VIEW v AS SELECT 1"), "objetos")
        refused(dir.resolve("junk.db").apply { writeText("not a database at all") }, "no es SQLite")
        refused(dir.resolve("short.db").apply { writeText("SQLite") }, "no es SQLite")
        refused(copy("future.db", 1, "PRAGMA user_version = 99"), "Esquema v99")
        // Some other app's SQLite file: valid, but not ours.
        val foreign = dir.resolve("foreign.db").also { f ->
            AndroidSQLiteDriver().open(f.path).use { it.execSQL("CREATE TABLE notes(body TEXT)") }
        }
        refused(foreign, "no tiene su esquema")
    }

    @Test
    fun refusesWhileAnUpdateHoldsTheDatabase() {
        val before = import(copy("a.db", 3))
        assertTrue(db.busy.tryLock())
        try {
            runBlocking { db.import(copy("b.db", 1).inputStream(), dir) }
            fail("imported during an update")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("actualización en curso"))
        } finally {
            db.busy.unlock()
        }
        val n = runBlocking { db.read { it.query("SELECT COUNT(*) AS n FROM product").first()["n"] } }
        assertEquals(before.toLong(), n)
    }
}
