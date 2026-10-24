package com.zobo.fogdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Driving view (distraction-optimized): the map follows the car with no gestures or animation,
 * plus two glanceable numbers. No game panels, lists, text input, or detour prompts.
 */
@Composable
fun DrivingScreen(viewModel: MainViewModel) {
    val version by viewModel.version.collectAsState()
    val live by viewModel.live.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val landings by viewModel.landings.collectAsState()
    val current = live.location?.let { it.lat to it.lng }
    val pins = remember(landings) { landings.map { it.lat to it.lng } }

    Box(Modifier.fillMaxSize().background(FogColors.Bg)) {
        FogMap(
            modifier = Modifier.fillMaxSize(),
            version = version,
            current = current,
            landings = pins,
            detour = null,
            follow = true,
            recenterSignal = 0,
            onUserMoved = {},
            driving = true,
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(28.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(FogColors.Surface.copy(alpha = 0.94f))
                .padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("주행 모드", style = MaterialTheme.typography.titleMedium, color = FogColors.TextSub)
            Text("Lv.${summary.level}", fontSize = 44.sp, color = FogColors.Accent, style = MaterialTheme.typography.displaySmall)
            Text("+${live.trip.newCells}칸", fontSize = 44.sp, color = FogColors.Amber, style = MaterialTheme.typography.displaySmall)
        }
    }
}
