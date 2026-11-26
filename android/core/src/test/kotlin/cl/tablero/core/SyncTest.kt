package cl.tablero.core

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cl.tablero.core.data.carryUserState
import cl.tablero.core.data.catalogStamp
import cl.tablero.core.data.exec
import cl.tablero.core.data.initDb
import cl.tablero.core.data.query
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Personal tables survive a catalogue download whose ids all moved. */
class SyncTest {
    private val dir: File = Files.createTempDirectory("sync").toFile()

    private fun db(name: String, block: (SQLiteConnection) -> Unit): SQLiteConnection =
        BundledSQLiteDriver().open(dir.resolve(name).path).also { initDb(it); block(it) }

    private fun product(c: SQLiteConnection, id: Long, url: String, gameId: Long, norm: String, store: String = "s") = c.exec(
        """INSERT INTO product(id, store, url_canon, url, game_id, title_raw, title, norm, first_seen, last_seen)
           VALUES (?, ?, ?, ?, ?, 'X', 'X', ?, 1, 1)""", id, store, url, "https://$url", gameId, norm)

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
    fun aStoreScrapedOnThePhoneAfterTheDownloadKeepsThePhoneRows() {
        db("phone.db") { c ->
            c.exec("INSERT INTO store(name) VALUES ('s'), ('t')")
            c.exec("INSERT INTO scrape_run(store, ts, n_products, success) VALUES ('s', 900, 2, 1), ('t', 100, 1, 1)")
            c.exec("INSERT INTO game(id, norm, title, created_at) VALUES (1, 'catan', 'Catan', 1), (2, 'solo phone', 'Solo Phone', 900)")
            product(c, 10, "s.cl/catan", 1, "catan")
            product(c, 11, "s.cl/solo", 2, "solo phone")
            product(c, 12, "t.cl/catan", 1, "catan", "t")
            c.exec("INSERT INTO price_obs(product_id, ts, price) VALUES (10, 100, 30000), (10, 900, 25000), (11, 900, 9000), (12, 100, 1)")
            c.exec("INSERT INTO cart(game_id, qty, added_at, product_id) VALUES (2, 1, 950, 11)")
        }.close()

        db("download.db") { c ->
            c.exec("INSERT INTO store(name) VALUES ('s'), ('t')")
            // The workflow reached t again, but s last succeeded long ago and failed since.
            c.exec("INSERT INTO scrape_run(store, ts, n_products, success) VALUES ('s', 100, 1, 1), ('s', 1000, NULL, 0), ('t', 1000, 1, 1)")
            c.exec("INSERT INTO game(id, norm, title, created_at) VALUES (7, 'catan', 'Catan', 1)")
            product(c, 70, "s.cl/catan", 7, "catan")
            product(c, 72, "t.cl/catan", 7, "catan", "t")
            c.exec("INSERT INTO price_obs(product_id, ts, price) VALUES (70, 100, 30000), (72, 1000, 2)")
        }.use { c ->
            assertEquals(0, carryUserState(c, dir.resolve("phone.db")))
            assertEquals(listOf("s.cl/catan", "s.cl/solo", "t.cl/catan"),
                c.query("SELECT url_canon FROM product ORDER BY url_canon").map { it["url_canon"] })
            assertEquals(listOf(25000.0, 9000.0), c.query("""
                SELECT o.price FROM price_obs o JOIN product p ON p.id = o.product_id
                 WHERE p.store = 's' AND o.ts = 900 ORDER BY p.url_canon""").map { it["price"] })
            // The phone-only game exists here now, and the cart still points at its product.
            val cart = c.query("""SELECT g.norm, p.url_canon FROM cart JOIN game g ON g.id = cart.game_id
                                   JOIN product p ON p.id = cart.product_id""").single()
            assertEquals(mapOf("norm" to "solo phone", "url_canon" to "s.cl/solo"), cart)
            // s keeps the phone's runs; t, which the workflow reached later, keeps the download's.
            assertEquals(listOf(mapOf("store" to "s", "ts" to 900L), mapOf("store" to "t", "ts" to 1000L)),
                c.query("SELECT store, ts FROM scrape_run ORDER BY store, ts"))
            assertEquals(2.0, c.query("SELECT price FROM price_obs o JOIN product p ON p.id = o.product_id WHERE p.store = 't'").single()["price"])
        }
    }
}
