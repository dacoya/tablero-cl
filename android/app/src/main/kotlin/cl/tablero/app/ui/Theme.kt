package cl.tablero.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/*
 * The app's own colours, not the wallpaper's: a warm orange shop front, teal
 * and purple as accents. Fixed so the photos, badges and prices always sit on
 * the same palette.
 */

/** Discount badges: the one colour that means "cheaper than usual". */
val Sale = Color(0xFFE5194A)

/** Home's header, the same in light and dark: the brand, not a surface. */
val Brand = listOf(Color(0xFFD9480F), Color(0xFF6A3FC0))

/** "Disponible": in stock at that store. */
val InStock = Color(0xFF2E7D32)

// A price against the game's cheapest in-stock listing: green at it, amber
// halfway, red at RED_AT above it. Dark enough to read on white and on dark.
private val PriceGood = InStock
private val PriceFair = Color(0xFFC08A00)
private val PriceBad = Color(0xFFC62828)

/** How far above the cheapest a price is fully red: 0.5 is 50 % more. Tune here. */
private const val RED_AT = 0.5

/**
 * The colour of a row's price against its "cheapest" (see withCheapest), or
 * null when there is nothing to compare: one store, no price, or out of stock.
 */
fun priceColor(row: Map<String, Any?>): Color? {
    val cheapest = row["cheapest"] as Double? ?: return null
    val price = (row["price_eff"] as Number?)?.toDouble()?.takeIf { it > 0 } ?: return null
    if (row["in_stock"] == 0L) return null
    val t = ((price / cheapest - 1) / RED_AT).coerceIn(0.0, 1.0).toFloat()
    return if (t < 0.5f) lerp(PriceGood, PriceFair, t * 2) else lerp(PriceFair, PriceBad, t * 2 - 1)
}

private val Light = lightColorScheme(
    primary = Color(0xFFD9480F), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCC), onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF00897B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2F1E8), onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF6A3FC0), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9DDFF), onTertiaryContainer = Color(0xFF22005D),
    background = Color(0xFFFFF8F5), onBackground = Color(0xFF231915),
    surface = Color(0xFFFFF8F5), onSurface = Color(0xFF231915),
    surfaceVariant = Color(0xFFF4DED4), onSurfaceVariant = Color(0xFF53433D),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFF1EB),
    surfaceContainer = Color(0xFFFCEAE3), surfaceContainerHigh = Color(0xFFF6E4DC),
    surfaceContainerHighest = Color(0xFFF0DED6), outlineVariant = Color(0xFFD8C2B9),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB59A), onPrimary = Color(0xFF5C1A00),
    primaryContainer = Color(0xFF832800), onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFF4FDBC9), onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005047), onSecondaryContainer = Color(0xFF70F8E5),
    tertiary = Color(0xFFCFBCFF), onTertiary = Color(0xFF3A0F8C),
    tertiaryContainer = Color(0xFF5227A6), onTertiaryContainer = Color(0xFFE9DDFF),
    background = Color(0xFF1A110E), onBackground = Color(0xFFF1DFD9),
    surface = Color(0xFF1A110E), onSurface = Color(0xFFF1DFD9),
    surfaceVariant = Color(0xFF53433D), onSurfaceVariant = Color(0xFFD8C2B9),
    surfaceContainerLowest = Color(0xFF140C09), surfaceContainerLow = Color(0xFF231915),
    surfaceContainer = Color(0xFF271D19), surfaceContainerHigh = Color(0xFF322823),
    surfaceContainerHighest = Color(0xFF3D322E), outlineVariant = Color(0xFF53433D),
)

@Composable
fun Theme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
