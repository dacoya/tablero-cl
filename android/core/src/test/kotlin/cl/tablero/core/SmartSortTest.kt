package cl.tablero.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cl.tablero.core.data.Scraped
import cl.tablero.core.data.ingestStore
import cl.tablero.core.data.initDb
import cl.tablero.core.data.smartProducts
import kotlin.test.Test
import kotlin.test.assertEquals

class SmartSortTest {
    /** The cached per-game stats must follow a write on the same connection. */
    @Test
    fun statsFollowWrites() = BundledSQLiteDriver().open(":memory:").use { conn ->
        initDb(conn)
        ingestStore(conn, "a", listOf(Scraped("Catan", "https://a.cl/catan", "$40.000")), ts = 1)
        ingestStore(conn, "b", listOf(Scraped("Catan", "https://b.cl/catan", "$50.000")), ts = 1)
        assertEquals(45000.0, smartProducts(conn).first()["median"])

        ingestStore(conn, "b", listOf(Scraped("Catan", "https://b.cl/catan", "$60.000")), ts = 2)
        assertEquals(50000.0, smartProducts(conn).first()["median"])
    }

}
