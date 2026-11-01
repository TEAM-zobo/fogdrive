package com.zobo.burung.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.zobo.burung.FogRuntime
import com.zobo.burung.TripSummary
import com.zobo.burung.explore.Progress
import com.zobo.burung.explore.Titles
import com.zobo.burung.geo.Area
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Shown on the parked map after a trip: what it cleared, the 동 it opened (three, the rest on demand), its stamps. */
@Composable
fun TripSummaryCard(summary: TripSummary, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val kids by FogRuntime.mapPrefs.kids.collectAsState()
    FogCard(modifier, color = FogColors.Surface.copy(alpha = 0.97f)) {
        if (summary.leveledUp) LevelUpBanner(summary.levelBefore, summary.levelAfter, kids)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Outlined.EmojiEvents, FogColors.Amber, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(if (kids) "오늘의 모험" else "이번 주행 요약", style = MaterialTheme.typography.titleSmall, color = FogColors.Amber)
                Text(
                    "+${Area.formatCells(summary.newCells)} · ${formatKm(summary.distanceMeters)} · 새 길 ${formatPercent(summary.newRoadRatio)}",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            TextButton(onClick = onClose) {
                Text("닫기", style = MaterialTheme.typography.titleMedium, color = FogColors.Accent)
            }
        }
        if (summary.newDongs.isNotEmpty()) {
            Text(if (kids) "처음 가 본 동네 ${summary.newDongs.size}곳 발견!" else "처음 가 본 동네 ${summary.newDongs.size}곳", style = MaterialTheme.typography.titleMedium, color = FogColors.Blue)
            // A long drive opens dozens of 동: three are named, the rest open on "자세히 보기".
            var all by remember(summary) { mutableStateOf(false) }
            val shown = if (all) summary.newDongs else summary.newDongs.take(MAX_DONGS)
            Column(
                Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                shown.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Place, contentDescription = null, tint = FogColors.Blue, modifier = Modifier.size(22.dp))
                        Text("${d.district} ${d.name}", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (summary.newDongs.size > MAX_DONGS) {
                TextButton(onClick = { all = !all }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(
                        if (all) "접기" else "외 ${summary.newDongs.size - MAX_DONGS}곳 자세히 보기",
                        style = MaterialTheme.typography.titleSmall,
                        color = FogColors.Accent,
                    )
                }
            }
        }
        // What this trip added to 내 탐험: titles it completed first, then the stamps it got for the first time.
        val titles = summary.newTitles.mapNotNull { id -> Titles.ALL.firstOrNull { it.id == id } }
        if (titles.isNotEmpty()) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = FogColors.Accent, modifier = Modifier.size(22.dp))
                Text(if (kids) "새 칭호를 받았어요!" else "새 칭호", style = MaterialTheme.typography.titleSmall, color = FogColors.Accent)
                titles.forEach { Pill(it.name(kids), FogColors.Accent) }
            }
        }
        if (summary.newStamps.isNotEmpty()) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Verified, contentDescription = null, tint = FogColors.Amber, modifier = Modifier.size(22.dp))
                Text("새 스탬프", style = MaterialTheme.typography.titleSmall, color = FogColors.Amber)
                summary.newStamps.take(MAX_STAMP_PILLS).forEach { Pill(it.title, FogColors.Amber) }
                if (summary.newStamps.size > MAX_STAMP_PILLS) Pill("+${summary.newStamps.size - MAX_STAMP_PILLS}", FogColors.TextSub)
            }
        }
        if (summary.withPassenger) Text(
            "오늘은 함께 탐험했어요!",
            style = MaterialTheme.typography.bodyMedium,
            color = FogColors.Accent,
        )
    }
}

/**
 * The arrival card's celebration: the star pops in with a bounce, confetti bursts out of it and
 * falls away, and the level number rolls up from [from] to [to]. Plays once per card.
 */
@Composable
private fun LevelUpBanner(from: Int, to: Int, kids: Boolean) {
    val pop = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    var shown by remember { mutableIntStateOf(from) }
    LaunchedEffect(from, to) {
        launch { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)) }
        launch { burst.animateTo(1f, tween(BURST_MS, easing = LinearOutSlowInEasing)) }
        // Several levels at once (a long first drive) roll through each one.
        delay(ROLL_DELAY_MS)
        for (lv in from + 1..to) {
            shown = lv
            delay(ROLL_STEP_MS)
        }
    }
    val amber = FogColors.Amber
    val accent = FogColors.Accent
    val blue = FogColors.Blue
    val confetti = remember { List(CONFETTI) { i -> Confetti(i) } }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(amber.copy(alpha = 0.22f), accent.copy(alpha = 0.14f))))
            .drawBehind {
                // Confetti from the star (left), flying out and dropping as it fades.
                val origin = Offset(56.dp.toPx(), size.height / 2)
                val t = burst.value
                if (t <= 0f || t >= 1f) return@drawBehind
                val colors = listOf(amber, accent, blue, Color(0xFFFF6B6B))
                confetti.forEach { c ->
                    val d = c.speed * t * size.width * 0.55f
                    val x = origin.x + cos(c.angle) * d
                    val y = origin.y + sin(c.angle) * d * 0.6f + t * t * size.height * 0.9f
                    rotate(c.spin * t * 360f, Offset(x, y)) {
                        drawRect(colors[c.color], Offset(x - 5f, y - 3f), Size(10f, 6f), alpha = 1f - t)
                    }
                }
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier
                    .size(72.dp)
                    .graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                        rotationZ = (1f - pop.value) * -30f
                    }
                    .clip(CircleShape)
                    .background(amber),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
            Column {
                Text(if (kids) "우와, 레벨이 올랐어요!" else "레벨 업!", style = MaterialTheme.typography.titleLarge, color = amber)
                Text(
                    "Lv.$from → Lv.$shown",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.graphicsLayer {
                        val s = 0.8f + 0.2f * pop.value
                        scaleX = s
                        scaleY = s
                    },
                )
                if (to >= Progress.MAX_LEVEL) {
                    Text(if (kids) "최고 레벨이에요! 대단해요!" else "최고 레벨에 도착했어요!", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
                }
            }
        }
    }
}

/** One confetti piece: a fixed pseudo-random direction, speed, spin and color per index. */
private class Confetti(i: Int) {
    private val r = java.util.Random(i * 7919L)
    // Mostly upward and outward (to the right of the star), like a popper.
    val angle = (-PI * 0.95 + r.nextDouble() * PI * 0.9).toFloat()
    val speed = 0.35f + r.nextFloat() * 0.65f
    val spin = r.nextFloat() * 2f - 1f
    val color = r.nextInt(4)
}

private const val MAX_DONGS = 3
private const val MAX_STAMP_PILLS = 4
private const val CONFETTI = 28
private const val BURST_MS = 1_400
private const val ROLL_DELAY_MS = 350L
private const val ROLL_STEP_MS = 180L
