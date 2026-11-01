package com.zobo.burung.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zobo.burung.data.ClearedCell
import com.zobo.burung.geo.Tiles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max

/**
 * The explored cells in the order they were cleared. [xs] / [ys] are in z20 cell units from an origin,
 * [cell] one drawn cell's width in the same units. The camera follows the footprint as it grows:
 * keyframe k ([cx], [cy], [span]) frames the cells shown at k / [KEYFRAMES] of the play, eased so a
 * trip to another city pans and zooms out instead of jumping; extra keyframes at the end let it settle.
 */
class Timelapse(
    val xs: FloatArray,
    val ys: FloatArray,
    val times: LongArray,
    val cell: Float,
    private val cx: FloatArray,
    private val cy: FloatArray,
    private val span: FloatArray,
) {
    val size: Int get() = xs.size

    /** Camera (centre x, centre y, side) at [t] of the play (0 … [END]). */
    fun camera(t: Float): Triple<Float, Float, Float> {
        val f = (t * KEYFRAMES).coerceIn(0f, (cx.size - 1).toFloat())
        val i = f.toInt().coerceAtMost(cx.size - 2)
        val a = f - i
        fun mix(v: FloatArray) = v[i] + (v[i + 1] - v[i]) * a
        return Triple(mix(cx), mix(cy), mix(span))
    }

    companion object {
        const val KEYFRAMES = 60
        /** The play runs past the last cell (1) to here while the camera settles on the whole footprint. */
        const val END = 1.25f
        /** Share of the cells trimmed on each side when framing, so one far trip doesn't shrink home to a dot. */
        private const val EDGE_TRIM = 0.04
        /** Coarsest cell grid across the final frame: a long history still draws as cells, not dust. */
        private const val MAX_SPAN_CELLS = 480.0
        private const val MIN_SPAN_CELLS = 64f
        /** How far the camera moves toward each keyframe's framing (lower: slower, smoother). */
        private const val EASE = 0.3f

        /** Null when nothing was cleared yet. */
        fun of(cleared: List<ClearedCell>): Timelapse? {
            if (cleared.isEmpty()) return null
            val ox = cleared.minOf { Tiles.x(it.cell) }
            val oy = cleared.minOf { Tiles.y(it.cell) }
            // Drawn at one level for the whole play, coarse enough for the final frame.
            val whole = frame(FloatArray(cleared.size) { (Tiles.x(cleared[it].cell) - ox).toFloat() }, FloatArray(cleared.size) { (Tiles.y(cleared[it].cell) - oy).toFloat() }, cleared.size)
            val dz = max(0, ceil(ln(whole.third / MAX_SPAN_CELLS) / ln(2.0)).toInt())
            // One point per coarse cell, at the time it was first reached.
            val first = HashMap<Long, Long>()
            for (c in cleared) first.merge(Tiles.parent(c.cell, dz), c.firstSeenAt, ::minOf)
            val order = first.entries.sortedBy { it.value }
            val scale = 1 shl dz
            val xs = FloatArray(order.size) { (Tiles.x(order[it].key).toLong() * scale - ox).toFloat() }
            val ys = FloatArray(order.size) { (Tiles.y(order[it].key).toLong() * scale - oy).toFloat() }
            val frames = (KEYFRAMES * END).toInt() + 1
            val cx = FloatArray(frames)
            val cy = FloatArray(frames)
            val sp = FloatArray(frames)
            for (k in 0 until frames) {
                val n = ((k.toDouble() / KEYFRAMES).coerceAtMost(1.0) * xs.size).toInt().coerceIn(1, xs.size)
                val (tx, ty, ts) = frame(xs, ys, n)
                if (k == 0) {
                    cx[0] = tx; cy[0] = ty; sp[0] = ts
                } else {
                    cx[k] = cx[k - 1] + (tx - cx[k - 1]) * EASE
                    cy[k] = cy[k - 1] + (ty - cy[k - 1]) * EASE
                    sp[k] = sp[k - 1] + (ts - sp[k - 1]) * EASE
                }
            }
            return Timelapse(xs, ys, LongArray(order.size) { order[it].value }, scale.toFloat(), cx, cy, sp)
        }

        /** Centre and side of a square around the bulk of the first [k] points, with a margin. */
        private fun frame(x: FloatArray, y: FloatArray, k: Int): Triple<Float, Float, Float> {
            // Primitive copies: sorting boxed lists for every keyframe was the bulk of the work.
            val xs = x.copyOf(k).apply { sort() }
            val ys = y.copyOf(k).apply { sort() }
            val lo = (k * EDGE_TRIM).toInt()
            val hi = (k * (1 - EDGE_TRIM)).toInt().coerceIn(lo, k - 1)
            val side = max(max(xs[hi] - xs[lo], ys[hi] - ys[lo]) + 1f, MIN_SPAN_CELLS) / 0.85f
            return Triple((xs[lo] + xs[hi]) / 2 + 0.5f, (ys[lo] + ys[hi]) / 2 + 0.5f, side)
        }
    }
}

