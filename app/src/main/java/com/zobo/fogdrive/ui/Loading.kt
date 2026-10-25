package com.zobo.fogdrive.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zobo.fogdrive.FogRuntime
import com.zobo.fogdrive.R
import kotlinx.coroutines.delay

/** Give up waiting for the fog after this long: a slow first render must not hide the app. */
private const val LOADING_MAX_MS = 6_000L

/**
 * Covers the app on launch until the map's fog is drawn with the region data ([FogRuntime.fogReady]):
 * until then the map showed dark fog everywhere and filled in a moment later, which read as lag.
 * Fades out once ready, or after [LOADING_MAX_MS] at the latest; shown once per process.
 */
@Composable
fun LoadingOverlay() {
    val ready by FogRuntime.fogReady.collectAsState()
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_MAX_MS)
        timedOut = true
    }
    AnimatedVisibility(visible = !ready && !timedOut, exit = fadeOut(tween(400))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(FogColors.Bg)
                // Swallow touches meant for the app underneath while it is covered.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            DriftingFog()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BrandTile(Modifier.size(176.dp))
                Spacer(Modifier.height(36.dp))
                Text(
                    "안개지도",
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = FogColors.TextMain,
                )
                Spacer(Modifier.height(10.dp))
                Text("달린 만큼 걷히는 나만의 지도", style = MaterialTheme.typography.bodyLarge, color = FogColors.TextSub)
                Spacer(Modifier.height(40.dp))
                LinearProgressIndicator(
                    modifier = Modifier.width(240.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = FogColors.Accent,
                    trackColor = FogColors.SurfaceHigh,
                    strokeCap = StrokeCap.Round,
                )
                Spacer(Modifier.height(14.dp))
                Text("내가 밝힌 지도를 불러오는 중", style = MaterialTheme.typography.labelMedium, color = FogColors.TextSub)
            }
        }
    }
}

/** The app icon as a tile: the night-fog background and the cleared-map mark. */
@Composable
fun BrandTile(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(40.dp)
    Box(
        modifier
            .shadow(18.dp, shape, ambientColor = Color(0x660081F8), spotColor = Color(0x660081F8))
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF1E2836), Color(0xFF0B1018)))),
        contentAlignment = Alignment.Center,
    ) {
        // The adaptive-icon foreground leaves room for launcher masks; shown as the launcher does (~1.45×).
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize().scale(1.45f))
    }
}

/** Two soft fog banks drifting slowly behind the logo. */
@Composable
private fun DriftingFog() {
    val drift = rememberInfiniteTransition(label = "fog")
    val t by drift.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val tint = FogColors.Accent
    val fog = FogColors.TextSub
    Canvas(Modifier.fillMaxSize()) {
        val r = size.minDimension * 0.75f
        drawCircle(
            Brush.radialGradient(listOf(tint.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width * (0.25f + 0.10f * t), size.height * 0.35f), radius = r),
            radius = r, center = Offset(size.width * (0.25f + 0.10f * t), size.height * 0.35f),
        )
        drawCircle(
            Brush.radialGradient(listOf(fog.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width * (0.78f - 0.10f * t), size.height * 0.70f), radius = r),
            radius = r, center = Offset(size.width * (0.78f - 0.10f * t), size.height * 0.70f),
        )
    }
}
