package com.zobo.burung.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.zobo.burung.geo.Outline
import com.zobo.burung.geo.RegionAtlas
import com.zobo.burung.geo.Tiles
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A named spot drawn as a dot (an island too small for an outline, 독도) or as a small label only (울릉도). */
class DexMark(val name: String, val x: Float, val y: Float, val dot: Boolean)

/** One region drawn on the dex map: its outline simplified for a small picture, in zoom-20 tile units. */
class DexShape(
    val code: String, val name: String, val rings: List<FloatArray>, val labelX: Float, val labelY: Float, val outline: Outline,
    val marks: List<DexMark> = emptyList(),
    /** A box drawn around an inset (울릉도·독도 on the country map), in tile units: x0, y0, x1, y1. */
    val inset: FloatArray? = null,
)

/**
 * The 시·도 of the country, or the 시·군·구 of one 시·도, as simplified outlines. Each set is built
 * once (the atlas outlines are detailed: thousands of points per region) and kept for the session.
 */
object DexShapes {
    private val cache = HashMap<String, List<DexShape>>()

    /** Points closer than this share of the picture's width to the simplified line are dropped. */
    private const val DETAIL = 1f / 900
    /** A region's main piece is kept unless it is under this many tolerances across (a dot). */
    private const val MIN_MAIN = 4f
    /**
     * Its other pieces (islands) only from this size: ~0.5 % of the country, so the coast reads like a
     * real map (진도, 완도, 거제, 남해, 강화, 백령도, 울릉도 stay; the many islets go), ~6 % of a 시·도.
     */
    private const val MIN_ISLAND_COUNTRY = 5f
    private const val MIN_ISLAND_PROVINCE = 54f

    @Synchronized
    fun provinces(atlas: RegionAtlas): List<DexShape> = cache.getOrPut("") {
        build(atlas.provinces.mapNotNull { p -> p.outline?.let { Triple(p.code, p.name, it) } }, MIN_ISLAND_COUNTRY) { emptyList() }
            .map { if (isGyeongbuk(it.name)) withEastInset(it) else it }
    }

    /**
     * 울릉도 and 독도 in an inset off the 강원 coast, as on the usual maps of Korea: drawn where they are,
     * 독도 2° east of the mainland would make the whole country a third smaller. 울릉도 keeps its
     * shape and scale; 독도 sits nearer to it than it is.
     */
    private fun withEastInset(s: DexShape): DexShape {
        val east = Tiles.lngToX(130.5).toFloat()
        val dx = Tiles.lngToX(INSET_ULLEUNG_LNG).toFloat() - ULLEUNG.x
        val dy = Tiles.latToY(INSET_ULLEUNG_LAT).toFloat() - ULLEUNG.y
        val rings = s.rings.map { r ->
            var minX = Float.MAX_VALUE
            for (i in 0 until r.size / 2) minX = min(minX, r[2 * i])
            if (minX < east) r else FloatArray(r.size) { i -> r[i] + if (i % 2 == 0) dx else dy }
        }
        val marks = listOf(
            DexMark(ULLEUNG.name, ULLEUNG.x + dx, ULLEUNG.y + dy + Tiles.latToY(37.43).toFloat() - Tiles.latToY(37.49).toFloat(), dot = false),
            mark(DOKDO.name, INSET_DOKDO_LAT, INSET_DOKDO_LNG, dot = true),
        )
        val box = floatArrayOf(
            Tiles.lngToX(128.80).toFloat(), Tiles.latToY(38.62).toFloat(),
            Tiles.lngToX(129.66).toFloat(), Tiles.latToY(37.98).toFloat(),
        )
        return DexShape(s.code, s.name, rings, s.labelX, s.labelY, s.outline, marks, box)
    }

    private const val INSET_ULLEUNG_LAT = 38.42
    private const val INSET_ULLEUNG_LNG = 129.04
    private const val INSET_DOKDO_LAT = 38.20
    private const val INSET_DOKDO_LNG = 129.48

    @Synchronized
    fun districts(atlas: RegionAtlas, provinceCode: String): List<DexShape> = cache.getOrPut(provinceCode) {
        val p = atlas.provinces.indexOfFirst { it.code == provinceCode }
        build(atlas.districts.filter { it.province == p }.mapNotNull { d -> d.outline?.let { Triple(d.code, d.name, it) } }, MIN_ISLAND_PROVINCE) { name ->
            if (name.startsWith("울릉")) listOf(DOKDO) else emptyList()
        }
    }

    private fun mark(name: String, lat: Double, lng: Double, dot: Boolean) =
        DexMark(name, Tiles.lngToX(lng).toFloat(), Tiles.latToY(lat).toFloat(), dot)

