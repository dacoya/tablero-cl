package cl.tablero.app

import cl.tablero.app.ui.Browse
import cl.tablero.app.ui.browseFromJson
import cl.tablero.app.ui.toJson
import cl.tablero.core.data.Filters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

// Here, not a JVM unit test: org.json is only a stub off the device.
@RunWith(AndroidJUnit4::class)
class BrowseJsonTest {
    @Test
    fun roundTrips() {
        val full = Browse("price", Filters(kinds = listOf("game"),
            inStockOnly = true, onSale = true, minPrice = 1000.0, maxPrice = 50000.0, minDiscount = 20.0,
            flag = "new", preorder = true, bggCategory = "partygames_rank", bggTop = 500,
            hideOwned = true, ownedExpansions = true, onePerGame = true))
        assertEquals(full, browseFromJson(full.toJson()))
        assertEquals(Browse(), browseFromJson(Browse().toJson()))
        // A store's catalogue is its own screen; the main one never keeps a store.
        assertEquals(Browse(), browseFromJson(Browse(filters = Filters(stores = listOf("buho"))).toJson()))
        assertEquals(Browse(), browseFromJson("""{"sort":"savings","stores":["buho"]}"""))
        // The category becomes part of the SQL: an unknown one does not survive the read.
        assertEquals(Browse(), browseFromJson("""{"sort":"savings","bggCategory":"rank; DROP TABLE game"}"""))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(browseFromJson(""))
        assertNull(browseFromJson("{}"))
    }
}
