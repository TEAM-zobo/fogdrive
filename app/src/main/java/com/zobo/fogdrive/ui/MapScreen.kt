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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.zobo.fogdrive.car.Place
import com.zobo.fogdrive.geo.Area

@Composable
fun MapScreen(viewModel: MainViewModel) {
    val version by viewModel.version.collectAsState()
    val live by viewModel.live.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val landings by viewModel.landings.collectAsState()
    val detour by viewModel.detour.collectAsState()
    val destination by viewModel.navi.destination.collectAsState()
    val vehicle by viewModel.vehicle.collectAsState()
    val here by viewModel.here.collectAsState()
    val regions by viewModel.regions.collectAsState()
    // Every live fix recomposes this screen; count the ~3,500 동 only when the tally changes.
    val nationwide = remember(regions) {
        regions?.let { r ->
            r.sumOf { p -> p.districts.sumOf { it.visitedDongs } } to r.sumOf { p -> p.districts.sumOf { it.dongs.size } }
        }
    }
    val tripNewDongs by viewModel.tripNewDongs.collectAsState()
    val focus by viewModel.mapFocus.collectAsState()
    // Opened from the dex: frame that region instead of chasing the car.
    var follow by remember { mutableStateOf(viewModel.mapFocus.value == null) }
    var zoomIn by remember { mutableIntStateOf(0) }
    var zoomOut by remember { mutableIntStateOf(0) }
    var recenter by remember { mutableIntStateOf(0) }
    // Dismissal is per destination: the suggested point moves with every fix, so keying on it never sticks.
    var dismissedFor by remember { mutableStateOf<Place?>(null) }
    var detourMessage by remember { mutableStateOf<String?>(null) }

    val current = live.location?.let { it.lat to it.lng }
    val lat = live.location?.lat ?: 37.5
    val pins = remember(landings) { landings.map { it.lat to it.lng } }
    val visibleDetour = detour?.takeIf { destination == null || destination != dismissedFor }

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
            zoomInSignal = zoomIn,
            zoomOutSignal = zoomOut,
            focus = focus,
            onFocusShown = { viewModel.mapFocus.value = null },
        )

        // Header: level, where the car is in the region dex, and this trip.
        FogCard(
            modifier = Modifier.padding(24.dp).width(560.dp).align(Alignment.TopStart),
            color = FogColors.Surface.copy(alpha = 0.95f),
            padding = 24.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Lv.${summary.level}", style = MaterialTheme.typography.headlineMedium, color = FogColors.Accent)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("밝힌 면적 ${Area.format(summary.areaKm2 * 1_000_000)}", style = MaterialTheme.typography.titleMedium)
                    ProgressBar(summary.levelProgress, height = 10.dp)
                }
            }
            here?.let { h ->
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Place, contentDescription = null, tint = FogColors.TextSub, modifier = Modifier.size(28.dp))
                    Text("${h.province.name} ${h.district.name} ${h.dong.name}", style = MaterialTheme.typography.titleMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("동 ${formatRate(h.dong.rate)}", FogColors.Accent)
                    Pill("${h.district.name} ${formatRate(h.district.rate)}", FogColors.Blue)
                    Pill("${h.province.name} ${formatRate(h.province.rate)}", FogColors.TextSub)
                }
            }
            nationwide?.let { (visited, dongs) ->
                Text("전국 읍·면·동 $visited / $dongs 개척", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
            }
            if (live.tripActive) {
                Text(
                    "이번 주행 +${Area.formatCells(live.trip.newCells, lat)} · 새 동 ${tripNewDongs}곳 · ${formatKm(live.trip.distanceMeters)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FogColors.Amber,
                )
            }
            live.lastEvent?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub) }
        }

        if (live.demo) {
            Pill("시연 모드 주행 중", FogColors.Amber, Modifier.padding(24.dp).align(Alignment.TopEnd))
        }

        // Detour suggestion (shown while parked, before departure).
        if (visibleDetour != null && destination != null && vehicle.parked) {
            FogCard(
                modifier = Modifier.padding(24.dp).width(620.dp).align(Alignment.BottomStart),
                color = FogColors.Surface.copy(alpha = 0.97f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(Icons.Outlined.AltRoute, FogColors.Blue, size = 44.dp)
                    Text("탐험 우회 경로", style = MaterialTheme.typography.titleSmall, color = FogColors.Blue)
                }
                Text(
                    "${visibleDetour.extraMinutes}분 더 쓰면 안 가본 동네를 지나요",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "${destination!!.name} 가는 길 · 안개 ${Area.formatCells(visibleDetour.foggedCells, lat)} · 약 ${formatKm(visibleDetour.extraMeters)} 추가",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FogColors.TextSub,
                )
                detourMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = FogColors.Accent) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = {
                            detourMessage = if (viewModel.acceptDetour(visibleDetour)) "경유지로 추가했어요" else "내비 연결이 없어 추가하지 못했어요"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = FogColors.Accent, contentColor = FogColors.OnAccent),
                    ) { Text("경유지 추가", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                    TextButton(onClick = { dismissedFor = destination }) {
                        Text("괜찮아요", color = FogColors.TextSub, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomEnd).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MapButton(Icons.Outlined.Add, "확대", onClick = { zoomIn++ })
            MapButton(Icons.Outlined.Remove, "축소", onClick = { zoomOut++ })
            MapButton(Icons.Outlined.MyLocation, "내 위치", tint = FogColors.Accent, onClick = {
                follow = true
                recenter++
            })
        }
    }
}