    /** 독도 is two islets of 0.19 km² in all, gone at any whole-country scale: always drawn as a dot. */
    private val DOKDO = mark("독도", 37.2413, 131.8653, dot = true)
    private val ULLEUNG = mark("울릉도", 37.4870, 130.8650, dot = false)

    private fun isGyeongbuk(name: String) = name.startsWith("경북") || name.startsWith("경상북")

    private fun build(regions: List<Triple<String, String, Outline>>, minIsland: Float, marks: (String) -> List<DexMark>): List<DexShape> {
        if (regions.isEmpty()) return emptyList()
        val x0 = regions.minOf { it.third.bbox[0] }
        val x1 = regions.maxOf { it.third.bbox[2] }
        val y0 = regions.minOf { it.third.bbox[1] }
        val y1 = regions.maxOf { it.third.bbox[3] }
        val tol = max(x1 - x0, y1 - y0) * DETAIL
        return regions.map { (code, name, o) ->
            val spans = o.rings.map { r ->
                var rx0 = Int.MAX_VALUE; var rx1 = Int.MIN_VALUE; var ry0 = Int.MAX_VALUE; var ry1 = Int.MIN_VALUE
                for (i in 0 until r.size / 2) {
                    rx0 = min(rx0, r[2 * i]); rx1 = max(rx1, r[2 * i])
                    ry0 = min(ry0, r[2 * i + 1]); ry1 = max(ry1, r[2 * i + 1])
                }
                max(rx1 - rx0, ry1 - ry0)
            }
            val main = spans.indices.maxByOrNull { spans[it] }
            val rings = o.rings.indices.mapNotNull { k ->
                val min = tol * if (k == main) MIN_MAIN else minIsland
                if (spans[k] < min) null else simplify(o.rings[k], tol).takeIf { it.size >= 6 }
            }
            DexShape(code, name, rings, o.labelX + 0.5f, o.labelY + 0.5f, o, marks(name))
        }
    }

    /** Douglas–Peucker on an (x, y) ring, iteratively (rings run to tens of thousands of points). */
    private fun simplify(r: IntArray, tol: Float): FloatArray {
        val n = r.size / 2
        if (n < 4) return FloatArray(r.size) { r[it].toFloat() }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Int>()
        stack.addLast(0)
        stack.addLast(n - 1)
        while (stack.isNotEmpty()) {
            val b = stack.removeLast()
            val a = stack.removeLast()
            val ax = r[2 * a].toFloat(); val ay = r[2 * a + 1].toFloat()
            val dx = r[2 * b] - ax; val dy = r[2 * b + 1] - ay
            val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            var best = 0f
            var bi = -1
            for (i in a + 1 until b) {
                val d = abs(dy * (r[2 * i] - ax) - dx * (r[2 * i + 1] - ay)) / len
                if (d > best) { best = d; bi = i }
            }
            if (best > tol && bi > 0) {
                keep[bi] = true
                stack.addLast(a); stack.addLast(bi)
                stack.addLast(bi); stack.addLast(b)
            }
        }
        val out = FloatArray(keep.count { it } * 2)
        var k = 0
        for (i in 0 until n) if (keep[i]) { out[k++] = r[2 * i].toFloat(); out[k++] = r[2 * i + 1].toFloat() }
        return out
    }
}

/** A label on the dex map: [chip] ones are drawn as a dark tag (explored 시·도 with their count). */
class DexLabel(val text: String, val color: Color, val bold: Boolean, val small: Boolean = false, val chip: String? = null)

/**
 * The simplified regions fitted into the space, each filled by [fill], with [label] at its label
 * point, the base camp at [home] (tile units) and taps reported per region.
 */
