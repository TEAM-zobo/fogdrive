package com.zobo.fogdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zobo.fogdrive.demo.DemoCourses
import com.zobo.fogdrive.demo.DemoDrive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onStartDemo: () -> Unit,
    onStopDemo: () -> Unit,
    onRequestBackground: () -> Unit,
) {
    // Only whether a demo plays: the full live status changes on every fix and would redraw it all.
    val demoRunning by viewModel.demoRunning.collectAsState()
    val demoPlaying by viewModel.demoPlaying.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDemoReset by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    val cloud by viewModel.cloud.collectAsState()
    val demoCourse by viewModel.demoCourse.collectAsState()
    val showWornRoads by viewModel.showWornRoads.collectAsState()
    val demoSpeed by viewModel.demoSpeed.collectAsState()
    val backupMessage by viewModel.backupMessage.collectAsState()
    val syncTime = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }

    Column(
        Modifier.fillMaxSize().padding(40.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SectionHeader("연결 상태")
        ConnectionCard(viewModel)

        SectionHeader("시연 모드", Modifier.padding(top = 16.dp))
        FogCard(Modifier.fillMaxWidth()) {
            Text(
                "코스를 골라 자동으로 달려요. 출발 전 우회 제안, 주행 중 안내, 도착 후 P단 + 도어 열림으로 하차 지점까지 기록돼요.",
                color = FogColors.TextSub,
            )
            DemoCourseSelect(viewModel, enabled = !demoPlaying, modifier = Modifier.fillMaxWidth())
            Text(DemoCourses.byId(demoCourse).summary, style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
            RoundTripRow(viewModel, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("차량 속도", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = demoSpeed.toFloat(),
                    onValueChange = { viewModel.setDemoSpeed((it / 10).roundToInt() * 10) },
                    valueRange = DemoDrive.MIN_KMH.toFloat()..DemoDrive.MAX_KMH.toFloat(),
                    steps = (DemoDrive.MAX_KMH - DemoDrive.MIN_KMH) / 10 - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = FogColors.Accent,
                        activeTrackColor = FogColors.Accent,
                        inactiveTrackColor = FogColors.SurfaceHigh,
                        activeTickColor = FogColors.OnAccent,
                        inactiveTickColor = FogColors.Outline,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text("$demoSpeed km/h", style = MaterialTheme.typography.titleMedium, color = FogColors.Accent)
            }
            Text("주행 중에도 바로 바뀌어요. 시연 시간은 실제보다 ${DemoDrive.SPEED_UP.toInt()}배 빠르게 흘러요.", style = MaterialTheme.typography.bodySmall, color = FogColors.TextSub)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    // A finished drive can be followed by another course right away.
                    onClick = onStartDemo,
                    enabled = !demoPlaying,
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FogColors.Accent, contentColor = FogColors.OnAccent),
                ) { Text("시연 시작", modifier = Modifier.padding(12.dp)) }
                OutlinedButton(onClick = onStopDemo, enabled = demoRunning, shape = RoundedCornerShape(4.dp)) {
                    Text("시연 중지", modifier = Modifier.padding(12.dp))
                }
                Spacer(Modifier.weight(1f))
                if (!confirmDemoReset) {
                    OutlinedButton(onClick = { confirmDemoReset = true }, shape = RoundedCornerShape(4.dp)) {
                        Text("기록 초기화", modifier = Modifier.padding(12.dp), color = FogColors.Amber)
                    }
                } else {
                    Button(
                        onClick = {
                            viewModel.resetAll()
                            confirmDemoReset = false
                        },
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FogColors.Amber, contentColor = FogColors.OnAccent),
                    ) { Text("모든 기록 지우기", modifier = Modifier.padding(12.dp)) }
                    OutlinedButton(onClick = { confirmDemoReset = false }, shape = RoundedCornerShape(4.dp)) {
                        Text("취소", modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }

        SectionHeader("지도 표시", Modifier.padding(top = 16.dp))
        FogCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("자주 다닌 길 표시", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "여러 번 달린 길을 파랗게 칠해요. 많이 달릴수록 진해져요. 지도와 주행 화면의 버튼으로도 켜고 끌 수 있어요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FogColors.TextSub,
                    )
                }
                FogSwitch(checked = showWornRoads, onCheckedChange = { viewModel.setShowWornRoads(it) })
            }
        }

        SectionHeader("데이터 동기화", Modifier.padding(top = 16.dp))
        FogCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconBadge(Icons.Outlined.CloudSync, FogColors.Accent)
                Column(Modifier.weight(1f)) {
                    Text("클라우드 동기화", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            cloud.syncing -> "동기화 중…"
                            cloud.error != null -> "동기화 실패: ${cloud.error}"
                            cloud.lastSyncAt != null -> "Pleos 계정 · 마지막 동기화 ${syncTime.format(Date(cloud.lastSyncAt!!))}"
                            else -> "Pleos 계정 · 아직 동기화하지 않았어요"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = FogColors.TextSub,
                    )
                }
                Button(
                    onClick = { viewModel.syncNow() },
                    enabled = !cloud.syncing,
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FogColors.Accent, contentColor = FogColors.OnAccent),
                ) { Text(if (cloud.syncing) "동기화 중" else "지금 동기화", modifier = Modifier.padding(8.dp)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("주행이 끝나면 자동으로 동기화", modifier = Modifier.weight(1f))
                FogSwitch(checked = cloud.autoSync, onCheckedChange = { viewModel.setAutoSync(it) })
            }
            Text(
                "다른 차량에서 같은 Pleos 계정으로 로그인하면 개척 기록을 이어서 쓸 수 있어요. " +
                    "서버 연동은 Pleos Sync SDK 공개에 맞춰 열리고, 그 전까지는 이 차량 안에 동기화 사본을 보관해요.",
                style = MaterialTheme.typography.bodySmall,
                color = FogColors.TextSub,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = { viewModel.exportData() }, shape = RoundedCornerShape(4.dp)) {
                    Text("내보내기", modifier = Modifier.padding(8.dp))
                }
                if (!confirmImport) {
                    OutlinedButton(onClick = { confirmImport = true }, shape = RoundedCornerShape(4.dp)) {
                        Text("불러오기", modifier = Modifier.padding(8.dp))
                    }
                } else {
                    Button(
                        onClick = {
                            viewModel.importData()
                            confirmImport = false
                        },
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FogColors.Amber, contentColor = FogColors.OnAccent),
                    ) { Text("지금 기록을 백업으로 바꾸기", modifier = Modifier.padding(8.dp)) }
                    OutlinedButton(onClick = { confirmImport = false }, shape = RoundedCornerShape(4.dp)) {
                        Text("취소", modifier = Modifier.padding(8.dp))
                    }
                }
            }
            backupMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub) }
        }

        SectionHeader("기록과 개인정보", Modifier.padding(top = 16.dp))
        FogCard(Modifier.fillMaxWidth()) {
            Text("위치 기록은 이 차량 안에 저장되고, 클라우드 동기화를 켠 경우에만 내 Pleos 계정으로 보관돼요. 지도 화면은 OpenStreetMap 데이터를 사용해요.", color = FogColors.TextSub)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onRequestBackground) {
                    Text("시동 시 자동 기록 허용", modifier = Modifier.padding(12.dp))
                }
                if (!confirmReset) {
                    OutlinedButton(onClick = { confirmReset = true }) {
                        Text("기록 전체 삭제", modifier = Modifier.padding(12.dp), color = FogColors.Amber)
                    }
                } else {
                    Button(
                        onClick = {
                            viewModel.resetAll()
                            confirmReset = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = FogColors.Amber, contentColor = FogColors.OnAccent),
                    ) { Text("정말 삭제", modifier = Modifier.padding(12.dp)) }
                    OutlinedButton(onClick = { confirmReset = false }) { Text("취소", modifier = Modifier.padding(12.dp)) }
                }
            }
        }
    }
}

