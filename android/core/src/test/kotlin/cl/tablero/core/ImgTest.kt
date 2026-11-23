package cl.tablero.core

import cl.tablero.core.scrape.img
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** parsers._img's placeholder/logo rule (test_images.py), which no saved page exercises. */
class ImgTest {
    private fun first(html: String) = img(Jsoup.parse(html).body(), "https://s.cl")

    @Test
    fun placeholdersAndStoreLogosAreNotPhotos() {
        assertEquals("https://s.cl/a.jpg", first(
            """<img src="https://s.cl/wp-content/uploads/woocommerce-placeholder-300x300.png"><img src="https://s.cl/a.jpg">"""))
        assertNull(first("""<img src="https://d.net/55673/3/M_logob5675.png?148&t=1">"""))
        assertNull(first("""<img src="https://s.cl/img/Logo-Calabozo.png">"""))
        assertEquals("https://s.cl/sherlock-tumba-arqueologo.jpg", first("""<img src="https://s.cl/sherlock-tumba-arqueologo.jpg">"""))
        assertEquals("https://s.cl/sleeves-guardians-logo.jpg", first("""<img src="https://s.cl/sleeves-guardians-logo.jpg">"""))
    }
}
