package cl.tablero.core

import cl.tablero.core.data.CartItem
import cl.tablero.core.data.CartOffer
import cl.tablero.core.data.dueStores
import cl.tablero.core.data.pinned
import cl.tablero.core.data.planCart
import kotlin.test.Test
import kotlin.test.assertEquals

class CartTest {
    private var nextId = 1L
    private fun item(title: String, vararg offers: Pair<String, Double>, qty: Int = 1) = CartItem(
        nextId++, title, null, qty,
        offers.map { (store, price) -> CartOffer(store, price, nextId++, title, null) },
    )

    @Test
    fun aPickedOfferIsKeptWhileItSells() {
        val catan = item("Catan", "a" to 30_000.0, "b" to 45_000.0)
        val atB = catan.offers.last()
        assertEquals(listOf(atB), pinned(catan.offers, atB.productId))
        assertEquals(catan.offers, pinned(catan.offers, null))
        assertEquals(catan.offers, pinned(catan.offers, -1L)) // picked offer gone: back to all
    }

    @Test
    fun oneStoreWinsOnShipping() {
        // Cheapest per game is a+b, but two shipments cost more than buying both at a.
        val catan = item("Catan", "a" to 30_000.0, "b" to 32_000.0)
        val azul = item("Azul", "a" to 25_000.0, "b" to 24_000.0)
        val plan = planCart(listOf(catan, azul), mapOf("a" to 4_000.0, "b" to 4_000.0))
        assertEquals(listOf("a"), plan.orders.map { it.store })
        assertEquals(59_000.0, plan.total)
    }

    @Test
    fun splittingWinsWhenTheGapIsBig() {
        val catan = item("Catan", "a" to 30_000.0, "b" to 45_000.0)
        val azul = item("Azul", "a" to 40_000.0, "b" to 24_000.0, qty = 2)
        val plan = planCart(listOf(catan, azul), mapOf("a" to 4_000.0, "b" to 4_000.0))
        assertEquals(setOf("a", "b"), plan.orders.map { it.store }.toSet())
        assertEquals(30_000.0 + 48_000.0 + 8_000.0, plan.total)
    }

    @Test
    fun gamesWithoutStockStayOut() {
        val gone = item("Agotado")
        val azul = item("Azul", "a" to 25_000.0)
        val plan = planCart(listOf(gone, azul), emptyMap())
        assertEquals(listOf(gone), plan.unavailable)
        assertEquals(25_000.0, plan.total)
    }

    @Test
    fun dueByCadence() {
        val day = 86_400L
        val now = 100 * day
        val last = mapOf("fav" to now - day, "slow" to now - 3 * day, "old" to now - 8 * day, "failed" to now - 2 * day)
        val due = dueStores(listOf("fav", "slow", "old", "new", "failed"), setOf("fav", "failed"), last,
            favDays = 1, otherDays = 7, now = now)
        // "new" was never scraped; "failed" last succeeded two days ago, so it is retried.
        assertEquals(listOf("fav", "old", "new", "failed"), due)
    }
}
