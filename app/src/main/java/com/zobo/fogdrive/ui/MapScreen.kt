package com.zobo.fogdrive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MapScreen(viewModel: MainViewModel) {
    val version by viewModel.version.collectAsState()
    val live by viewModel.live.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val landings by viewModel.landings.collectAsState()
    val detour by viewModel.detour.collectAsState()
    val destination by viewModel.navi.destination.collectAsState()
    val vehicle by viewModel.vehicle.collectAsState()
    var follow by remember { mutableStateOf(true) }
    var recenter by remember { mutableIntStateOf(0) }
    var dismissedDetour by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var detourMessage by remember { mutableStateOf<String?>(null) }

    val current = live.location?.let { it.lat to it.lng }
    val pins = remember(landings) { landings.map { it.lat to it.lng } }
    val visibleDetour = detour?.takeIf { (it.lat to it.lng) != dismissedDetour }

    Box(Modifier.fillMaxSize()) {
        FogMap(
            modifier = Modifier.fillMaxSize(),
            version = version,
            current = current,
            landings = pins,
            detour = visibleDetour?.takeIf { vehicle.parked }?.let { it.lat to it.lng },
            follow = follow,
            recenterSignal = recenter,
            onUserMoved = { follow = false },
        )

        // Header: level + live progress.
        FogCard(
            modifier = Modifier.padding(28.dp).width(520.dp).align(Alignment.TopStart),
            color = FogColors.Surface.copy(alpha = 0.94f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Lv.${summary.level}", style = MaterialTheme.typography.headlineMedium, color = FogColors.Accent)
                Text("개척 ${summary.totalCells}칸 · %.2f km²".format(summary.areaKm2), style = MaterialTheme.typography.titleMedium)
            }
            ProgressBar(summary.levelProgress)
            summary.zoneRate?.let {
                Text("현재 2km 구역 정복률 %.1f%%".format(it), style = MaterialTheme.typography.bodyLarge, color = FogColors.TextSub)
            }
            if (live.tripActive) {
                Text(
                    "이번 주행 새 칸 ${live.trip.newCells} · ${formatKm(live.trip.distanceMeters)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FogColors.Amber,
                )
            }
            live.lastEvent?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub) }
        }

        if (live.demo) {
            FogCard(
                modifier = Modifier.padding(28.dp).align(Alignment.TopEnd),
                color = FogColors.Amber.copy(alpha = 0.92f),
            ) { Text("시연 모드 주행 중", style = MaterialTheme.typography.titleMedium, color = FogColors.Bg) }
        }

        // Detour suggestion (shown while parked, before departure).
        if (visibleDetour != null && destination != null && vehicle.parked) {
            FogCard(
                modifier = Modifier.padding(28.dp).width(620.dp).align(Alignment.BottomStart),
                color = FogColors.Surface.copy(alpha = 0.96f),
            ) {
                Text("탐험 우회 경로", style = MaterialTheme.typography.bodyLarge, color = FogColors.Blue)
                Text(
                    "${visibleDetour.extraMinutes}분 더 쓰면 안 가본 동네를 지나요",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "${destination!!.name} 가는 길 · 안개 ${visibleDetour.foggedCells}칸 · 약 ${formatKm(visibleDetour.extraMeters)} 추가",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FogColors.TextSub,
                )
                detourMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = FogColors.Accent) }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = {
                            detourMessage = if (viewModel.acceptDetour(visibleDetour)) "경유지로 추가했어요" else "내비 연결이 없어 추가하지 못했어요"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = FogColors.Accent),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) { Text("경유지 추가", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                    TextButton(onClick = { dismissedDetour = visibleDetour.lat to visibleDetour.lng }) {
                        Text("괜찮아요", color = FogColors.TextSub, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomEnd).padding(28.dp)) {
            FloatingActionButton(
                onClick = {
                    follow = true
                    recenter++
                },
                containerColor = FogColors.SurfaceHigh,
                modifier = Modifier.size(96.dp),
            ) { Icon(Icons.Outlined.MyLocation, contentDescription = "내 위치", tint = FogColors.Accent, modifier = Modifier.size(44.dp)) }
        }
    }
}
