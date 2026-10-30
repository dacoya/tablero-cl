package cl.tablero.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cl.tablero.core.data.Filters
import cl.tablero.core.data.Scraped
import cl.tablero.core.data.ingestStore
import cl.tablero.core.data.initDb
import cl.tablero.core.data.products
import cl.tablero.core.data.countProducts
import cl.tablero.core.data.countProductsEach
import kotlin.test.Test
import kotlin.test.assertEquals

class PreorderTest {
    /** Any spelling, case or bracketing of the tag counts; a plain title does not. */
    @Test
    fun matchesTagVariants() = BundledSQLiteDriver().open(":memory:").use { conn ->
        initDb(conn)
        val titles = listOf("PREVENTA: Nyakuza", "Azul Duel (Preventa)", "Catan [pre-order]",
            "Pre Venta - Coraquest", "Reserva El Hobbit", "Monopoly Todo a la Venta")
        ingestStore(conn, "a", titles.mapIndexed { i, t -> Scraped(t, "https://a.cl/$i", "$10.000") }, ts = 1)
        val hits = products(conn, "title", Filters(preorder = true)).map { it["title"] }.toSet()
        assertEquals(titles.dropLast(1).toSet(), hits)
    }
}

class CountEachTest {
    /** One scan must give each filter set the same count as counting it alone. */
    @Test
    fun matchesSeparateCounts() = BundledSQLiteDriver().open(":memory:").use { conn ->
        initDb(conn)
        ingestStore(conn, "a", listOf(Scraped("Catan", "https://a.cl/1", "$10.000"),
            Scraped("PREVENTA Azul", "https://a.cl/2", "$20.000"),
            Scraped("Fundas Catan", "https://a.cl/3", "$1.000")), ts = 1)
        ingestStore(conn, "a", listOf(Scraped("Catan", "https://a.cl/1", "$10.000")), ts = 2)
        val sets = listOf(Filters(), Filters(preorder = true), Filters(kinds = listOf("accessory")),
            Filters(maxPrice = 15000.0))
        assertEquals(sets.map { countProducts(conn, it) }, countProductsEach(conn, sets))
        val stale = sets.map { it.copy(includeStale = true) }
        assertEquals(stale.map { countProducts(conn, it) }, countProductsEach(conn, stale))
    }
}
