package com.zobo.fogdrive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zobo.fogdrive.car.Place
import com.zobo.fogdrive.geo.Area
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val focus by viewModel.mapFocus.collectAsState()
    // Opened from the dex: frame that region instead of chasing the car.
    var follow by remember { mutableStateOf(viewModel.mapFocus.value == null) }
    val shownTrip by viewModel.shownTrip.collectAsState()
    val showWornRoads by viewModel.showWornRoads.collectAsState()
    // A region or trip picked elsewhere may arrive after the tab switch; stop following the car then.
    LaunchedEffect(focus) { if (focus != null) follow = false }
    var zoomIn by remember { mutableIntStateOf(0) }
    var zoomOut by remember { mutableIntStateOf(0) }
    var recenter by remember { mutableIntStateOf(0) }
    // Dismissal is per destination: the suggested point moves with every fix, so keying on it never sticks.
    var dismissedFor by remember { mutableStateOf<Place?>(null) }
    var detourMessage by remember { mutableStateOf<String?>(null) }
    val suggestion by viewModel.suggestion.collectAsState()
    val hereStory by viewModel.hereStory.collectAsState()
    var suggestionHidden by remember { mutableStateOf(false) }
    // Keyed on the place, so "안내를 시작했어요" doesn't stay on a different 동 after the card moves on.
    var suggestionMessage by remember(suggestion?.district, suggestion?.name) { mutableStateOf<String?>(null) }

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
            route = shownTrip,
            showWornRoads = showWornRoads,
        )

        // Header: level and where the car is in the region dex. The nationwide count is in the dex
        // and this trip's progress on the driving view, so the map keeps to the essentials.
        Column(Modifier.padding(24.dp).align(Alignment.TopStart), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FogCard(
            modifier = Modifier.width(560.dp),
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
                hereStory?.let { st ->
                    Text(
                        "동네 이야기 · ${st.title}: ${st.line}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FogColors.TextSub,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("동 ${formatRate(h.dong.rate)}", FogColors.Accent)
                    Pill("${h.district.name} ${formatRate(h.district.rate)}", FogColors.Blue)
                    Pill("${h.province.name} ${formatRate(h.province.rate)}", FogColors.TextSub)
                }
            }
        }
        // Notices (a stamp, a drop-off, a reset) briefly, right under the header: never over it.
        EventToast(live.lastEvent, live.lastEventId, viewModel::eventShown, Modifier.widthIn(max = 560.dp))
        }

        DemoControls(viewModel, Modifier.padding(24.dp).align(Alignment.TopEnd))

        shownTrip?.let { t ->
            FogCard(
                modifier = Modifier.padding(24.dp).width(620.dp).align(Alignment.BottomStart),
                color = FogColors.Surface.copy(alpha = 0.97f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(Icons.Outlined.Route, FogColors.Amber, size = 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            SimpleDateFormat("M월 d일 HH:mm 주행", Locale.KOREA).format(Date(t.trip.startedAt)),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            "${formatKm(t.trip.distanceMeters)} · +${Area.formatCells(t.trip.newCells, t.points.firstOrNull()?.first ?: lat)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FogColors.TextSub,
                        )
                    }
                    TextButton(onClick = { viewModel.shownTrip.value = null }) {
                        Text("닫기", style = MaterialTheme.typography.titleMedium, color = FogColors.Accent)
                    }
                }
                if (!t.isTrack) {
                    Text(
                        if (t.points.isEmpty()) "이 주행은 경로 기록 기능 전이라 표시할 경로가 없어요."
                        else "경로 기록 기능 전의 주행이라 이때 새로 밝힌 곳만 점으로 보여 줘요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FogColors.TextSub,
                    )
                }
            }
        }

        // Just-finished trip: its summary takes the corner until closed (or the next trip starts).
        val lastTrip = live.lastTrip?.takeIf { vehicle.parked && shownTrip == null }
        lastTrip?.let {
            TripSummaryCard(it, onClose = { viewModel.dismissTripSummary() }, Modifier.padding(24.dp).width(640.dp).align(Alignment.BottomStart))
        }

        // Cards switched off in Settings, or dismissed with "오늘 안 보기" today, stay hidden.
        // Per composition entry, not per frame: the cards re-check the day when the map is reopened.
        val today = remember { MapPrefs.today() }
        val detourOn by viewModel.detourCard.enabled.collectAsState()
        val detourHiddenOn by viewModel.detourCard.hiddenOn.collectAsState()
        val suggestionOn by viewModel.suggestionCard.enabled.collectAsState()
        val suggestionHiddenOn by viewModel.suggestionCard.hiddenOn.collectAsState()
        val detourAllowed = detourOn && detourHiddenOn != today
        val showDetour = detourAllowed && visibleDetour != null && destination != null && vehicle.parked && shownTrip == null && lastTrip == null

        // "Go explore": nearest unexplored 동, while parked with nothing else in that corner.
        val showSuggestion = suggestion != null && !suggestionHidden && suggestionOn && suggestionHiddenOn != today &&
            vehicle.parked && shownTrip == null && !showDetour && lastTrip == null
        if (showSuggestion) suggestion?.let { sg ->
            FogCard(
                modifier = Modifier.padding(24.dp).width(640.dp).align(Alignment.BottomStart),
                color = FogColors.Surface.copy(alpha = 0.97f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(Icons.Outlined.TravelExplore, FogColors.Accent, size = 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text("안 가본 동네 가보기", style = MaterialTheme.typography.titleSmall, color = FogColors.Accent)
                        Text("${sg.district} ${sg.name} · ${formatKm(sg.distanceM)}", style = MaterialTheme.typography.titleLarge)
                    }
                    TextButton(onClick = { viewModel.suggestionCard.hideToday() }) {
                        Text("오늘 안 보기", color = FogColors.TextSub, style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { suggestionHidden = true }) {
                        Text("닫기", color = FogColors.TextSub, style = MaterialTheme.typography.titleMedium)
                    }
                }
                suggestionMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub) }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            suggestionMessage = if (viewModel.routeToSuggestion(sg)) "내비 안내를 시작했어요" else "내비 연결이 없어 안내를 시작하지 못했어요"
                        },
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FogColors.Accent, contentColor = FogColors.OnAccent),
                    ) { Text("내비 안내", modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) }
                    OutlinedButton(onClick = { viewModel.mapFocus.value = sg.bounds; follow = false }, shape = RoundedCornerShape(4.dp)) {
                        Text("지도에서 보기", color = FogColors.TextMain, modifier = Modifier.padding(vertical = 6.dp))
                    }
                    OutlinedButton(onClick = { suggestionMessage = null; viewModel.nextSuggestion() }, shape = RoundedCornerShape(4.dp)) {
                        Text("다른 곳", color = FogColors.TextMain, modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }

        // Detour suggestion (shown while parked, before departure).
        if (showDetour && visibleDetour != null && destination != null) {
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
                vehicle.rangeKm?.let { km ->
                    Text("주행 가능 거리 ${km.toInt()} km · 돌아오는 길까지 충분해요", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
                }
                detourMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = FogColors.Accent) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = {
                            detourMessage = when {
                                // The scripted drive keeps its own path; say so instead of implying a reroute.
                                live.demo -> "시연에서는 내비에 경유지를 보내지 않고 시연 경로를 그대로 달려요"
                                viewModel.acceptDetour(visibleDetour) -> "경유지로 추가했어요"
                                else -> "내비 연결이 없어 추가하지 못했어요"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = FogColors.Accent, contentColor = FogColors.OnAccent),
                    ) { Text("경유지 추가", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                    TextButton(onClick = { dismissedFor = destination }) {
                        Text("괜찮아요", color = FogColors.TextSub, modifier = Modifier.padding(8.dp))
                    }
                    TextButton(onClick = { viewModel.detourCard.hideToday() }) {
                        Text("오늘 안 보기", color = FogColors.TextSub, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomEnd).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            WornRoadsButton(showWornRoads) { viewModel.setShowWornRoads(!showWornRoads) }
            MapButton(Icons.Outlined.Add, "확대", onClick = { zoomIn++ })
            MapButton(Icons.Outlined.Remove, "축소", onClick = { zoomOut++ })
            MapButton(Icons.Outlined.MyLocation, "내 위치", tint = FogColors.Accent, onClick = {
                follow = true
                recenter++
            })
        }
    }
}
