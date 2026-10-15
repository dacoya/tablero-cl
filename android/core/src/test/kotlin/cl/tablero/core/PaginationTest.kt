package cl.tablero.core

import cl.tablero.core.scrape.buildUrl
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PaginationTest {
    @Test
    fun matchesEveryUrlVector() {
        val vectors = corpus("url_vectors.json").jsonArray.map { it.jsonObject }
        assertTrue(vectors.isNotEmpty())
        val wrong = vectors.mapNotNull { v ->
            fun s(k: String) = v.getValue(k).jsonPrimitive.content
            val got = buildUrl(s("base_url"), s("pagination"), v.getValue("page").jsonPrimitive.int)
            if (got == s("url")) null else "${s("pagination")} p${v["page"]}: $got != ${s("url")}"
        }
        assertEquals(emptyList(), wrong.take(5))
    }

    @Test
    fun unknownStyleFailsOnlyPastPageOne() {
        assertEquals("https://x.cl", buildUrl("https://x.cl", "nope", 1))
        assertFailsWith<IllegalArgumentException> { buildUrl("https://x.cl", "nope", 2) }
    }
}
