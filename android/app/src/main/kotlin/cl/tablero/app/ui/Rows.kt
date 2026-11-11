package cl.tablero.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cl.tablero.app.money
import cl.tablero.app.pct

/*
 * Shared pieces. Compact on purpose: one line of title, one line of facts,
 * prices right-aligned -- the terminal's tables, as a phone list.
 */

/**
 * Two lines, not one: one line cut four different "La Leyenda de los Cinco
 * Anillos LCG: ..." expansions to the same label, and rows that read the same
 * are useless however compact they are.
 */
@Composable
fun Title(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, maxLines = 2, overflow = TextOverflow.Ellipsis)

@Composable
fun Facts(text: String) =
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * The effective price, coloured against the other stores (see priceColor), with
 * the struck list price and discount under it when on sale.
 */
@Composable
fun Price(row: Map<String, Any?>) {
    Column(horizontalAlignment = Alignment.End) {
        Text(money(row["price_eff"]), fontWeight = FontWeight.SemiBold, color = priceColor(row) ?: Color.Unspecified)
        val discount = pct(row["discount_pct"])
        if (discount != null) Row {
            Text(money(row["price_original"]), style = MaterialTheme.typography.bodySmall,
                textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("  $discount", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Sale)
        }
    }
}

/** A small square photo for list rows. */
@Composable
fun Thumb(url: Any?, size: Dp = 56.dp) = Photo(url, Modifier.size(size).clip(RoundedCornerShape(8.dp)))

@Composable
fun Back(onBack: () -> Unit) = IconButton(onClick = onBack) {
    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
}

/** The heading every top-level screen opens with: the way back when pushed, the name, its actions. */
@Composable
fun ScreenTitle(text: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 16.dp else 4.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) Back(onBack)
        Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Nothing to show, said in the middle rather than as a stray line at the top. */
@Composable
fun Empty(text: String) =
    Text(text, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A query still running. */
@Composable
fun Loading(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

fun stock(inStock: Any?): String? = when (inStock) {
    0L -> "agotado"
    null -> "?"
    else -> null
}
