package cl.tablero.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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

/** The effective price, with the struck list price and discount under it when on sale. */
@Composable
fun Price(row: Map<String, Any?>) {
    Column(horizontalAlignment = Alignment.End) {
        Text(money(row["price_eff"]), fontWeight = FontWeight.SemiBold)
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

@Composable
fun Empty(text: String) =
    Text(text, modifier = Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)

fun stock(inStock: Any?): String? = when (inStock) {
    0L -> "agotado"
    null -> "?"
    else -> null
}
