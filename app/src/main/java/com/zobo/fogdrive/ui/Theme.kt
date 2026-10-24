package com.zobo.fogdrive.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class FogPalette(
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val accent: Color,
    val onAccent: Color,
    val amber: Color,
    val blue: Color,
    val textMain: Color,
    val textSub: Color,
)

/*
 * Colours sampled from the Pleos Connect built-in apps: near-white background, black for primary
 * elements, blue (#0081F8, the navigation's position arrow) as the one accent and orange (#FF9900,
 * the dock's active-app bar) for highlights. Night follows the built-in car settings (black, grey).
 */
private val NightPalette = FogPalette(
    bg = Color(0xFF000000),
    surface = Color(0xFF1A1A1A),
    surfaceHigh = Color(0xFF272727),
    outline = Color(0xFF3A3A3A),
    accent = Color(0xFF3D9BFF),
    onAccent = Color(0xFFFFFFFF),
    amber = Color(0xFFFF9900),
    blue = Color(0xFFB8BEC8),
    textMain = Color(0xFFFFFFFF),
    textSub = Color(0xFF9A9A9A),
)

private val DayPalette = FogPalette(
    bg = Color(0xFFF6F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEEF0F4),
    outline = Color(0xFFD9DDE3),
    accent = Color(0xFF0081F8),
    onAccent = Color(0xFFFFFFFF),
    amber = Color(0xFFEF8A00),
    blue = Color(0xFF4A5260),
    textMain = Color(0xFF111111),
    textSub = Color(0xFF7A808A),
)

private val LocalFogPalette = staticCompositionLocalOf { NightPalette }

/** App colours; they follow the system day/night setting, like the map style does. */
object FogColors {
    val Bg: Color @Composable @ReadOnlyComposable get() = LocalFogPalette.current.bg
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalFogPalette.current.surface
    val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalFogPalette.current.surfaceHigh
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalFogPalette.current.outline
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalFogPalette.current.accent
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalFogPalette.current.onAccent
    val Amber: Color @Composable @ReadOnlyComposable get() = LocalFogPalette.current.amber
    val Blue: Color @Composable @ReadOnlyComposable get() = LocalFogPalette.current.blue
    val TextMain: Color @Composable @ReadOnlyComposable get() = LocalFogPalette.current.textMain
    val TextSub: Color @Composable @ReadOnlyComposable get() = LocalFogPalette.current.textSub
}

/**
 * One type family for the whole app: the system font. Pleos maps the system sans-serif to 42dot's
 * AstaSans, so this is the built-in apps' font, shared by every app (no bundled font, no extra
 * memory). AstaSans has Regular/Medium/Bold/ExtraBold faces and Pleos maps weight 600 to Bold and
 * 700 to ExtraBold, so titles use Medium (500) and only the big numbers SemiBold (600 → Bold).
 */
private fun style(size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = FontFamily.Default, fontSize = size.sp, lineHeight = (size * 1.3).sp, fontWeight = weight)

private val CarTypography = Typography(
    displaySmall = style(40, FontWeight.SemiBold),
    headlineMedium = style(32, FontWeight.SemiBold),
    headlineSmall = style(28, FontWeight.Medium),
    titleLarge = style(26, FontWeight.Medium),
    titleMedium = style(22, FontWeight.Medium),
    titleSmall = style(20, FontWeight.Medium),
    bodyLarge = style(22),
    bodyMedium = style(20),
    bodySmall = style(18),
    labelLarge = style(20, FontWeight.Medium),
    labelMedium = style(18, FontWeight.Medium),
    labelSmall = style(16, FontWeight.Medium),
)

@Composable
fun FogTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) NightPalette else DayPalette
    val base = if (dark) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(LocalFogPalette provides p) {
        MaterialTheme(
            colorScheme = base.copy(
                primary = p.accent,
                onPrimary = p.onAccent,
                secondary = p.blue,
                tertiary = p.amber,
                background = p.bg,
                onBackground = p.textMain,
                surface = p.surface,
                onSurface = p.textMain,
                surfaceVariant = p.surfaceHigh,
                onSurfaceVariant = p.textSub,
                outline = p.outline,
                outlineVariant = p.outline,
            ),
            typography = CarTypography,
            content = content,
        )
    }
}
