package com.zobo.burung.ui

import com.zobo.burung.FogRuntime

import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.Person
import android.os.UserManager
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
import androidx.compose.material3.OutlinedTextField
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
import com.zobo.burung.car.DriveMode
import com.zobo.burung.demo.DemoCourses
import com.zobo.burung.demo.DemoDrive
import com.zobo.burung.voice.Sfx
import com.zobo.burung.voice.Sound
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
    val demoSpeed by viewModel.demoSpeed.collectAsState()
    val backupMessage by viewModel.backupMessage.collectAsState()
    val syncTime = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }

    Column(
        Modifier.fillMaxSize().padding(40.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SectionHeader("탐험대")
        ExpeditionSettings()

        SectionHeader("연결 상태", Modifier.padding(top = 16.dp))
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

        SectionHeader("지도 표시 · 안내", Modifier.padding(top = 16.dp))
        FogCard(Modifier.fillMaxWidth()) {
            CardToggle(
                "탐험 우회 경로 제안",
                "목적지가 있을 때, 출발 전에 안 가본 동네를 지나는 경유지를 제안해요.",
                viewModel.detourCard,
            )
            CardToggle(
                "안 가본 동네 추천",
                "정차 중에 가까운 안 가본 동네를 추천하고 내비 안내를 보내요.",
                viewModel.suggestionCard,
            )
            val voice by viewModel.voice.collectAsState()
            PrefToggle(
                "음성 안내",
                "처음 들어선 동네, 동네 개척률 단계, 하차 후 주행 요약을 짧게 말해 줘요. 화면을 보지 않아도 돼요.",
                voice,
            ) { viewModel.setVoice(it) }
            val sound by viewModel.sound.collectAsState()
            PrefToggle(
                "버튼 효과음",
                "버튼, 화면 전환, 스위치, 주행 시작과 종료에 짧은 소리를 내요. 소리는 앱이 직접 만들어 내요.",
                sound,
            ) { viewModel.setSound(it) }
            val soundVolume by viewModel.soundVolume.collectAsState()
            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("효과음 크기", style = MaterialTheme.typography.titleMedium, color = if (sound) FogColors.TextMain else FogColors.TextSub)
                Slider(
                    value = soundVolume.toFloat(),
                    onValueChange = { viewModel.setSoundVolume((it / 10).roundToInt() * 10) },
                    // A tick at the new level when the thumb is let go, to hear what was picked.
                    onValueChangeFinished = { Sfx.play(Sound.TAP) },
                    valueRange = 0f..100f,
                    steps = 9,
                    enabled = sound,
                    colors = SliderDefaults.colors(
                        thumbColor = FogColors.Accent,
                        activeTrackColor = FogColors.Accent,
                        inactiveTrackColor = FogColors.SurfaceHigh,
                        activeTickColor = FogColors.OnAccent,
                        inactiveTickColor = FogColors.Outline,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text("$soundVolume%", style = MaterialTheme.typography.titleMedium, color = if (sound) FogColors.Accent else FogColors.TextSub)
            }
            val stories by viewModel.stories.collectAsState()
            PrefToggle(
                "동네 이야기",
                "처음 가 본 동네의 명소를 위키백과에서 찾아 한 줄로 보여 줘요. 차 위치가 아니라 동의 중심 좌표만 보내요.",
                stories,
            ) { viewModel.setStories(it) }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("튜토리얼", style = MaterialTheme.typography.titleMedium)
                    Text("처음 실행할 때 본 안내를 다시 봐요.", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
                }
                OutlinedButton(onClick = { viewModel.setOnboardingDone(false) }, shape = RoundedCornerShape(4.dp)) {
                    Text("다시 보기", modifier = Modifier.padding(8.dp))
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
            DriverProfileLine()
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

/**
 * The car's driver profile: each one is a separate Android user, so its map, trips and settings
 * are its own (another driver starts with fog everywhere).
 */
/** The 탐험대 name (changed here only) and 키즈모드. */
@Composable
private fun ExpeditionSettings() {
    val prefs = FogRuntime.mapPrefs
    val team by prefs.teamName.collectAsState()
    val kids by prefs.kids.collectAsState()
    FogCard(Modifier.fillMaxWidth()) {
        Text("탐험대 이름", style = MaterialTheme.typography.titleMedium)
        Text("내 탐험 화면에 보여요. 일반 모드와 키즈모드에서 같은 이름을 써요.", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
        OutlinedTextField(
            value = team,
            onValueChange = { prefs.setTeamName(it) },
            singleLine = true,
            placeholder = { Text(MapPrefs.DEFAULT_TEAM, color = FogColors.TextSub) },
            supportingText = { Text("${team.length} / ${MapPrefs.TEAM_MAX}", color = FogColors.TextSub) },
            textStyle = MaterialTheme.typography.titleLarge.copy(color = FogColors.TextMain),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        PrefToggle(
            "키즈모드",
            "아이와 함께 탈 때 켜요. 화면 문구와 음성 안내만 아이가 알아듣기 쉬운 말로 바뀌고, 기록·스탬프·칭호는 그대로예요.",
            kids,
        ) { prefs.setKids(it) }
    }
}

@Composable
private fun DriverProfileLine() {
    val context = LocalContext.current
    val name = remember {
        runCatching { context.getSystemService(UserManager::class.java)?.userName }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        IconBadge(Icons.Outlined.Person, FogColors.Accent, size = 44.dp)
        Column(Modifier.weight(1f)) {
            Text("운전자 프로필 · ${name ?: "현재 사용자"}", style = MaterialTheme.typography.titleMedium)
            Text("기록은 운전자 프로필마다 따로 저장돼요. 다른 프로필로 바꾸면 그 운전자의 지도가 보여요.", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
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
                if (vehicle.windowsOpen.isNotEmpty()) add("창문 열림")
                if (vehicle.sunroofOpen) add("선루프 열림")
                if (vehicle.drivingMode == DriveMode.ECO) add("에코 모드")
                if (vehicle.drivingMode == DriveMode.SPORT) add("스포츠 모드")
                vehicle.rangeKm?.let { add("주행 가능 %.0f km".format(it)) }
                vehicle.odometerKm?.let { add("누적 %.0f km".format(it)) }
            }.ifEmpty { listOf("-") }.joinToString(" · "),
        )
        naviMessage?.let { StatusLine("내비 메시지", it) }
    }
}

/** On/off row with a one-line explanation. */
@Composable
private fun PrefToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
        }
        FogSwitch(checked = checked, onCheckedChange = onChange)
    }
}

/** On/off for one map suggestion card; mentions when it was hidden just for today. */
@Composable
private fun CardToggle(title: String, description: String, card: MapPrefs.CardPref) {
    val on by card.enabled.collectAsState()
    val hiddenOn by card.hiddenOn.collectAsState()
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description + if (on && hiddenOn == MapPrefs.today()) " (오늘은 숨김)" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = FogColors.TextSub,
            )
        }
        FogSwitch(checked = on, onCheckedChange = { card.setEnabled(it) })
    }
}