@Composable
fun DexMap(
    shapes: List<DexShape>,
    fill: (DexShape) -> Color,
    label: (DexShape) -> DexLabel?,
    home: Offset?,
    onTap: (DexShape) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val edge = FogColors.Surface
    val chipBg = FogColors.TextMain
    val chipText = FogColors.Surface
    val markEdge = FogColors.TextSub
    // The tap handler lives as long as [shapes]; read the latest [onTap] (its regions change more often).
    val tap by rememberUpdatedState(onTap)
    Spacer(
        modifier
            .pointerInput(shapes) {
                detectTapGestures { p ->
                    val fit = Fit.of(shapes, size.width.toFloat(), size.height.toFloat()) ?: return@detectTapGestures
                    val x = fit.toX(p.x).toDouble()
                    val y = fit.toY(p.y).toDouble()
                    shapes.firstOrNull { it.outline.contains(x, y) }?.let(tap)
                }
            }
            .drawWithCache {
                val fit = Fit.of(shapes, size.width, size.height)
                val paths = shapes.map { s ->
                    Path().apply {
                        if (fit != null) s.rings.forEach { r ->
                            moveTo(fit.px(r[0]), fit.py(r[1]))
                            for (i in 1 until r.size / 2) lineTo(fit.px(r[2 * i]), fit.py(r[2 * i + 1]))
                            close()
                        }
                    }
                }
                val strokeW = 2.5f * density
                onDrawBehind {
                    if (fit == null) return@onDrawBehind
                    shapes.forEachIndexed { i, s ->
                        drawPath(paths[i], fill(s))
                        drawPath(paths[i], edge, style = Stroke(strokeW))
                    }
                    shapes.forEach { s ->
                        s.inset?.let { b ->
                            val tl = Offset(fit.px(b[0]), fit.py(b[1]))
                            drawRoundRect(
                                markEdge.copy(alpha = 0.5f), tl, Size(fit.px(b[2]) - tl.x, fit.py(b[3]) - tl.y), CornerRadius(8f * density),
                                style = Stroke(1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density))),
                            )
                        }
                        s.marks.forEach { m ->
                            val c = Offset(fit.px(m.x), fit.py(m.y))
                            if (m.dot) {
                                drawCircle(fill(s), 6f * density, c)
                                drawCircle(markEdge, 6f * density, c, style = Stroke(1.5f * density))
                            }
                            val t = measurer.measure(m.name, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = markEdge))
                            // Under a dot; under the island for a label-only mark.
                            val dy = if (m.dot) 8f * density else 0f
                            drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y + dy + if (m.dot) 0f else 4f * density))
                        }
                    }
                    home?.let { h ->
                        val t = measurer.measure("🏕️", TextStyle(fontSize = 22.sp))
                        drawText(t, topLeft = Offset(fit.px(h.x) - t.size.width / 2f, fit.py(h.y) - t.size.height / 2f))
                    }
                    shapes.forEach { s ->
                        val l = label(s) ?: return@forEach
                        val x = fit.px(s.labelX)
                        val y = fit.py(s.labelY)
                        if (l.chip != null) {
                            val t = measurer.measure("${l.text}  ${l.chip}", TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = chipText))
                            val pad = 10f * density
                            // A small 시·도 (서울) would be hidden under its own tag: put the tag to its
                            // left, with a dot on the region.
                            val shift = if (l.small) t.size.width / 2f + pad + 18f * density else 0f
                            if (l.small) drawCircle(chipBg, 5f * density, Offset(x, y))
                            val tl = Offset(x - shift - t.size.width / 2f - pad, y - t.size.height / 2f - pad / 2)
                            drawRoundRect(chipBg, tl, Size(t.size.width + 2 * pad, t.size.height + pad), CornerRadius(12f * density))
                            drawText(t, topLeft = Offset(tl.x + pad, tl.y + pad / 2))
                        } else {
                            val t = measurer.measure(
                                l.text,
                                TextStyle(fontSize = if (l.small) 12.sp else 15.sp, fontWeight = if (l.bold) FontWeight.Bold else FontWeight.Medium, color = l.color),
                            )
                            drawText(t, topLeft = Offset(x - t.size.width / 2f, y - t.size.height / 2f))
                        }
                    }
                }
            },
    )
}

/** Tile units ↔ pixels for [shapes] centred in a w × h space with a margin. */
private class Fit(val x0: Float, val y0: Float, val s: Float, val ox: Float, val oy: Float) {
    fun px(x: Float) = (x - x0) * s + ox
    fun py(y: Float) = (y - y0) * s + oy
    fun toX(px: Float) = (px - ox) / s + x0
    fun toY(py: Float) = (py - oy) / s + y0

    companion object {
        private const val MARGIN = 0.06f

        fun of(shapes: List<DexShape>, w: Float, h: Float): Fit? {
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            shapes.forEach { s ->
                s.rings.forEach { r ->
                    for (i in 0 until r.size / 2) {
                        x0 = min(x0, r[2 * i]); x1 = max(x1, r[2 * i])
                        y0 = min(y0, r[2 * i + 1]); y1 = max(y1, r[2 * i + 1])
                    }
                }
                s.marks.forEach { m ->
                    x0 = min(x0, m.x); x1 = max(x1, m.x)
                    y0 = min(y0, m.y); y1 = max(y1, m.y)
                }
                s.inset?.let { b ->
                    x0 = min(x0, b[0]); x1 = max(x1, b[2])
                    y0 = min(y0, b[1]); y1 = max(y1, b[3])
                }
            }
            if (x0 >= x1 || y0 >= y1 || w <= 0 || h <= 0) return null
            val s = min(w * (1 - 2 * MARGIN) / (x1 - x0), h * (1 - 2 * MARGIN) / (y1 - y0))
            return Fit(x0, y0, s, (w - (x1 - x0) * s) / 2, (h - (y1 - y0) * s) / 2)
        }
    }
}
