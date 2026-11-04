package cl.tablero.core

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cl.tablero.core.data.Filters
import cl.tablero.core.data.Scraped
import cl.tablero.core.data.bggLookup
import cl.tablero.core.data.bggMechanics
import cl.tablero.core.data.browse
import cl.tablero.core.data.countProducts
import cl.tablero.core.data.exec
import cl.tablero.core.data.gameBase
import cl.tablero.core.data.gameBgg
import cl.tablero.core.data.gameExpansions
import cl.tablero.core.data.importBggCsv
import cl.tablero.core.data.ingestStore
import cl.tablero.core.data.initDb
import cl.tablero.core.data.isOwned
import cl.tablero.core.data.offersByTotal
import cl.tablero.core.data.ownedAdd
import cl.tablero.core.data.ownedEntries
import cl.tablero.core.data.ownedRemove
import cl.tablero.core.data.products
import cl.tablero.core.data.query
import cl.tablero.core.data.shippingCosts
import cl.tablero.core.data.shippingSet
import cl.tablero.core.data.recordFailure
import cl.tablero.core.data.storeFreshness
import cl.tablero.core.data.withCheapest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionTest {
    private fun db(block: (SQLiteConnection) -> Unit) = BundledSQLiteDriver().open(":memory:").use { conn ->
        initDb(conn)
        ingestStore(conn, "a", listOf(
            Scraped("Skull", "https://a.cl/skull", "$20.000"),
            Scraped("Skull King", "https://a.cl/king", "$15.000"),
            Scraped("Skull King Pack de Expansión", "https://a.cl/king-exp", "$9.000"),
            Scraped("Catan", "https://a.cl/catan", "$40.000"),
            Scraped("Catan: Navegantes Expansión", "https://a.cl/nav", "$30.000"),
        ), ts = 100)
        ingestStore(conn, "b", listOf(Scraped("Catan", "https://b.cl/catan", "$35.000")), ts = 100)
        block(conn)
    }

    private fun SQLiteConnection.id(title: String) =
        query("SELECT id FROM game WHERE title = ?", title).single()["id"] as Long

    @Test
    fun aStoreWhoseLatestScrapeFailedIsHiddenUntilItRecovers() = db { conn ->
        val before = countProducts(conn, Filters(stores = listOf("b")))
        recordFailure(conn, "b", ts = 200)
        assertEquals(0L, countProducts(conn, Filters(stores = listOf("b"))))
        assertEquals(1L, conn.query("SELECT COUNT(*) AS n FROM product WHERE store = 'b'").single()["n"]) // kept
        ingestStore(conn, "b", listOf(Scraped("Catan", "https://b.cl/catan", "$35.000")), ts = 300)
        assertEquals(before, countProducts(conn, Filters(stores = listOf("b"))))
    }

    @Test
    fun anExpansionBelongsToItsLongestBaseGame() = db { conn ->
        assertEquals(listOf("Catan: Navegantes Expansión"), gameExpansions(conn, conn.id("Catan")).map { it["title"] })
        // Skull King is the longer match, so its expansion is not listed under Skull.
        assertEquals(emptyList(), gameExpansions(conn, conn.id("Skull")))
        assertEquals("Skull King", gameBase(conn, conn.id("Skull King Pack de Expansión"))?.get("title"))
        assertNull(gameBase(conn, conn.id("Catan")))
    }

    @Test
    fun ownedGamesLeaveShelvesAndTheirExpansionsSurface() = db { conn ->
        val catan = conn.id("Catan")
        assertFalse(isOwned(conn, catan))
        ownedAdd(conn, catan, now = 5)
        assertTrue(isOwned(conn, catan))
        assertEquals(listOf("Catan"), ownedEntries(conn).map { it["title"] })

        val hidden = products(conn, "title", Filters(hideOwned = true)).map { it["title"] }
        assertFalse("Catan" in hidden)
        assertEquals(listOf("Catan: Navegantes Expansión"),
            products(conn, "title", Filters(ownedExpansions = true)).map { it["title"] })

        // An expansion already owned is not suggested again.
        ownedAdd(conn, conn.id("Catan: Navegantes Expansión"), now = 6)
        assertEquals(0L, countProducts(conn, Filters(ownedExpansions = true)))

        ownedRemove(conn, catan)
        assertFalse(isOwned(conn, catan))
    }

    @Test
    fun onePerGameKeepsTheCheapestListingAndCountsGames() = db { conn ->
        val (rows, total) = browse(conn, "title", Filters(onePerGame = true))
        assertEquals(5L, total)
        assertEquals(5, rows.size)
        val catan = rows.single { it["title"] == "Catan" }
        assertEquals("b", catan["store"])
        assertEquals(35000.0, catan["price_eff"])
        assertEquals(6L, countProducts(conn))
    }

    @Test
    fun onePerGameAppliesFiltersBeforePickingTheCheapest() = db { conn ->
        // Over $36.000 the cheapest listing that qualifies is store a's, not b's.
        val rows = products(conn, "title", Filters(onePerGame = true, minPrice = 36000.0))
        assertEquals(listOf("a"), rows.map { it["store"] })
    }

    @Test
    fun bggFiltersMatchOnlyRankedGames() = db { conn ->
        conn.exec("""INSERT INTO bgg(id, name, norm, rank, usersrated, is_expansion, familygames_rank)
                     VALUES (1, 'Catan', 'catan', 400, 1000, 0, 12), (2, 'Skull', 'skull', 900, 1000, 0, NULL)""")
        conn.exec("UPDATE game SET bgg_id = 1 WHERE id = ?", conn.id("Catan"))
        conn.exec("UPDATE game SET bgg_id = 2 WHERE id = ?", conn.id("Skull"))
        assertEquals(2, countProducts(conn, Filters(bggCategory = "familygames_rank")))
        assertEquals(2, countProducts(conn, Filters(bggTop = 500)))
        assertEquals(3, countProducts(conn, Filters(bggTop = 1000)))
        assertEquals(0L, countProducts(conn, Filters(bggCategory = "partygames_rank")))
        // The column name goes into the SQL, so only known ones are accepted.
        assertFailsWith<IllegalArgumentException> { countProducts(conn, Filters(bggCategory = "rank; DROP TABLE game")) }
    }

    @Test
    fun editionWordsReachTheBaseEntryAndNothingElseDoes() = db { conn ->
        conn.exec("INSERT INTO bgg(id, name, norm, usersrated, is_expansion) VALUES (822, 'Carcassonne', 'carcassonne', 1000, 0)")
        assertEquals(822L, bggLookup(conn, "carcassonne deluxe"))
        assertEquals(822L, bggLookup(conn, "carcassonne big box"))
        assertNull(bggLookup(conn, "carcassonne 2 constructores"))
        assertNull(bggLookup(conn, "deluxe"))
    }

    @Test
    fun factFiltersMatchOnlyGamesWithFacts() = db { conn ->
        conn.exec("""INSERT INTO bgg(id, name, norm, usersrated, is_expansion)
                     VALUES (1, 'Catan', 'catan', 1000, 0), (2, 'Skull', 'skull', 1000, 0)""")
        conn.exec("INSERT INTO bgg_facts(bgg_id, min_players, max_players, play_time) VALUES (1, 3, 4, 120)")
        conn.exec("INSERT INTO bgg_mechanic(bgg_id, mechanic) VALUES (1, 'Trading'), (1, 'Dice Rolling')")
        conn.exec("UPDATE game SET bgg_id = 1 WHERE id = ?", conn.id("Catan"))
        conn.exec("UPDATE game SET bgg_id = 2 WHERE id = ?", conn.id("Skull")) // matched, no facts
        val catan = countProducts(conn, Filters(players = 4))
        assertTrue(catan > 0)
        assertEquals(catan, countProducts(conn, Filters(players = 3)))
        assertEquals(0L, countProducts(conn, Filters(players = 2)))
        assertEquals(catan, countProducts(conn, Filters(maxPlayTime = 120)))
        assertEquals(0L, countProducts(conn, Filters(maxPlayTime = 60)))
        assertEquals(catan, countProducts(conn, Filters(mechanic = "Trading")))
        assertEquals(0L, countProducts(conn, Filters(mechanic = "Bluffing")))
        assertEquals(listOf("Dice Rolling", "Trading"), bggMechanics(conn).sorted())
    }

    @Test
    fun gameBggCarriesFactsAndMechanics() = db { conn ->
        conn.exec("INSERT INTO bgg(id, name, norm, usersrated, is_expansion) VALUES (1, 'Catan', 'catan', 1000, 0)")
        conn.exec("INSERT INTO bgg_facts(bgg_id, min_players, complexity) VALUES (1, 3, 2.3)")
        conn.exec("INSERT INTO bgg_mechanic(bgg_id, mechanic) VALUES (1, 'Trading'), (1, 'Dice Rolling')")
        conn.exec("UPDATE game SET bgg_id = 1 WHERE id = ?", conn.id("Catan"))
        val row = gameBgg(conn, conn.id("Catan"))!!
        assertEquals(3L, row["min_players"])
        assertEquals(2.3, row["complexity"])
        assertEquals("Dice Rolling · Trading", row["mechanics"])
    }

    @Test
    fun totalWithShippingReordersOffersAndUnknownCostStaysThePrice() {
        val offers = listOf(
            mapOf("store" to "a", "price_eff" to 10000.0),
            mapOf("store" to "b", "price_eff" to 11000.0),
            mapOf("store" to "c", "price_eff" to 12000.0),
        )
        val sorted = offersByTotal(offers, mapOf("a" to 5000.0, "b" to 0.0))
        assertEquals(listOf("b", "c", "a"), sorted.map { it["store"] })
        assertEquals(listOf(11000.0, 12000.0, 15000.0), sorted.map { it["total"] })
        assertEquals(listOf(0.0, null, 5000.0), sorted.map { it["shipping"] })
    }

    @Test
    fun shippingCostsPersistAndClear() = db { conn ->
        shippingSet(conn, "a", 3000.0)
        assertEquals(mapOf("a" to 3000.0), shippingCosts(conn))
        shippingSet(conn, "a", 0.0)
        assertEquals(mapOf("a" to 0.0), shippingCosts(conn))
        shippingSet(conn, "a", null)
        assertEquals(emptyMap(), shippingCosts(conn))
    }

    @Test
    fun freshnessKeepsTheLastGoodScrapeAndReportsALaterFailure() = db { conn ->
        conn.exec("INSERT INTO scrape_run(store, ts, n_products, success) VALUES ('a', 200, 0, 0)")
        val fresh = storeFreshness(conn)
        assertEquals(100L, fresh.getValue("a").lastOk)
        assertTrue(fresh.getValue("a").failed)
        assertEquals(100L, fresh.getValue("b").lastOk)
        assertFalse(fresh.getValue("b").failed)
        assertTrue(fresh.getValue("a").dim(now = 100))
        assertFalse(fresh.getValue("b").dim(now = 100))
        assertTrue(fresh.getValue("b").dim(now = 100 + 4 * 86_400))
    }

    @Test
    fun bggCsvReplacesEntriesAliasesAndMatches() = db { conn ->
        // Skull's old match (an id the CSV keeps) survives with no name hit, as a fuzzy one would.
        conn.exec("INSERT INTO bgg(id, name, norm, usersrated, is_expansion) VALUES (92415, 'Skull', 'skull', 9000, 0)")
        conn.exec("UPDATE game SET bgg_id = 92415 WHERE id = ?", conn.id("Skull"))
        val csv = """
            id,name,yearpublished,rank,bayesaverage,average,usersrated,is_expansion,abstracts_rank,cgs_rank,childrensgames_rank,familygames_rank,partygames_rank,strategygames_rank,thematic_rank,wargames_rank,name_es,variants_es,name_es_source
            13,"Catan, the game",1995,600,6.9,7.1,140000,0,,,,120,,,,,Catan,Los Colonos de Catán|Colonos,manual
            92415,Skull & Roses,2011,300,7.0,7.2,9000,0,,,,,50,,,,,,
            99,Obscure,0,0,,,2,0,,,,,,,,,Calavera,,web
        """.trimIndent()
        val r = importBggCsv(conn, csv.lineSequence())
        assertEquals(2, r.entries)                  // Obscure is under MIN_VOTES
        assertEquals(3, r.aliases)                  // Calavera points at an entry not kept
        assertEquals(13L, conn.query("SELECT bgg_id FROM game WHERE id = ?", conn.id("Catan")).single()["bgg_id"])
        assertEquals(92415L, conn.query("SELECT bgg_id FROM game WHERE id = ?", conn.id("Skull")).single()["bgg_id"])
        assertEquals(2, r.matched)
        val catan = conn.query("SELECT * FROM bgg WHERE id = 13").single()
        assertEquals("Catan, the game", catan["name"]); assertEquals(120L, catan["familygames_rank"]); assertNull(catan["abstracts_rank"])
        assertFailsWith<IllegalArgumentException> { importBggCsv(conn, sequenceOf("a,b", "1,2")) }
        assertEquals(2L, conn.query("SELECT COUNT(*) AS n FROM bgg").single()["n"]) // a bad file changes nothing
    }

    @Test
    fun cheapestComparesOnlyGamesTwoStoresSell() = db { conn ->
        val rows = withCheapest(conn, products(conn, "title"))
        val byStore = rows.filter { it["title"] == "Catan" }.associate { it["store"] to it["cheapest"] }
        assertEquals<Map<Any?, Any?>>(mapOf("a" to 35000.0, "b" to 35000.0), byStore)
        assertNull(rows.single { it["title"] == "Skull" }["cheapest"])
    }
}