private const val PLAY_MS = 9_000

/** Plays the footprint growing from the first drive to today; the date follows the newest cell. */
@Composable
fun TimelapseDialog(lapse: Timelapse, onClose: () -> Unit) {
    var round by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(round) {
        progress.snapTo(0f)
        progress.animateTo(Timelapse.END, tween((PLAY_MS * Timelapse.END).toInt(), easing = LinearEasing))
    }
    val shown = (progress.value.coerceAtMost(1f) * lapse.size).toInt().coerceIn(if (lapse.size > 0) 1 else 0, lapse.size)
    val fmt = remember { SimpleDateFormat("yyyy년 M월 d일", Locale.KOREA) }
    // Cell centres in the timelapse's units; the camera is applied as a transform per frame.
    val points = remember(lapse) { List(lapse.size) { Offset(lapse.xs[it] + lapse.cell / 2, lapse.ys[it] + lapse.cell / 2) } }
    val accent = FogColors.Accent
    val fog = FogColors.SurfaceHigh
    Dialog(onDismissRequest = onClose) {
        FogCard(Modifier.width(720.dp).clickSounds(), padding = 28.dp) {
            Row(Modifier.fillMaxWidth()) {
                Text("개척 타임랩스", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (shown > 0) Text(fmt.format(Date(lapse.times[shown - 1])), style = MaterialTheme.typography.titleMedium, color = FogColors.Accent)
            }
            Box(Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(8.dp)).background(fog)) {
                Canvas(Modifier.matchParentSize()) {
                    // The camera's square, fitted to the box's shorter side and centred.
                    val (camX, camY, camSide) = lapse.camera(progress.value)
                    val k = size.minDimension / camSide
                    val left = size.width / 2 - camX * k
                    val top = size.height / 2 - camY * k
                    // At least 2 px a cell, so a long history zoomed out still reads as cells.
                    val width = max(lapse.cell, 2f / k)
                    // The newest cells glow brighter for a moment (the last ~3 % shown).
                    val fresh = if (progress.value < 1f) max(1, lapse.size / 30).coerceAtMost(shown) else 0
                    // Every shown cell in one draw call (a long history has tens of thousands).
                    clipRect {
                        withTransform({
                            translate(left, top)
                            scale(k, k, pivot = Offset.Zero)
                        }) {
                            drawPoints(points.subList(0, shown - fresh), PointMode.Points, accent.copy(alpha = 0.78f), width, StrokeCap.Square)
                            if (fresh > 0) {
                                val young = points.subList(shown - fresh, shown)
                                drawPoints(young, PointMode.Points, accent.copy(alpha = 0.28f), width * 3, StrokeCap.Square)
                                drawPoints(young, PointMode.Points, accent, width, StrokeCap.Square)
                            }
                        }
                    }
                }
            }
            Text(
                "${lapse.size}칸을 탐험한 기록이에요.",
                style = MaterialTheme.typography.bodyMedium,
                color = FogColors.TextSub,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { round++ },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FogColors.Accent, contentColor = FogColors.OnAccent),
                ) { Text("다시 보기", modifier = Modifier.padding(8.dp)) }
                OutlinedButton(onClick = onClose, shape = RoundedCornerShape(4.dp)) { Text("닫기", modifier = Modifier.padding(8.dp)) }
            }
        }
    }
}
