package com.zobo.fogdrive.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Built-in apps use near-square panels and buttons. */
val CardShape = RoundedCornerShape(6.dp)
private val SmallShape = RoundedCornerShape(4.dp)

/**
 * The one card used everywhere. [outlined] draws only a border (for locked / empty items) so filled
 * cards stand out; [onClick] makes the whole card the touch target.
 */
@Composable
fun FogCard(
    modifier: Modifier = Modifier,
    color: Color = FogColors.Surface,
    outlined: Boolean = false,
    padding: Dp = 24.dp,
    /** Filled card with a coloured frame (e.g. an unlocked dex entry). */
    borderColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var m = modifier.clip(CardShape)
    m = when {
        outlined -> m.border(2.dp, FogColors.Outline, CardShape)
        borderColor != null -> m.background(color).border(2.dp, borderColor, CardShape)
        else -> m.background(color)
    }
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** Screen title with an optional one-line explanation under it. */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = FogColors.TextMain)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = FogColors.TextSub) }
    }
}

/** Section heading inside a screen, with room for an action on the right. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = FogColors.TextMain, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Label then value (QA reads them as consecutive text nodes), with an optional icon above. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = FogColors.TextMain,
    icon: ImageVector? = null,
) {
    FogCard(modifier) {
        icon?.let { IconBadge(it, accent) }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
        Text(value, style = MaterialTheme.typography.headlineMedium, color = accent)
    }
}

/** Small tinted circle holding an icon. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 48.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f)) }
}

/** Compact rounded label, e.g. a rate or a status. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier.clip(SmallShape).background(color.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, color: Color = FogColors.Accent, height: Dp = 12.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(SmallShape)
            .background(FogColors.SurfaceHigh)
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .clip(SmallShape)
                .background(color)
        )
    }
}

/** Square floating button over the map (zoom, my location). */
@Composable
fun MapButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = FogColors.TextMain) {
    // Like the navigation app's map buttons: square, white, hairline border.
    Surface(
        onClick = onClick,
        shape = SmallShape,
        color = FogColors.Surface,
        border = BorderStroke(1.dp, FogColors.Outline),
        shadowElevation = 2.dp,
        modifier = Modifier.size(88.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(40.dp))
        }
    }
}

fun formatKm(meters: Double): String = if (meters >= 1000) "%.1f km".format(meters / 1000) else "${meters.toInt()} m"

fun formatPercent(ratio: Double): String = "%.0f%%".format(ratio * 100)

/** Region achievement rates are tiny next to 100 %, so keep two significant-ish digits. */
fun formatRate(ratio: Double): String {
    val pct = ratio * 100
    return when {
        pct <= 0.0 -> "0%"
        pct < 0.01 -> "<0.01%"
        pct < 1 -> "%.2f%%".format(pct)
        pct < 10 -> "%.1f%%".format(pct)
        else -> "%.0f%%".format(pct)
    }
}
