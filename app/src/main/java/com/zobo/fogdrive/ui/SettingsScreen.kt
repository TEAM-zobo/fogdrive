package com.zobo.fogdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Switch
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onStartDemo: () -> Unit,
    onStopDemo: () -> Unit,
    onRequestBackground: () -> Unit,
) {
    val live by viewModel.live.collectAsState()
    val vehicle by viewModel.vehicle.collectAsState()
    val naviConnected by viewModel.navi.connected.collectAsState()
    val naviMessage by viewModel.navi.lastMessage.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    val cloud by viewModel.cloud.collectAsState()
    val backupMessage by viewModel.backupMessage.collectAsState()
    val syncTime = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }

    Column(
        Modifier.fillMaxSize().padding(40.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SectionHeader("연결 상태")
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

        SectionHeader("시연 모드", Modifier.padding(top = 16.dp))
        FogCard(Modifier.fillMaxWidth()) {
            Text("성수역 → 서울숲 → 한강 코스를 자동으로 달려요. 중간에 비가 오고(와이퍼), 뒷좌석 동승자가 있으며, 도착하면 P단 + 도어 열림으로 하차 지점이 기록돼요.", color = FogColors.TextSub)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onStartDemo,
                    enabled = !live.demo,
                    colors = ButtonDefaults.buttonColors(containerColor = FogColors.Accent, contentColor = FogColors.OnAccent),
                ) { Text("시연 시작", modifier = Modifier.padding(12.dp)) }
                OutlinedButton(onClick = onStopDemo, enabled = live.demo) {
                    Text("시연 중지", modifier = Modifier.padding(12.dp))
                }
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
                Switch(checked = cloud.autoSync, onCheckedChange = { viewModel.setAutoSync(it) })
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
            Text("위치 기록은 이 차량의 기기 내부에만 저장되고 서버로 전송되지 않아요. 지도 화면은 OpenStreetMap 데이터를 사용해요.", color = FogColors.TextSub)
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