/** Label then value (QA reads them as consecutive text nodes); a dot shows connected / not. */
@Composable
private fun StatusLine(label: String, value: String) {
    val ok = value.startsWith("연결됨") || value == "실행 중"
    val off = value == "사용 불가" || value == "중지됨"
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(12.dp).clip(CircleShape).background(
                when {
                    ok -> FogColors.Accent
                    off -> FogColors.Amber
                    else -> FogColors.Outline
                },
            ),
        )
        Text(label, color = FogColors.TextSub, modifier = Modifier.padding(start = 16.dp).weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Service and SDK status. Vehicle signals (odometer, doors…) change often, so they are read here
 * and only this card recomposes, not the whole settings screen.
 */
@Composable
private fun ConnectionCard(viewModel: MainViewModel) {
    val live by viewModel.live.collectAsState()
    val vehicle by viewModel.vehicle.collectAsState()
    val naviConnected by viewModel.navi.connected.collectAsState()
    val naviMessage by viewModel.navi.lastMessage.collectAsState()
    FogCard(Modifier.fillMaxWidth()) {
        StatusLine("기록 서비스", if (live.recording) "실행 중" else "중지됨")
        StatusLine("위치 소스", live.locationSource)
        StatusLine("Pleos Vehicle SDK", if (vehicle.pleosVehicleConnected) "연결됨" else "사용 불가")
        StatusLine("AAOS 차량 속성(기어)", if (vehicle.aaosConnected) "연결됨 · ${vehicle.gear}" else "사용 불가")
        StatusLine("Pleos NaviHelper", if (naviConnected) "연결됨" else "사용 불가")
        StatusLine(
            "차량 상태",
            buildList {
                if (vehicle.anyDoorOpen) add("도어 열림")
                if (vehicle.rearOccupied) add("뒷좌석 탑승")
                if (vehicle.wiperOn) add("와이퍼")
                if (vehicle.lowBeamOn) add("전조등")
                vehicle.evSocPercent?.let { add("배터리 $it%") }
                vehicle.odometerKm?.let { add("누적 %.0f km".format(it)) }
            }.ifEmpty { listOf("-") }.joinToString(" · "),
        )
        naviMessage?.let { StatusLine("내비 메시지", it) }
    }
}
