package com.zobo.burung.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zobo.burung.TripSummary
import com.zobo.burung.geo.Area

/** Shown on the parked map after a trip: what it cleared, the 동 it opened (with their stories), its stamps. */
@Composable
fun TripSummaryCard(summary: TripSummary, onClose: () -> Unit, modifier: Modifier = Modifier) {
    FogCard(modifier, color = FogColors.Surface.copy(alpha = 0.97f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Outlined.EmojiEvents, FogColors.Amber, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text("이번 주행 요약", style = MaterialTheme.typography.titleSmall, color = FogColors.Amber)
                Text(
                    "+${Area.formatCells(summary.newCells)} · ${formatKm(summary.distanceMeters)} · 새 길 ${formatPercent(summary.newRoadRatio)}",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            TextButton(onClick = onClose) {
                Text("닫기", style = MaterialTheme.typography.titleMedium, color = FogColors.Accent)
            }
        }
        if (summary.newDongs.isNotEmpty()) {
            Text("처음 가 본 동네 ${summary.newDongs.size}곳", style = MaterialTheme.typography.titleMedium, color = FogColors.Blue)
            // A long drive opens dozens of 동; the first few carry the story, the rest are named.
            summary.newDongs.take(MAX_STORIES).forEach { d ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Place, contentDescription = null, tint = FogColors.Blue)
                    Column {
                        Text("${d.district} ${d.name}", style = MaterialTheme.typography.bodyLarge)
                        d.story?.let {
                            Text("${it.title} · ${it.line}", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
                        }
                    }
                }
            }
            if (summary.newDongs.size > MAX_STORIES) {
                Text(
                    summary.newDongs.drop(MAX_STORIES).joinToString(", ") { it.name }.let { if (it.length > 80) it.take(79) + "…" else it },
                    style = MaterialTheme.typography.bodyMedium,
                    color = FogColors.TextSub,
                )
            }
        }
        if (summary.stamps.isNotEmpty()) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                summary.stamps.take(MAX_STAMP_PILLS).forEach { Pill(it.title, FogColors.Amber) }
                if (summary.stamps.size > MAX_STAMP_PILLS) Pill("+${summary.stamps.size - MAX_STAMP_PILLS}", FogColors.TextSub)
            }
        }
        summary.mission?.let { m ->
            Text(
                if (summary.missionDone) "동승자 미션 성공 · ${m.title()}" else "동승자 미션 · ${m.title()} (다음엔 꼭!)",
                style = MaterialTheme.typography.bodyMedium,
                color = if (summary.missionDone) FogColors.Accent else FogColors.TextSub,
            )
        }
    }
}

private const val MAX_STORIES = 3
private const val MAX_STAMP_PILLS = 4
