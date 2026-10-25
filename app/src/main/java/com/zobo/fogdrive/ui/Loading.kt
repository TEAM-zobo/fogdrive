package com.zobo.fogdrive.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zobo.fogdrive.FogRuntime
import com.zobo.fogdrive.R
import kotlinx.coroutines.delay

/** Give up waiting for the fog after this long: a slow first render must not hide the app. */
private const val LOADING_MAX_MS = 6_000L

/**
 * Covers the app on launch until the map's fog is drawn with the region data ([FogRuntime.fogReady]):
 * until then the map showed dark fog everywhere and filled in a moment later, which read as lag.
 * The bar follows the real steps (region atlas, explored cells, first fog); fades out once ready,
 * or after [LOADING_MAX_MS] at the latest; shown once per process.
 */
@Composable
fun LoadingOverlay() {
    val ready by FogRuntime.fogReady.collectAsState()
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_MAX_MS)
        timedOut = true
    }
    AnimatedVisibility(visible = !ready && !timedOut, enter = EnterTransition.None, exit = fadeOut(tween(450))) {
        val atlas by FogRuntime.regionsLoaded.collectAsState()
        val cells by FogRuntime.repo.loaded.collectAsState()
        val steps = (if (atlas) 1 else 0) + (if (cells) 1 else 0)
        // Each finished step fills a third; a slow creep inside the step shows it is working.
        val creep = remember { Animatable(0f) }
        LaunchedEffect(steps) {
            creep.snapTo(0f)
            creep.animateTo(0.8f, tween(2_400, easing = LinearEasing))
        }
        val progress by animateFloatAsState((steps + creep.value) / 3f, tween(250), label = "progress")
        val status = when {
            !atlas -> "지역 경계를 불러오는 중"
            !cells -> "내 탐험 기록을 불러오는 중"
            else -> "안개 지도를 그리는 중"
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(FogColors.Bg, FogColors.SurfaceHigh)))
                .blockingCover(),
            contentAlignment = Alignment.Center,
        ) {
            DriftingFog()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // The tile settles in with a fog-clearing ring around it.
                val appear = remember { Animatable(0f) }
                LaunchedEffect(Unit) { appear.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
                    ClearingRings(Modifier.fillMaxSize())
                    BrandTile(
                        Modifier
                            .size(176.dp)
                            .graphicsLayer {
                                alpha = appear.value
                                val s = 0.9f + 0.1f * appear.value
                                scaleX = s
                                scaleY = s
                            },
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "안개지도",
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
                    color = FogColors.TextMain,
                )
                Spacer(Modifier.height(10.dp))
                Text("달린 만큼 걷히는 나만의 지도", style = MaterialTheme.typography.bodyLarge, color = FogColors.TextSub)
                Spacer(Modifier.height(44.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.width(260.dp).height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = FogColors.Accent,
                    trackColor = FogColors.Outline.copy(alpha = 0.6f),
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(14.dp))
                Text(status, style = MaterialTheme.typography.labelMedium, color = FogColors.TextSub)
            }
        }
    }
}

/**
 * The app icon as a tile, drawn from the launcher icon's own layers (shown as a launcher would: the
 * adaptive layers are 108 dp with a 72 dp visible middle, hence the 1.5× scale).
 */
@Composable
fun BrandTile(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val corner = RoundedCornerShape(26)
        Box(
            Modifier
                .fillMaxSize()
                .shadow(20.dp, corner, ambientColor = Color(0x660A3A7A), spotColor = Color(0x880A3A7A))
                .clip(corner)
                // A thin light edge on top, like a lit glass tile.
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.03f))), corner),
        ) {
            Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize().scale(1.5f))
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize().scale(1.5f))
        }
    }
}

/** Two soft rings widening and fading around the tile, like fog drawing back. */
@Composable
private fun ClearingRings(modifier: Modifier) {
    val loop = rememberInfiniteTransition(label = "rings")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2_600, easing = LinearEasing)), label = "ring")
    val color = FogColors.Accent
    Canvas(modifier) {
        val base = size.minDimension * 0.34f
        for (k in 0..1) {
            val p = (t + k * 0.5f) % 1f
            drawCircle(
                color = color.copy(alpha = 0.22f * (1f - p)),
                radius = base + size.minDimension * 0.18f * p,
                style = Stroke(width = 2.dp.toPx() * (1f - p * 0.5f)),
            )
        }
    }
}

/** Soft fog banks drifting slowly behind everything. */
@Composable
private fun DriftingFog() {
    val drift = rememberInfiniteTransition(label = "fog")
    val t by drift.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(11_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val accent = FogColors.Accent
    val mist = FogColors.TextSub
    Canvas(Modifier.fillMaxSize()) {
        val r = size.minDimension * 0.8f
        fun bank(color: Color, alpha: Float, cx: Float, cy: Float, radius: Float) {
            val c = Offset(size.width * cx, size.height * cy)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center = c, radius = radius), radius, c)
        }
        bank(accent, 0.09f, 0.22f + 0.08f * t, 0.30f, r)
        bank(mist, 0.08f, 0.80f - 0.08f * t, 0.72f, r * 0.9f)
        bank(mist, 0.05f, 0.55f + 0.05f * t, 0.10f, r * 0.6f)
    }
}

/**
 * Covers a map until its fog is first drawn with the region data. With the activity's one map this
 * shows only on the first visit; a map built per screen (none provided) shows it each time.
 */
@Composable
fun MapLoadingCover(visible: Boolean) {
    // Like the launch overlay: never cover (and swallow touches on) the map for good if the fog
    // never reports ready.
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_MAX_MS)
        timedOut = true
    }
    AnimatedVisibility(visible = visible && !timedOut, enter = EnterTransition.None, exit = fadeOut(tween(300))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(FogColors.Bg)
                .blockingCover(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
                    ClearingRings(Modifier.fillMaxSize())
                    BrandTile(Modifier.size(96.dp))
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    modifier = Modifier.width(180.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = FogColors.Accent,
                    trackColor = FogColors.Outline.copy(alpha = 0.6f),
                    strokeCap = StrokeCap.Round,
                )
                Spacer(Modifier.height(12.dp))
                Text("지도를 불러오는 중", style = MaterialTheme.typography.labelMedium, color = FogColors.TextSub)
            }
        }
    }
}

/** A cover takes every touch meant for what is under it, silently (no ripple, no click sound). */
@Composable
private fun Modifier.blockingCover(): Modifier =
    muteClickSounds().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
