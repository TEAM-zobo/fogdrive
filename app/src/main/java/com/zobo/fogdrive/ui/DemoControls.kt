package com.zobo.fogdrive.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.zobo.fogdrive.demo.DemoCourse
import com.zobo.fogdrive.demo.DemoCourses
import com.zobo.fogdrive.demo.DemoDrive
import com.zobo.fogdrive.record.RecordingService

/**
 * Demo controls on the map and the driving view (Settings has the full set): the course playing,
 * its speed (applies mid-drive), stop, and once it finished, another run. On the map, with no demo
 * on, it folds into a "시연" chip that opens a course picker. [compact] (driving view) shows only
 * the running demo's controls.
 */
@Composable
fun DemoControls(viewModel: MainViewModel, modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val session by viewModel.demoRunning.collectAsState()
    val playing by viewModel.demoPlaying.collectAsState()
    val courseId by viewModel.demoCourse.collectAsState()
    val speed by viewModel.demoSpeed.collectAsState()
    var open by remember { mutableStateOf(false) }
    val course = DemoCourses.byId(courseId)
    fun start() = RecordingService.start(context, RecordingService.ACTION_DEMO_START)
    fun stop() = RecordingService.start(context, RecordingService.ACTION_DEMO_STOP)

    if (!session && compact) return
    if (!session && !open) {
        TextButton(onClick = { open = true }, modifier = modifier) {
            Pill("시연 주행", FogColors.Amber)
        }
        return
    }
    FogCard(modifier.width(420.dp), color = FogColors.Surface.copy(alpha = 0.97f), padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (session) (if (playing) "시연 주행 중" else "시연 주행 완료") else "시연 주행", style = MaterialTheme.typography.labelMedium, color = FogColors.Amber)
                Text(course.title, style = MaterialTheme.typography.titleMedium)
            }
            if (!session) TextButton(onClick = { open = false }) { Text("닫기", color = FogColors.TextSub) }
        }
        // Course picker while nothing plays (also after a drive finished, before the next one).
        if (!playing) DemoCourseSelect(viewModel, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("속도", style = MaterialTheme.typography.bodyLarge, color = FogColors.TextSub)
            IconButton(onClick = { viewModel.setDemoSpeed(speed - 10) }, enabled = speed > DemoDrive.MIN_KMH) {
                Icon(Icons.Outlined.Remove, contentDescription = "느리게", tint = FogColors.TextMain)
            }
            Text("$speed km/h", style = MaterialTheme.typography.titleMedium, color = FogColors.Accent)
            IconButton(onClick = { viewModel.setDemoSpeed(speed + 10) }, enabled = speed < DemoDrive.MAX_KMH) {
                Icon(Icons.Outlined.Add, contentDescription = "빠르게", tint = FogColors.TextMain)
            }
        }
        RoundTripRow(viewModel)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!playing) {
                Button(
                    onClick = { start(); open = false },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FogColors.Accent, contentColor = FogColors.OnAccent),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                    Text(if (session) "다시 달리기" else "시연 시작", modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (session) {
                OutlinedButton(onClick = { stop() }, shape = RoundedCornerShape(4.dp)) {
                    Icon(Icons.Outlined.Stop, contentDescription = null, tint = FogColors.TextMain, modifier = Modifier.size(22.dp))
                    Text("시연 중지", color = FogColors.TextMain, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/** What a course shows on the way, as short tags for the course select box. */
private fun DemoCourse.tags(): List<String> = buildList {
    add(formatKm(lengthMeters))
    if (rain != null) add("비")
    if (lowBeam) add("야간")
    if (rearPassenger) add("동승자")
    add("제한 ${speedLimit}km/h")
}

/**
 * Demo course select box: a field with the picked course (route icon, title, tags) and a menu of
 * every course with its route summary, the picked one ticked. A dropdown rather than cards, so the
 * list can grow without crowding the page.
 */
@Composable
fun DemoCourseSelect(viewModel: MainViewModel, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val courseId by viewModel.demoCourse.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val course = DemoCourses.byId(courseId)
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")
    val shape = RoundedCornerShape(6.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("시연 코스", style = MaterialTheme.typography.labelMedium, color = FogColors.TextSub)
        Box(Modifier.fillMaxWidth().onSizeChanged { anchorWidth = it.width }) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(FogColors.Surface)
                    .border(if (expanded) 2.dp else 1.dp, if (expanded) FogColors.Accent else FogColors.Outline, shape)
                    .clickable(enabled = enabled) { expanded = true }
                    .alpha(if (enabled) 1f else 0.5f)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconBadge(Icons.Outlined.Route, FogColors.Accent, size = 44.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(course.title, style = MaterialTheme.typography.titleMedium, color = FogColors.TextMain)
                    Text(course.tags().joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
                }
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "코스 선택",
                    tint = FogColors.TextSub,
                    modifier = Modifier.size(32.dp).rotate(arrow),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(with(density) { anchorWidth.toDp() }),
                offset = DpOffset(0.dp, 6.dp),
                shape = shape,
                containerColor = FogColors.Surface,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, FogColors.Outline),
            ) {
                DemoCourses.all.forEachIndexed { i, c ->
                    val picked = c.id == courseId
                    if (i > 0) HorizontalDivider(color = FogColors.Outline, modifier = Modifier.padding(horizontal = 16.dp))
                    DropdownMenuItem(
                        modifier = Modifier.background(if (picked) FogColors.Accent.copy(alpha = 0.08f) else Color.Transparent),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        leadingIcon = {
                            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                if (picked) Icon(Icons.Outlined.Check, contentDescription = "선택됨", tint = FogColors.Accent)
                            }
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    c.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (picked) FogColors.Accent else FogColors.TextMain,
                                )
                                Text(c.summary, style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    c.tags().forEach { Pill(it, if (picked) FogColors.Accent else FogColors.TextSub) }
                                }
                            }
                        },
                        onClick = {
                            viewModel.setDemoCourse(c.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** "같은 길로 돌아오기" switch; read when the car arrives, so it can be flipped mid-drive. */
@Composable
fun RoundTripRow(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val on by viewModel.demoRoundTrip.collectAsState()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("같은 길로 돌아오기", style = MaterialTheme.typography.bodyLarge, color = FogColors.TextMain)
            Text("도착해 하차한 뒤 출발지까지 왕복해요", style = MaterialTheme.typography.bodySmall, color = FogColors.TextSub)
        }
        FogSwitch(checked = on, onCheckedChange = { viewModel.setDemoRoundTrip(it) })
    }
}
