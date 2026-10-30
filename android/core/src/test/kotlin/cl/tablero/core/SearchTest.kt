package cl.tablero.core

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cl.tablero.core.data.Scraped
import cl.tablero.core.data.ingestStore
import cl.tablero.core.data.initDb
import cl.tablero.core.data.likeEscape
import cl.tablero.core.data.searchGames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchTest {
    private fun catalog(): SQLiteConnection = BundledSQLiteDriver().open(":memory:").also { conn ->
        initDb(conn)
        fun rec(title: String, price: String, slug: String) = Scraped(title, "https://a.cl/p/$slug", price)
        ingestStore(conn, "a", listOf(
            rec("Catan Expansión Navegantes", "$34.990", "catan-nav"),
            rec("Catan - Juego de Mesa", "$45.990", "catan"),
            rec("Fundas para Catan", "$3.990", "fundas"),
            rec("Terraforming Mars", "$45.000", "tm"),
            rec("Mars Open", "$9.990", "mars-open"),
            rec("Juego_raro 100%", "$1.000", "raro"),
            rec("Pandemic Legacy", "$59.990", "pandemic-legacy"),
        ), ts = 1)
        ingestStore(conn, "b", listOf(Scraped("Catan", "https://b.cl/catan", "$42.000")), ts = 1)
    }

    private fun titles(conn: SQLiteConnection, q: String, accessories: Boolean = false) =
        searchGames(conn, q, includeAccessories = accessories).map { it["title"] }

    @Test
    fun exactGameFirstAccessoriesHidden() = catalog().use { conn ->
        assertEquals(listOf("Catan", "Catan Expansión Navegantes"), titles(conn, "catan"))
        val hit = searchGames(conn, "catan").first()
        assertEquals(2L, hit["n_stores"])
        assertEquals(42000.0, hit["min_price"])
        assertTrue("Fundas para Catan" in titles(conn, "catan", accessories = true))
    }

    @Test
    fun matchesWordPrefixesNotSubstrings() = catalog().use { conn ->
        assertEquals(listOf("Terraforming Mars"), titles(conn, "terra"))
        assertEquals(setOf("Terraforming Mars", "Mars Open"), titles(conn, "mars").toSet())
        assertEquals(emptyList(), titles(conn, "erra"))
        assertEquals(listOf("Terraforming Mars"), titles(conn, "Mars Terra"))
    }

    @Test
    fun typosFallBackToTheClosestGames() = catalog().use { conn ->
        assertEquals(listOf("Pandemic Legacy"), titles(conn, "pandemc"))
        assertEquals("Catan", titles(conn, "catn").first())
        assertEquals(emptyList(), titles(conn, "zzzzzz"))
    }

    @Test
    fun likeWildcardsInTheQueryAreLiteral() = catalog().use { conn ->
        // normalize keeps "_" as a word character; unescaped it would match any one char.
        assertEquals(listOf("Juego_raro 100%"), titles(conn, "juego_raro"))
        // Escaping is checked directly: the typo fallback would find "juego_r_ro"
        // anyway, so a result-level test could not tell a wildcard from a typo.
        assertEquals("a\\_b\\%c\\\\", likeEscape("a_b%c\\"))
        assertEquals(emptyList(), titles(conn, ""))
    }
}
