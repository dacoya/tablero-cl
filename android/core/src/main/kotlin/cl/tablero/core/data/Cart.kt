package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection

/*
 * The shopping cart: games the user wants and how many, and the cheapest way
 * to buy them across stores once shipping is counted. App-only, like owned.
 */

fun cartAdd(conn: SQLiteConnection, gameId: Long, now: Long = System.currentTimeMillis() / 1000) =
    conn.exec("INSERT OR IGNORE INTO cart(game_id, qty, added_at) VALUES (?, 1, ?)", gameId, now)

/** Put the game in the cart bought from this offer, or move it there if it is already in. */
fun cartPick(conn: SQLiteConnection, gameId: Long, productId: Long, now: Long = System.currentTimeMillis() / 1000) =
    conn.exec("""INSERT INTO cart(game_id, qty, added_at, product_id) VALUES (?, 1, ?, ?)
        ON CONFLICT(game_id) DO UPDATE SET product_id = excluded.product_id""", gameId, now, productId)

/** The offer the user picked for this game in the cart; null when not in it or left to the cheapest. */
fun cartPin(conn: SQLiteConnection, gameId: Long): Long? =
    conn.query("SELECT product_id FROM cart WHERE game_id = ?", gameId).firstOrNull()?.get("product_id") as Long?

/** Back to wherever the total comes out lowest. */
fun cartUnpin(conn: SQLiteConnection, gameId: Long) =
    conn.exec("UPDATE cart SET product_id = NULL WHERE game_id = ?", gameId)

fun cartRemove(conn: SQLiteConnection, gameId: Long) = conn.exec("DELETE FROM cart WHERE game_id = ?", gameId)

/** Set how many; zero or less takes the game out. */
fun cartSetQty(conn: SQLiteConnection, gameId: Long, qty: Int) =
    if (qty <= 0) cartRemove(conn, gameId)
    else conn.exec("UPDATE cart SET qty = ? WHERE game_id = ?", qty, gameId)

fun inCart(conn: SQLiteConnection, gameId: Long): Boolean =
    conn.query("SELECT 1 AS x FROM cart WHERE game_id = ?", gameId).isNotEmpty()

fun cartCount(conn: SQLiteConnection): Long =
    conn.query("SELECT COALESCE(SUM(qty), 0) AS n FROM cart").single()["n"] as Long

data class CartOffer(val store: String, val price: Double, val productId: Long, val title: String, val url: String?)

/**
 * A cart line with the offers it can be bought from: in stock, with a price.
 * `pin` is the offer the user picked, if any.
 */
data class CartItem(
    val gameId: Long, val title: String, val image: String?, val qty: Int, val offers: List<CartOffer>,
    val pin: Long? = null,
) {
    /** What the plan may buy: only the picked offer while it sells, otherwise any. */
    val choices get() = pinned(offers, pin)

    /** The pick is in force (it was made, and that offer still sells). */
    val picked get() = offers.any { it.productId == pin }
}

data class CartPick(val item: CartItem, val offer: CartOffer) {
    val cost get() = offer.price * item.qty
}

/** One store's part of the purchase. A null shipping is a store whose cost the user never entered. */
data class StoreOrder(val store: String, val picks: List<CartPick>, val shipping: Double?) {
    val subtotal get() = picks.sumOf { it.cost }
    val total get() = subtotal + (shipping ?: 0.0)
}

data class CartPlan(val orders: List<StoreOrder>, val unavailable: List<CartItem>) {
    val total get() = orders.sumOf { it.total }
}

/** The cart with each game's live offers. */
fun cartItems(conn: SQLiteConnection): List<CartItem> = conn.query("""
    SELECT c.game_id, c.qty, c.product_id, g.title,
           (SELECT MIN(p.image_url) FROM product p WHERE p.game_id = g.id) AS image_url
      FROM cart c JOIN game g ON g.id = c.game_id
     ORDER BY c.added_at, c.game_id
""").map { row ->
    val id = row["game_id"] as Long
    val offers = gamePrices(conn, id)
        .filter { (it["price_eff"] as Number?)?.toDouble()?.let { p -> p > 0 } == true && it["in_stock"] != 0L }
        .map {
            CartOffer(it["store"] as String, (it["price_eff"] as Number).toDouble(), it["product_id"] as Long,
                it["title"] as String, it["url"] as String?)
        }
    CartItem(id, row["title"] as String, row["image_url"] as String?, (row["qty"] as Long).toInt(), offers,
        row["product_id"] as Long?)
}

/** Only the picked offer while it can be bought; otherwise every offer, cheapest wins. */
fun pinned(offers: List<CartOffer>, productId: Long?): List<CartOffer> =
    offers.filter { it.productId == productId }.ifEmpty { offers }

private fun cost(picks: Map<CartItem, CartOffer>, shipping: Map<String, Double>): Double =
    picks.entries.sumOf { (item, offer) -> offer.price * item.qty } +
        picks.values.map { it.store }.distinct().sumOf { shipping[it] ?: 0.0 }

/** `picks` with `drop`'s games moved to their cheapest of `others`; null when one is sold only there. */
private fun without(picks: Map<CartItem, CartOffer>, drop: String, others: Set<String>): Map<CartItem, CartOffer>? =
    picks.mapValues { (item, offer) ->
        if (offer.store != drop) offer
        else item.choices.filter { it.store in others }.minByOrNull { it.price } ?: return null
    }

/**
 * Which store to buy each game from so the total, shipping included, is
 * lowest. Every game starts at its cheapest offer; then, while it pays, one
 * store at a time is dropped and its games move to the cheapest of the
 * stores still used, saving that store's shipping.
 *
 * ponytail: greedy, not guaranteed optimal; an exact search over subsets of
 * stores if carts grow past ~20 games and the totals look off.
 */
fun planCart(items: List<CartItem>, shipping: Map<String, Double>): CartPlan {
    val (buyable, unavailable) = items.partition { it.offers.isNotEmpty() }
    var picks: Map<CartItem, CartOffer> = buyable.associateWith { item -> item.choices.minBy { it.price } }

    // Each round drops the store whose removal saves the most, not the first that saves anything.
    while (true) {
        val used = picks.values.map { it.store }.toSet()
        val best = used.sorted().mapNotNull { drop -> without(picks, drop, used - drop) }
            .minByOrNull { cost(it, shipping) }
        if (best == null || cost(best, shipping) >= cost(picks, shipping)) break
        picks = best
    }

    val orders = picks.entries.groupBy({ it.value.store }, { CartPick(it.key, it.value) })
        .map { (store, list) -> StoreOrder(store, list, shipping[store]) }
        .sortedByDescending { it.total }
    return CartPlan(orders, unavailable)
}
