package com.zobo.fogdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun FogCard(
    modifier: Modifier = Modifier,
    color: Color = FogColors.Surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(color)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color = FogColors.TextMain) {
    FogCard(modifier) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
        Text(value, style = MaterialTheme.typography.headlineMedium, color = accent)
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, color: Color = FogColors.Accent) {
    Box(
        modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(FogColors.SurfaceHigh)
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(color)
        )
    }
}

fun formatKm(meters: Double): String = if (meters >= 1000) "%.1f km".format(meters / 1000) else "${meters.toInt()} m"

fun formatPercent(ratio: Double): String = "%.0f%%".format(ratio * 100)
