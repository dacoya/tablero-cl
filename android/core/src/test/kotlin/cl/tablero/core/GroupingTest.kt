package cl.tablero.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cl.tablero.core.data.Scraped
import cl.tablero.core.data.foldMap
import cl.tablero.core.data.indelRatio
import cl.tablero.core.data.ingestStore
import cl.tablero.core.data.initDb
import cl.tablero.core.data.query
import cl.tablero.core.data.sameGame
import cl.tablero.core.data.tokenSortRatio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mirrors tests/test_grouping.py; scores are rapidfuzz's own output. */
class GroupingTest {
    @Test
    fun scoresMatchRapidfuzz() {
        assertEquals(95.45454545454545, tokenSortRatio("king of tokio mind bug", "king of tokyo mind bug"), 1e-9)
        assertEquals(92.3076923076923, indelRatio("wonder", "wonders"), 1e-9)
    }

    @Test
    fun foldsTyposAndWordOrderOnly() {
        assertTrue(sameGame("7 wonder duel", "7 wonders duel"))
        assertTrue(sameGame("adivina quien pokemon", "pokemon adivina quien"))
        assertFalse(sameGame("pandemic legacy season 1", "pandemic legacy season 2"))
        assertFalse(sameGame("age steam deluxe volume ii", "age steam deluxe volume iii"))
        assertFalse(sameGame("catan navegantes", "catan navegantes plus"))
        assertTrue(sameGame("king tokio mind bug", "king tokyo mind bug"))
        assertFalse(sameGame("clans", "clank"))
        assertFalse(sameGame("bandido fractal", "bandida fractal"))
        assertEquals(
            mapOf("king of tokio mind bug" to "king of tokyo mind bug"),
            foldMap(listOf("king of tokyo mind bug", "king of tokio mind bug"), emptySet()),
        )
    }

    @Test
    fun ingestFoldsATypoIntoTheKnownGame() {
        val conn = BundledSQLiteDriver().open(":memory:")
        initDb(conn)
        ingestStore(conn, "a", listOf(Scraped("Marvel Champions: Civil War", "https://a.cl/p/mc", "$10.000")))
        ingestStore(conn, "b", listOf(Scraped("Marvel Champion Civil War", "https://b.cl/p/mc", "$9.000")))
        assertEquals(1, conn.query("SELECT DISTINCT game_id FROM product").size)
    }
}
