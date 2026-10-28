package com.zobo.fogdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zobo.fogdrive.car.Guidance
import com.zobo.fogdrive.car.TurnHint
import com.zobo.fogdrive.car.TurnKind
import com.zobo.fogdrive.geo.Area

/**
 * Driving view (distraction-optimized): the map follows the car with no gestures or animation,
 * plus a few glanceable numbers (level, area cleared, new 동, the 동 under the car) and, while the navigation guides, its next turn and speed limit.
 * No game panels, lists, text input, or detour prompts.
 */
@Composable
fun DrivingScreen(viewModel: MainViewModel) {
    val version by viewModel.version.collectAsState()
    val live by viewModel.live.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val landings by viewModel.landings.collectAsState()
    val guidance by viewModel.navi.guidance.collectAsState()
    val destination by viewModel.navi.destination.collectAsState()
    val here by viewModel.here.collectAsState()
    val tripNewDongs by viewModel.tripNewDongs.collectAsState()
    val showWornRoads by viewModel.showWornRoads.collectAsState()
    val location = live.location
    val current = location?.let { it.lat to it.lng }
    val pins = remember(landings) { landings.map { it.lat to it.lng } }

    Box(Modifier.fillMaxSize().background(FogColors.Bg)) {
        FogMap(
            modifier = Modifier.fillMaxSize(),
            version = version,
            current = current,
            fixTimeMs = location?.timeMs,
            landings = pins,
            detour = null,
            follow = true,
            recenterSignal = 0,
            onUserMoved = {},
            driving = true,
            showWornRoads = showWornRoads,
            destination = destination?.let { it.lat to it.lng },
        )
        // Stamps, missions, drop-offs: a moment on screen below the header, then gone.
        EventToast(live.lastEvent, live.lastEventId, viewModel::eventShown, Modifier.padding(top = 128.dp).align(Alignment.TopCenter))
        Row(
            modifier = Modifier
                // Live numbers stay on screen, but accessibility gets one stable label: TalkBack does
                // not re-read every count change while driving, and the tree stops churning per fix.
                .clearAndSetSemantics { contentDescription = "주행 모드" }
                .align(Alignment.TopStart)
                .padding(28.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(FogColors.Surface.copy(alpha = 0.94f))
                .padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("주행 모드", style = MaterialTheme.typography.titleMedium, color = FogColors.TextSub)
            Text("Lv.${summary.level}", color = FogColors.Accent, style = MaterialTheme.typography.displaySmall)
            Text(
                "+${Area.formatCells(live.trip.newCells, live.location?.lat ?: 37.5)}",
                color = FogColors.Amber,
                style = MaterialTheme.typography.displaySmall.tabular(),
                modifier = Modifier.widthIn(min = 220.dp),
            )
            if (tripNewDongs > 0) {
                Text("새 동 +$tripNewDongs", color = FogColors.Blue, style = MaterialTheme.typography.displaySmall)
            }
            here?.let { h ->
                Column(Modifier.widthIn(min = 140.dp)) {
                    Text(h.dong.name, style = MaterialTheme.typography.titleLarge, color = FogColors.TextMain)
                    Text(formatRate(h.dong.rate), style = MaterialTheme.typography.titleMedium.tabular(), color = FogColors.Accent)
                }
            }
        }
        GuidancePanel(guidance, Modifier.align(Alignment.TopEnd).padding(28.dp))
        // Passenger mission: one line with its progress, nothing to touch (the passengers follow
        // it; the driver hears when it is done).
        live.mission?.let { m ->
            val done = live.missionProgress >= m.target
            Row(
                Modifier
                    .clearAndSetSemantics { contentDescription = "동승자 미션" }
                    // Centred: the bottom-left corner keeps the map credit visible.
                    .align(Alignment.BottomCenter)
                    .padding(28.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(FogColors.Surface.copy(alpha = 0.94f))
                    .padding(horizontal = 28.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("동승자 미션", style = MaterialTheme.typography.titleMedium, color = FogColors.TextSub)
                Text(m.title(), style = MaterialTheme.typography.titleLarge, color = FogColors.TextMain)
                Text(
                    if (done) "성공!" else m.progressLabel(live.missionProgress),
                    style = MaterialTheme.typography.titleLarge.tabular(),
                    modifier = Modifier.widthIn(min = 240.dp),
                    color = if (done) FogColors.Accent else FogColors.Amber,
                )
            }
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(28.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DemoControls(viewModel, compact = true)
            WornRoadsButton(showWornRoads) { viewModel.setShowWornRoads(!showWornRoads) }
        }
    }
}

/** Next turn (only while a route is guided) and the road's speed limit, relayed from the navigation. */
@Composable
private fun GuidancePanel(guidance: Guidance, modifier: Modifier) {
    val turn = guidance.nextTurn.takeIf { guidance.routing }
    val limit = guidance.speedLimitKmh
    if (turn == null && limit == null) return
    Column(
        // Same as the stats row: one stable label instead of a re-read on every distance change.
        modifier = modifier.clearAndSetSemantics { contentDescription = "내비 안내" },
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (turn != null) TurnCard(turn)
        if (limit != null) SpeedLimitSign(limit)
    }
}

@Composable
private fun TurnCard(turn: TurnHint) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .background(FogColors.Surface.copy(alpha = 0.94f))
            .padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(turnIcon(turn.kind), contentDescription = null, tint = FogColors.Accent, modifier = Modifier.size(64.dp))
        Text(
            distanceLabel(turn.distanceM),
            color = FogColors.TextMain,
            style = MaterialTheme.typography.displaySmall.tabular(),
            modifier = Modifier.widthIn(min = 150.dp),
        )
    }
}

@Composable
private fun SpeedLimitSign(kmh: Int) {
    Box(
        modifier = Modifier
            .size(104.dp)
            .clip(CircleShape)
            .background(Color.White)
            .border(10.dp, SignRed, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$kmh", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    }
}

private val SignRed = Color(0xFFD62B2B)

/**
 * Same-width digits, used with a minimum width on the numbers that change every fix: the panels then
 * keep their size, so a new value redraws the text without re-laying out the panel (and without an
 * accessibility layout event per fix, which also kept UI tools from ever seeing the screen idle).
 */
private fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

private fun turnIcon(kind: TurnKind): ImageVector = when (kind) {
    TurnKind.STRAIGHT -> Icons.Filled.Straight
    TurnKind.LEFT -> Icons.Filled.TurnLeft
    TurnKind.RIGHT -> Icons.Filled.TurnRight
    TurnKind.KEEP_LEFT -> Icons.Filled.TurnSlightLeft
    TurnKind.KEEP_RIGHT -> Icons.Filled.TurnSlightRight
    TurnKind.UTURN -> Icons.Filled.UTurnLeft
    TurnKind.ROUNDABOUT -> Icons.Filled.RoundaboutRight
    TurnKind.ARRIVE -> Icons.Filled.Flag
}

/** "1.2km" from a kilometre up, otherwise metres rounded down to 10 m so the number does not flicker. */
private fun distanceLabel(meters: Int): String =
    if (meters >= 1000) "%.1fkm".format(meters / 1000.0) else "${(meters / 10) * 10}m"
