package app.photos.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Direction « Album » : papier chaud, encre brune, une seule couleur d'accent (brique) réservée
 * à la sélection et à l'action principale. Plus de couleurs tirées du fond d'écran.
 */
private val Paper = lightColorScheme(
    primary = Color(0xFF8F3B24), onPrimary = Color.White,
    primaryContainer = Color(0xFFF1E0D7), onPrimaryContainer = Color(0xFF5A2414),
    secondary = Color(0xFF6B665E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E3D8), onSecondaryContainer = Color(0xFF1F1D1A),
    tertiary = Color(0xFF8F3B24), onTertiary = Color.White,
    background = Color(0xFFF6F4EF), onBackground = Color(0xFF1F1D1A),
    surface = Color(0xFFF6F4EF), onSurface = Color(0xFF1F1D1A),
    surfaceVariant = Color(0xFFECE8E0), onSurfaceVariant = Color(0xFF6B665E),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF2EFE9),
    surfaceContainer = Color(0xFFF6F4EF), surfaceContainerHigh = Color(0xFFEDE9E2),
    surfaceContainerHighest = Color(0xFFE6E1D8),
    outline = Color(0xFF9A948A), outlineVariant = Color(0xFFE3DED5),
)

private val Darkroom = darkColorScheme(
    primary = Color(0xFFE3A08A), onPrimary = Color(0xFF3D1508),
    primaryContainer = Color(0xFF5A2414), onPrimaryContainer = Color(0xFFF6DDD2),
    secondary = Color(0xFFA59E93), onSecondary = Color(0xFF1F1D1A),
    secondaryContainer = Color(0xFF2E2A26), onSecondaryContainer = Color(0xFFECE7DF),
    tertiary = Color(0xFFE3A08A), onTertiary = Color(0xFF3D1508),
    background = Color(0xFF161412), onBackground = Color(0xFFECE7DF),
    surface = Color(0xFF161412), onSurface = Color(0xFFECE7DF),
    surfaceVariant = Color(0xFF2A2622), onSurfaceVariant = Color(0xFFA59E93),
    surfaceContainerLowest = Color(0xFF110F0E), surfaceContainerLow = Color(0xFF1A1816),
    surfaceContainer = Color(0xFF161412), surfaceContainerHigh = Color(0xFF221F1C),
    surfaceContainerHighest = Color(0xFF2B2824),
    outline = Color(0xFF6E685F), outlineVariant = Color(0xFF2C2925),
)

/** Dates et titres en serif, comme des légendes d'album ; l'interface reste en sans-serif. */
private val AlbumType = Typography().run {
    val serif = { s: androidx.compose.ui.text.TextStyle -> s.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal) }
    copy(
        displaySmall = serif(displaySmall),
        headlineLarge = serif(headlineLarge),
        headlineMedium = serif(headlineMedium).copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium),
        headlineSmall = serif(headlineSmall),
        titleLarge = serif(titleLarge),
    )
}

@Composable
fun AlbumTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Darkroom else Paper, typography = AlbumType, content = content)
}
