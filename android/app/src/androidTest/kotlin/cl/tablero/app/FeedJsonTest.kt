package cl.tablero.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import cl.tablero.app.ui.FeedData
import cl.tablero.app.ui.HOME_TILES
import cl.tablero.app.ui.SHELVES
import cl.tablero.app.ui.decode
import cl.tablero.app.ui.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

// Here, not a JVM unit test: org.json is only a stub off the device.
@RunWith(AndroidJUnit4::class)
class FeedJsonTest {
    @Test
    fun keepsEveryValueType() {
        // Long ids must come back as Long, a whole-number price as Double: the screens cast them exactly.
        val row = mapOf("product_id" to 7L, "price_eff" to 25000.0, "title" to "l:not a long", "image_url" to null)
        val feed = FeedData(List(HOME_TILES.size) { it.toLong() }, List(SHELVES.size) { listOf(row) })
        assertEquals("3:1:99" to feed, decode(encode("3:1:99", feed)))
    }

    @Test
    fun refusesAFeedOfAnotherShape() {
        val feed = FeedData(listOf(1L), listOf(emptyList()))
        assertThrows(IllegalArgumentException::class.java) { decode(encode("k", feed)) }
    }
}
