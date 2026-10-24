package com.zobo.fogdrive.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object FogColors {
    val Bg = Color(0xFF0E1218)
    val Surface = Color(0xFF161C25)
    val SurfaceHigh = Color(0xFF1F2733)
    val Accent = Color(0xFF5EE6B8)
    val Amber = Color(0xFFFFB547)
    val Blue = Color(0xFF7FB2FF)
    val TextMain = Color(0xFFF1F4F8)
    val TextSub = Color(0xFF9AA6B5)
}

@Composable
fun FogTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FogColors.Accent,
            onPrimary = Color(0xFF00281C),
            background = FogColors.Bg,
            surface = FogColors.Surface,
            surfaceVariant = FogColors.SurfaceHigh,
            onSurface = FogColors.TextMain,
            onSurfaceVariant = FogColors.TextSub,
        ),
        // Car display: everything a size up from phone defaults.
        typography = Typography(
            displaySmall = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold),
            headlineMedium = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 22.sp),
            bodyMedium = TextStyle(fontSize = 20.sp),
            labelLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        ),
        content = content,
    )
}
