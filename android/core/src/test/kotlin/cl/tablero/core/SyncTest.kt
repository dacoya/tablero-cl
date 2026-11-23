package cl.tablero.core

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cl.tablero.core.data.carryUserState
import cl.tablero.core.data.catalogStamp
import cl.tablero.core.data.exec
import cl.tablero.core.data.initDb
import cl.tablero.core.data.query
import cl.tablero.core.data.unreachableStores
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Personal tables survive a catalogue download whose ids all moved. */
class SyncTest {
    private val dir: File = Files.createTempDirectory("sync").toFile()

    private fun db(name: String, block: (SQLiteConnection) -> Unit): SQLiteConnection =
        BundledSQLiteDriver().open(dir.resolve(name).path).also { initDb(it); block(it) }

    private fun product(c: SQLiteConnection, id: Long, url: String, gameId: Long, norm: String) = c.exec(
        """INSERT INTO product(id, store, url_canon, url, game_id, title_raw, title, norm, first_seen, last_seen)
           VALUES (?, 's', ?, ?, ?, 'X', 'X', ?, 1, 1)""", id, url, "https://$url", gameId, norm)

    @Test
    fun remapsByNormThenByProductAndCopiesTheRest() {
        db("old.db") { c ->
            c.exec("INSERT INTO store(name) VALUES ('s')")
            c.exec("INSERT INTO game(id, norm, title, created_at) VALUES (1, 'catan', 'Catan', 1), (2, 'azul', 'Azul', 1), (3, 'gone', 'Gone', 1)")
            product(c, 10, "s.cl/catan", 1, "catan")
            product(c, 20, "s.cl/azul", 2, "azul")
            c.exec("INSERT INTO watchlist(game_id, target, added_at) VALUES (1, 30000, 5), (3, NULL, 5)")
            c.exec("INSERT INTO owned(game_id, added_at) VALUES (2, 6)")
            c.exec("INSERT INTO cart(game_id, qty, added_at, product_id) VALUES (1, 2, 7, 10)")
            c.exec("INSERT INTO store_fav(store) VALUES ('s')")
            c.exec("INSERT INTO cursor(name, ts) VALUES ('review', 99)")
        }.close()

        db("new.db") { c ->
            c.exec("INSERT INTO store(name) VALUES ('s')")
            c.exec("INSERT INTO scrape_run(store, ts, n_products, success) VALUES ('s', 500, 2, 1)")
            // New ids; Azul regrouped under another norm, still reachable by its product URL.
            c.exec("INSERT INTO game(id, norm, title, created_at) VALUES (7, 'catan', 'Catan', 1), (8, 'azul juego', 'Azul', 1)")
            product(c, 70, "s.cl/catan", 7, "catan")
            product(c, 80, "s.cl/azul", 8, "azul juego")
        }.use { c ->
            assertEquals(1, carryUserState(c, dir.resolve("old.db")))
            assertEquals(listOf(mapOf("game_id" to 7L, "target" to 30000.0)),
                c.query("SELECT game_id, target FROM watchlist"))
            assertEquals(listOf(mapOf("game_id" to 8L)), c.query("SELECT game_id FROM owned"))
            assertEquals(listOf(mapOf("game_id" to 7L, "qty" to 2L, "product_id" to 70L)),
                c.query("SELECT game_id, qty, product_id FROM cart"))
            assertEquals(1, c.query("SELECT * FROM store_fav").size)
            assertEquals(99L, c.query("SELECT ts FROM cursor WHERE name = 'review'").single()["ts"])
            assertEquals(500L, catalogStamp(c))
            assertEquals(0, c.query("PRAGMA database_list").count { it["name"] == "old" })
        }
    }

    @Test
    fun unreachableIsLatestRunFailedOrNeverRun() = BundledSQLiteDriver().open(":memory:").use { c ->
        initDb(c)
        c.exec("""INSERT INTO scrape_run(store, ts, n_products, success) VALUES
                  ('ok', 1, 5, 0), ('ok', 2, 5, 1), ('blocked', 1, 5, 1), ('blocked', 2, 0, 0)""")
        assertEquals(listOf("blocked", "new"), unreachableStores(c, listOf("ok", "blocked", "new")))
    }
}
