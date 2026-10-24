package com.zobo.fogdrive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zobo.fogdrive.data.TripEntity
import com.zobo.fogdrive.geo.Area
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordScreen(viewModel: MainViewModel, onShowTrip: (TripEntity) -> Unit) {
    val summary by viewModel.summary.collectAsState()
    val trips by viewModel.trips.collectAsState()
    val fmt = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }

    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        ScreenTitle("나의 개척 기록", "Lv.${summary.level} · 경험치 ${summary.xp}")
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("밝힌 면적", Area.format(summary.areaKm2 * 1_000_000), Modifier.weight(1f), FogColors.Accent, Icons.Outlined.Landscape)
            StatTile("주행 거리", formatKm(trips.sumOf { it.distanceMeters }), Modifier.weight(1f), FogColors.TextMain, Icons.Outlined.Route)
            StatTile("하차 지점", "${summary.landings}곳", Modifier.weight(1f), FogColors.Amber, Icons.Outlined.Flag)
            StatTile("새 길 비율", formatPercent(summary.newRoadRatio), Modifier.weight(1f), FogColors.Blue, Icons.Outlined.AltRoute)
        }
        SectionHeader("주행 기록") {
            Text("눌러서 지도에서 경로 보기", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
        }
        if (trips.isEmpty()) {
            FogCard(Modifier.fillMaxWidth(), outlined = true) {
                Text("아직 주행 기록이 없어요. 시동을 걸고 달리면 자동으로 기록돼요.", color = FogColors.TextSub)
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(trips, key = { it.id }) { trip ->
                FogCard(Modifier.fillMaxWidth(), padding = 20.dp, onClick = { onShowTrip(trip) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        IconBadge(Icons.Outlined.Route, FogColors.Accent, size = 44.dp)
                        Text(fmt.format(Date(trip.startedAt)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(formatKm(trip.distanceMeters), style = MaterialTheme.typography.bodyLarge, color = FogColors.TextSub)
                        Pill("+${Area.formatCells(trip.newCells)}", FogColors.Accent)
                        val ratio = if (trip.distanceMeters > 0) trip.newDistanceMeters / trip.distanceMeters else 0.0
                        Pill("새 길 ${formatPercent(ratio)}", FogColors.Blue)
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = FogColors.TextSub)
                    }
                }
            }
        }
    }
}
