package cl.tablero.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cl.tablero.app.count
import cl.tablero.core.data.SqlRow
import kotlin.math.log10

/*
 * The BGG headline under a game's name: geek rating, overall rank and best
 * category rank, each in a hexagon coloured from red (bad) through blue to
 * green (good). A value BGG does not have is a light brown "?".
 */

private val Good = Color(0xFF2E7D32)
private val Middle = Color(0xFF1565C0)
private val Bad = Color(0xFFC62828)
private val Unknown = Color(0xFFC8A27A)

// Ranges the colour scale spans; values past either end take its end colour.
// Geek ratings sit between ~5.5 (unloved) and ~8.5 (top of the chart).
private const val RATING_BAD = 5.5
private const val RATING_GOOD = 8.0
private const val RANK_GOOD = 10.0
private const val RANK_BAD = 20_000.0
private const val CATEGORY_RANK_GOOD = 5.0
private const val CATEGORY_RANK_BAD = 2_000.0

// CSV column -> label, as on BGG.
private val CATEGORIES = listOf(
    "strategygames_rank" to "Estrategia", "familygames_rank" to "Familiar", "partygames_rank" to "Party",
    "thematic_rank" to "Temático", "abstracts_rank" to "Abstracto", "childrensgames_rank" to "Infantil",
    "wargames_rank" to "Wargame", "cgs_rank" to "Coleccionable",
)

// Width over height of a regular flat-sided hexagon (2 / sqrt 3).
private const val HEX_RATIO = 1.1547f

/** Flat-sided hexagon, points left and right. */
private val Hexagon = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    moveTo(w * 0.25f, 0f)
    lineTo(w * 0.75f, 0f)
    lineTo(w, h / 2)
    lineTo(w * 0.75f, h)
    lineTo(w * 0.25f, h)
    lineTo(0f, h / 2)
    close()
}

/** 0 = bad, 1 = good, onto red -> blue -> green. */
private fun scale(t: Double): Color {
    val x = t.coerceIn(0.0, 1.0).toFloat()
    return if (x < 0.5f) lerp(Bad, Middle, x * 2) else lerp(Middle, Good, (x - 0.5f) * 2)
}

private fun linear(v: Double, bad: Double, good: Double) = (v - bad) / (good - bad)

/** Ranks are judged on a log scale: #10 vs #100 matters as much as #1.000 vs #10.000. */
private fun logRank(rank: Double, good: Double, bad: Double) = linear(log10(rank), log10(bad), log10(good))

private class Badge(val value: String?, val label: String, val score: Double?)

private fun badges(bgg: SqlRow?): List<Badge> {
    val rating = (bgg?.get("bayesaverage") as? Number)?.toDouble()
    val rank = (bgg?.get("rank") as? Number)?.toDouble()
    val category = CATEGORIES
        .mapNotNull { (key, label) -> (bgg?.get(key) as? Number)?.toDouble()?.let { label to it } }
        .minByOrNull { it.second }
    return listOf(
        Badge(rating?.let { "%.1f".format(java.util.Locale.ROOT, it) }, "Nota geek",
            rating?.let { linear(it, RATING_BAD, RATING_GOOD) }),
        Badge(rank?.let { "#${count(it)}" }, "Ranking", rank?.let { logRank(it, RANK_GOOD, RANK_BAD) }),
        Badge(category?.let { "#${count(it.second)}" }, category?.first ?: "Categoría",
            category?.let { logRank(it.second, CATEGORY_RANK_GOOD, CATEGORY_RANK_BAD) }),
    )
}

@Composable
fun BggBadges(bgg: SqlRow?, modifier: Modifier = Modifier) {
    // Equal thirds of the width, so the three badges fill the row on any screen.
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        badges(bgg).forEach { Hex(it, Modifier.weight(1f)) }
    }
}

@Composable
private fun Hex(badge: Badge, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(HEX_RATIO)
                .background(badge.score?.let(::scale) ?: Unknown, Hexagon),
            contentAlignment = Alignment.Center,
        ) {
            Text(badge.value ?: "?", color = Color.White, fontWeight = FontWeight.Black, maxLines = 1,
                style = if ((badge.value?.length ?: 1) > 5) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.headlineMedium)
        }
        Text(badge.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp))
    }
}
