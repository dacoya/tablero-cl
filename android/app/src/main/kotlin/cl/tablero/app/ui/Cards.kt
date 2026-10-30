package cl.tablero.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cl.tablero.app.KIND_LABELS
import cl.tablero.app.isWebUrl
import cl.tablero.app.money
import cl.tablero.app.pct
import cl.tablero.core.data.SqlRow
import coil3.compose.AsyncImage

/*
 * The shop-window pieces: photo cards for grids and carousels, and the small
 * coloured tags on them.
 */

/**
 * A product photo on white, the way stores shoot them. Web URLs only: an
 * imported database is a file from outside, and a file:// or content:// value
 * in it must not have the image loader read the phone's own storage.
 */
@Composable
fun Photo(url: Any?, modifier: Modifier = Modifier, crop: Boolean = true) = AsyncImage(
    model = (url as? String)?.takeIf(::isWebUrl),
    contentDescription = null,
    contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
    modifier = modifier.background(Color.White),
)

/** A small pill: "-35%", "Nuevo", "Agotado". */
@Composable
fun Tag(text: String, background: Color, color: Color = Color.White, modifier: Modifier = Modifier) =
    Surface(color = background, contentColor = color, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }

/** The tags in a photo's corners: discount top-left, novelty and stock top-right. */
@Composable
private fun Corners(discount: String?, flag: Any?, inStock: Any?) {
    Box(Modifier.fillMaxWidth().padding(8.dp)) {
        if (discount != null) Tag(discount, Sale, modifier = Modifier.align(Alignment.TopStart))
        Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (flag) {
                "new" -> Tag("Nuevo", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
                "restock" -> Tag("Repuesto", MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary)
            }
            if (inStock == 0L) Tag("Agotado", Color(0xCC222222))
        }
    }
}

/** A card's text block: where (and what, when not a plain game), two lines of title, the price line. */
@Composable
private fun CardText(label: String, kind: Any?, title: String, price: @Composable () -> Unit) {
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(listOfNotNull(label, KIND_LABELS[kind]).joinToString(" · ").uppercase(),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, minLines = 2,
            overflow = TextOverflow.Ellipsis)
        price()
    }
}

/** One store's listing, as a browse row returns it. */
@Composable
fun ProductCard(row: SqlRow, open: (GameRef) -> Unit, modifier: Modifier = Modifier) {
    val gameId = row["game_id"] as Long?
    val title = row["title"] as String
    Card(
        onClick = { open(GameRef(gameId!!, title, row["image_url"] as String?)) }, enabled = gameId != null,
        modifier = modifier, colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box {
            Photo(row["image_url"], Modifier.fillMaxWidth().aspectRatio(1f))
            Corners(pct(row["discount_pct"]), row["flag"], row["in_stock"])
        }
        CardText(row["store"] as String, row["kind"], title) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(money(row["price_eff"]), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                if (row["discount_pct"] != null) {
                    Text(money(row["price_original"]), style = MaterialTheme.typography.labelSmall,
                        textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp))
                }
            }
        }
    }
}

/** One game across stores, as search returns it: the cheapest price and how many sell it. */
@Composable
fun GameCard(hit: SqlRow, open: (GameRef) -> Unit, modifier: Modifier = Modifier) {
    val title = hit["title"] as String
    val stores = hit["n_stores"] as Long
    val flag = when { hit["is_new"] == 1L -> "new"; hit["is_restock"] == 1L -> "restock"; else -> null }
    Card(
        onClick = { open(GameRef(hit["game_id"] as Long, title, hit["image_url"] as String?)) },
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box {
            Photo(hit["image_url"], Modifier.fillMaxWidth().aspectRatio(1f))
            Corners(null, flag, hit["in_stock"])
        }
        CardText("$stores tienda${if (stores == 1L) "" else "s"}", hit["kind"], title) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("desde", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
                Text(money(hit["min_price"]), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** A section heading with an optional "Ver todo" on the right. */
@Composable
fun SectionTitle(text: String, onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        if (onMore != null) TextButton(onClick = onMore) { Text("Ver todo") }
    }
}
