package com.zobo.fogdrive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordScreen(viewModel: MainViewModel) {
    val summary by viewModel.summary.collectAsState()
    val trips by viewModel.trips.collectAsState()
    val fmt = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)

    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("나의 개척 기록", style = MaterialTheme.typography.displaySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("개척한 칸", "${summary.totalCells}", Modifier.weight(1f), FogColors.Accent)
            StatTile("밝힌 면적", "%.2f km²".format(summary.areaKm2), Modifier.weight(1f))
            StatTile("상륙 지점", "${summary.landings}곳", Modifier.weight(1f), FogColors.Amber)
            StatTile("새 길 비율", formatPercent(summary.newRoadRatio), Modifier.weight(1f), FogColors.Blue)
        }
        Text("주행 기록", style = MaterialTheme.typography.titleLarge)
        if (trips.isEmpty()) {
            Text("아직 주행 기록이 없어요. 시동을 걸고 달리면 자동으로 기록돼요.", color = FogColors.TextSub)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(trips, key = { it.id }) { trip ->
                FogCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text(fmt.format(Date(trip.startedAt)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(formatKm(trip.distanceMeters), color = FogColors.TextSub)
                        Text("새 칸 ${trip.newCells}", color = FogColors.Accent)
                        val ratio = if (trip.distanceMeters > 0) trip.newDistanceMeters / trip.distanceMeters else 0.0
                        Text("새 길 ${formatPercent(ratio)}", color = FogColors.Blue)
                    }
                }
            }
        }
    }
}
