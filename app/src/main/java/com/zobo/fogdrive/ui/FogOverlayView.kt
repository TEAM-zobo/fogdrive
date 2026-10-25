package com.zobo.fogdrive.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.zobo.fogdrive.explore.RegionCounts
import com.zobo.fogdrive.geo.Outline
import com.zobo.fogdrive.geo.RegionAtlas
import com.zobo.fogdrive.geo.Tiles
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the fog of war on top of the map in two strengths: opaque dark fog over 읍·면·동 the driver
 * has never entered, and a light grey fog (the map shows through) inside the ones already entered.
 * The fog is painted into a reduced-resolution mask with tileable cloud textures, explored cells get
 * a soft blue rim, and each explored cell is punched out as a slightly oversized circle (neighbours
 * merge into smooth strips instead of a staircase). The mask is then scaled up with filtering,
 * which softens edges and clouds alike. It is rendered off the UI thread for an area a bit larger
 * than the view ([FogMaskRenderer]) and only moved and scaled per frame.
 * Over the fog go the administrative boundaries (시·도 always, 시·군·구 and 읍·면·동 as the map zooms
 * in) and, for regions already entered, their name and achievement rate.
 * At low zoom it switches to coarser parent tiles so the cost stays bounded.
 * Map rotation/tilt are disabled, so tile coordinates map linearly to the screen.
 */
class FogOverlayView(context: Context) : View(context) {
    var map: MapLibreMap? = null
    var cellsAtLevel: (Int) -> Set<Long> = { emptySet() }
    /** Draw [revisits] at all (map / driving view / Settings toggle). */
    var showWornRoads = true
    /** Cells driven by more than one trip and how many (read live, drawn as "worn" roads). */
    var revisits: () -> Map<Long, Int> = { emptyMap() }
    /** Changes whenever explored cells or revisit counts change (the fog is redrawn then). */
    var dataVersion: () -> Long = { 0L }
    var current: Pair<Double, Double>? = null
    var landings: List<Pair<Double, Double>> = emptyList()
    var detour: Pair<Double, Double>? = null
    /** A past trip picked on the record screen: its route as a line, or (older trips) its cells as dots. */
    var route: TripRoute? = null
    /** Draw the car dot here (parked). While driving a fixed centre marker is used instead. */
    var showCurrent = true
    /** Keep drawing with the transform from [captureTransform] (driving: fog moves with the slid map). */
    var freezeTransform = false
    /** Night palette (darker fog) to match the night map style. */
    var night = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    /** Boundaries and explored counts; null until the atlas is loaded (dark fog everywhere meanwhile). */
    var regions: RegionLayer? = null
        set(value) {
            field = value
            invalidate()
        }
    /** The region atlas could not be loaded: draw light fog everywhere instead of waiting for it. */
    var regionsUnavailable = false
        set(value) {
            field = value
            invalidate()
        }
    /** Region names and rates on the map (off while driving: lines only). */
    var showLabels = true

    // Screen <-> zoom-20 tile transform: top-left and bottom-right of the view in tile units.
    private var tx0 = 0.0
    private var ty0 = 0.0
    private var tx1 = 0.0
    private var ty1 = 0.0
    private var hasTransform = false

    /** Snapshot of the map projection, taken on the frame a re-render lands. */
    fun captureTransform(): Boolean {
        val m = map ?: return false
        if (width == 0 || height == 0) return false
        val proj = m.projection
        val tl = proj.fromScreenLocation(PointF(0f, 0f))
        val br = proj.fromScreenLocation(PointF(width.toFloat(), height.toFloat()))
        tx0 = Tiles.lngToX(tl.longitude)
        ty0 = Tiles.latToY(tl.latitude)
        tx1 = Tiles.lngToX(br.longitude)
        ty1 = Tiles.latToY(br.latitude)
        hasTransform = true
        return true
    }

    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0081F8") }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val density = resources.displayMetrics.density
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF9900") }
    private val pinRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val pinText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 16f * density
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    }
    private val detourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0081F8")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private fun linePaint(argb: Int, width: Float, dash: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb
        style = Paint.Style.STROKE
        strokeWidth = width * density
        strokeJoin = Paint.Join.ROUND
        if (dash > 0) pathEffect = DashPathEffect(floatArrayOf(dash * density, dash * density * 0.8f), 0f)
    }
    // Each level is a light line over a dark halo so it reads on the dark fog and the light map alike.
    // Over unexplored (dark) fog the lines are light; inside an entered region the map shows through
    // light fog, so there they are dark to stay visible.
    private val provinceHalo = Stroke(Color.argb(110, 0, 0, 0), 5f)
    private val provinceLine = Stroke(Color.argb(230, 0xF1, 0xF4, 0xF8), 2.4f)
    private val districtHalo = Stroke(Color.argb(90, 0, 0, 0), 3.4f)
    private val districtLine = Stroke(Color.argb(190, 0xC9, 0xD3, 0xDF), 1.5f)
    private val districtHaloIn = Stroke(Color.argb(150, 0xFF, 0xFF, 0xFF), 4f)
    private val districtLineIn = Stroke(Color.argb(230, 0x2A, 0x30, 0x38), 1.8f)
    private val dongLine = Stroke(Color.argb(150, 0xAE, 0xB8, 0xC6), 1f, dashDp = 5f)
    private val dongLineIn = Stroke(Color.argb(210, 0x4A, 0x52, 0x60), 1.3f, dashDp = 5f)
    private val nameText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F1F4F8")
        textSize = 22f * density
        textAlign = Paint.Align.CENTER
        // Medium: Pleos maps bold (700) to AstaSans ExtraBold, which smeared at this size.
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private val rateText = Paint(nameText).apply {
        color = Color.parseColor("#3D9BFF")
        textSize = 19f * density
    }
    private val textHalo = Paint(nameText).apply {
        color = Color.argb(170, 0x11, 0x11, 0x11)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val rateHalo = Paint(textHalo).apply { textSize = rateText.textSize }
    private val placedLabels = ArrayList<RectF>()
    private val routePath = Path()
    private val routeCasing = linePaint(Color.WHITE, 9f).apply { strokeCap = Paint.Cap.ROUND }
    private val routeLine = linePaint(Color.parseColor("#FF9900"), 5f).apply { strokeCap = Paint.Cap.ROUND }
    private val routeDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0xFF, 0x99, 0x00) }
    private val routeStart = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#111111") }
    private val routeEnd = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF9900") }

    override fun onDraw(canvas: Canvas) {
        if (map == null) return
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        if (!freezeTransform || !hasTransform) {
            if (!captureTransform()) return
        }
        val x0 = tx0
        val y0 = ty0
        val x1 = tx1
        val y1 = ty1
        if (x1 <= x0 || y1 <= y0) return
        val pxPerCell = w / (x1 - x0)

        drawFog(canvas, x0, y0, x1, y1, pxPerCell)
        val regions = regions
        if (regions != null) {
            drawBoundaries(canvas, regions, pxPerCell, x0, y0, x1, y1)
            if (showLabels) {
                placedLabels.clear()
                // Keep labels off the car dot.
                current?.takeIf { showCurrent }?.let { (lat, lng) ->
                    val cx = ((Tiles.lngToX(lng) - x0) * pxPerCell).toFloat()
                    val cy = ((Tiles.latToY(lat) - y0) * pxPerCell).toFloat()
                    placedLabels.add(RectF(cx - 24 * density, cy - 24 * density, cx + 24 * density, cy + 24 * density))
                }
                drawLabels(canvas, regions, pxPerCell, x0, y0, x1, y1)
            }
        }

        fun toScreen(lat: Double, lng: Double): PointF = PointF(
            ((Tiles.lngToX(lng) - x0) * pxPerCell).toFloat(),
            ((Tiles.latToY(lat) - y0) * pxPerCell).toFloat(),
        )
        route?.let { r ->
            // No per-point PointF: a route can hold thousands of points and this runs every frame.
            fun sx(lng: Double) = ((Tiles.lngToX(lng) - x0) * pxPerCell).toFloat()
            fun sy(lat: Double) = ((Tiles.latToY(lat) - y0) * pxPerCell).toFloat()
            if (r.isTrack) {
                routePath.rewind()
                r.points.forEachIndexed { i, (lat, lng) ->
                    if (i == 0) routePath.moveTo(sx(lng), sy(lat)) else routePath.lineTo(sx(lng), sy(lat))
                }
                canvas.drawPath(routePath, routeCasing)
                canvas.drawPath(routePath, routeLine)
                r.points.first().let { (lat, lng) -> canvas.drawCircle(sx(lng), sy(lat), 10f * density, routeStart) }
                r.points.last().let { (lat, lng) -> canvas.drawCircle(sx(lng), sy(lat), 10f * density, routeEnd) }
            } else {
                val radius = max(3f * density, pxPerCell.toFloat() * 0.6f)
                for ((lat, lng) in r.points) canvas.drawCircle(sx(lng), sy(lat), radius, routeDot)
            }
        }
        drawLandingPins(canvas, x0, y0, pxPerCell)
        detour?.let { (lat, lng) ->
            val p = toScreen(lat, lng)
            canvas.drawCircle(p.x, p.y, min(160f, max(48f, pxPerCell.toFloat() * 3)), detourPaint)
        }
        current?.takeIf { showCurrent }?.let { (lat, lng) ->
            val p = toScreen(lat, lng)
            canvas.drawCircle(p.x, p.y, 16f, dotPaint)
            canvas.drawCircle(p.x, p.y, 16f, dotRing)
        }
    }

    private val renderer = FogMaskRenderer(onTile = { onTile(it) }, onFailed = { onRenderFailed(it) })
    private var tile: FogMaskTile? = null
    /** The tile the last frame drew: its bitmaps stay out of reuse until a later frame replaced it. */
    private var drawn: FogMaskTile? = null
    /** The newest request sent to [renderer] (its key and rectangle), so a frame doesn't resend it. */
    private var requested: FogMaskRenderer.Job? = null
    private var lastDataRequest = 0L
    private val tileMatrix = Matrix()
    private val fogFallback = Paint()

    private fun onTile(t: FogMaskTile) {
        // A tile that was never drawn is in no display list and can be reused right away; the drawn
        // one is retired from [drawFog] once a frame has replaced it.
        tile?.takeIf { it !== drawn }?.let { renderer.recycle(it) }
        tile = t
        invalidate()
    }

    private fun onRenderFailed(job: FogMaskRenderer.Job) {
        // Forget the request, or later frames would wait for it forever ("already on its way").
        if (requested === job) requested = null
        postInvalidateDelayed(DATA_REDRAW_MS)
    }

    /** The previous drawn tile may still be read by the render thread (or replayed unchanged by a
     *  parent redraw) for a frame or two: its bitmaps are handed back for reuse only after a delay. */
    private fun retire(old: FogMaskTile) {
        postDelayed({ if (old !== tile && old !== drawn) renderer.recycle(old) }, RETIRE_MS)
    }

    /**
     * Draws the fog from the current [tile] (moved and scaled to the view) and asks the renderer for a
     * new one when the view left it, the zoom moved too far from it, or what it shows changed. Data
     * changes are throttled to [DATA_REDRAW_MS]: while driving new cells arrive with every fix.
     */
    private fun drawFog(canvas: Canvas, x0: Double, y0: Double, x1: Double, y1: Double, px: Double) {
        val cellPx = px / MASK_DIV
        var dz = 0
        while (dz < MAX_DZ && cellPx * (1 shl dz) < 1.5) dz++
        val key = FogMaskKey(dataVersion(), dz, night, regions, regionsUnavailable, showWornRoads && px >= WORN_MIN_PX)
        val t = tile
        if (t != null) {
            val s = px / t.cellPx
            tileMatrix.setScale(s.toFloat(), s.toFloat())
            tileMatrix.postTranslate(((t.x0 - x0) * px).toFloat(), ((t.y0 - y0) * px).toFloat())
            if (!t.covers(x0, y0, x1, y1)) {
                // Past the rendered area until the new tile lands: plain dark fog, never the bare map.
                fogFallback.color = if (night) Color.rgb(6, 6, 8) else Color.rgb(18, 19, 22)
                canvas.drawPaint(fogFallback)
            }
            canvas.drawBitmap(t.bitmap, tileMatrix, maskPaint)
            if (drawn !== t) {
                drawn?.let { retire(it) }
                drawn = t
            }
            t.worn?.let { worn ->
                val ws = s / FogMaskRenderer.WORN_SHARPER
                tileMatrix.setScale(ws.toFloat(), ws.toFloat())
                tileMatrix.postTranslate(((t.x0 - x0) * px).toFloat(), ((t.y0 - y0) * px).toFloat())
                canvas.drawBitmap(worn, tileMatrix, maskPaint)
            }
        } else {
            fogFallback.color = if (night) Color.rgb(6, 6, 8) else Color.rgb(18, 19, 22)
            canvas.drawPaint(fogFallback)
        }
        fun fits(k: FogMaskKey, jx0: Double, jy0: Double, jx1: Double, jy1: Double, jCellPx: Double) = k.dz == dz &&
            x0 >= jx0 && y0 >= jy0 && x1 <= jx1 && y1 <= jy1 && (cellPx / jCellPx) in 0.6..1.6
        val r = requested
        val tileFits = t != null && fits(t.key, t.x0, t.y0, t.x1, t.y1, t.cellPx)
        if (tileFits && t!!.key == key) return
        if (r != null && r.key == key && fits(r.key, r.x0, r.y0, r.x1, r.y1, r.cellPx)) return  // already on its way
        val now = android.os.SystemClock.uptimeMillis()
        if (tileFits && now - lastDataRequest < DATA_REDRAW_MS) {
            // Only the content changed: keep showing the current tile a little longer.
            postInvalidateDelayed(DATA_REDRAW_MS - (now - lastDataRequest))
            return
        }
        lastDataRequest = now
        // Render a margin around the view so panning and driving re-renders reuse the tile.
        val mx = (x1 - x0) * TILE_MARGIN
        val my = (y1 - y0) * TILE_MARGIN
        val wantDz = dz
        val job = FogMaskRenderer.Job(
            key, x0 - mx, y0 - my, x1 + mx, y1 + my, cellPx,
            { cellsAtLevel(wantDz) },
            if (key.worn) revisits() else null,
        )
        requested = job
        renderer.submit(job)
    }

    private var clusterX = FloatArray(64)
    private var clusterY = FloatArray(64)
    private var clusterN = IntArray(64)

    /**
     * Drop-off pins, merged on screen: pins within [CLUSTER_DP] of a group join it (drawn at the
     * group's average position with the count), so repeated stops at home or work stay one marker.
     */
    private fun drawLandingPins(c: Canvas, x0: Double, y0: Double, px: Double) {
        if (landings.isEmpty()) return
        val radius = CLUSTER_DP * density
        var groups = 0
        for ((lat, lng) in landings) {
            val x = ((Tiles.lngToX(lng) - x0) * px).toFloat()
            val y = ((Tiles.latToY(lat) - y0) * px).toFloat()
            var g = 0
            while (g < groups) {
                val n = clusterN[g]
                val dx = clusterX[g] / n - x
                val dy = clusterY[g] / n - y
                if (dx * dx + dy * dy < radius * radius) break
                g++
            }
            if (g == groups) {
                if (groups == clusterN.size) {
                    clusterX = clusterX.copyOf(groups * 2)
                    clusterY = clusterY.copyOf(groups * 2)
                    clusterN = clusterN.copyOf(groups * 2)
                }
                clusterX[g] = 0f
                clusterY[g] = 0f
                clusterN[g] = 0
                groups++
            }
            clusterX[g] += x
            clusterY[g] += y
            clusterN[g]++
        }
        for (g in 0 until groups) {
            val n = clusterN[g]
            val x = clusterX[g] / n
            val y = clusterY[g] / n
            if (x < -radius || y < -radius || x > width + radius || y > height + radius) continue
            if (n == 1) {
                c.drawRect(x - 2.5f * density, y - 18f * density, x + 2.5f * density, y, pinPaint)
                c.drawCircle(x, y - 20f * density, 10f * density, pinPaint)
                c.drawCircle(x, y - 20f * density, 10f * density, pinRing)
            } else {
                val r = (14f + 2f * minOf(n, 9)) * density
                c.drawCircle(x, y, r, pinPaint)
                c.drawCircle(x, y, r, pinRing)
                val label = if (n > 99) "99+" else n.toString()
                c.drawText(label, x, y - (pinText.descent() + pinText.ascent()) / 2, pinText)
            }
        }
    }

    private fun visible(o: Outline, x0: Double, y0: Double, x1: Double, y1: Double): Boolean =
        o.bbox[2] >= x0 && o.bbox[0] <= x1 && o.bbox[3] >= y0 && o.bbox[1] <= y1

    /**
     * Boundary lines are built in screen pixels from only the on-screen part of each outline (see
     * [strokeOutline]); the strokes are therefore plain dp widths.
     */
    private fun drawBoundaries(c: Canvas, r: RegionLayer, px: Double, x0: Double, y0: Double, x1: Double, y1: Double) {
        val n = r.counts
        if (px >= DONG_LINES_PX) {
            r.atlas.dongs.forEachIndexed { i, d ->
                if (visible(d.outline, x0, y0, x1, y1)) {
                    strokeOutline(c, d.outline, px, x0, y0, if (n.dong[i] > 0) dongLineIn else dongLine)
                }
            }
        }
        if (px >= DISTRICT_LINES_PX) {
            r.atlas.districts.forEachIndexed { i, d ->
                val o = d.outline ?: return@forEachIndexed
                if (!visible(o, x0, y0, x1, y1)) return@forEachIndexed
                if (n.district[i] > 0) strokeOutline(c, o, px, x0, y0, districtHaloIn, districtLineIn)
                else strokeOutline(c, o, px, x0, y0, districtHalo, districtLine)
            }
        }
        r.atlas.provinces.forEach { p ->
            val o = p.outline ?: return@forEach
            if (visible(o, x0, y0, x1, y1)) strokeOutline(c, o, px, x0, y0, provinceHalo, provinceLine)
        }
    }

    /**
     * Strokes the part of [o] that is on screen. Stroking the whole cached outline under a canvas
     * scale made the GPU rasterise every province and district border in full (tens of thousands of
     * points, almost all off screen) each time the fog was redrawn: ~35 ms per redraw, one dropped
     * frame per driving re-render. Here segments outside the view are skipped and points closer than
     * [MIN_SEGMENT_PX] merged, so only a few hundred short lines reach the GPU.
     */
    private fun strokeOutline(c: Canvas, o: Outline, scale: Double, x0: Double, y0: Double, vararg strokes: Stroke) {
        val pad = 8f * density
        val left = -pad
        val top = -pad
        val right = width + pad
        val bottom = height + pad
        val dashed = strokes.any { it.dashPeriod > 0f }
        val path = boundaryPath
        path.rewind()
        // Dashes restart at every visible piece; each piece is drawn with the dash phase of its
        // distance along the whole ring, so the dashes stay put on the map while it pans.
        var runStart = 0.0
        fun endRun() {
            if (!dashed || path.isEmpty) return
            for (st in strokes) {
                if (st.dashPeriod > 0f) st.paint.pathEffect = st.dashAt((runStart % st.dashPeriod).toFloat())
                c.drawPath(path, st.paint)
            }
            path.rewind()
        }
        for (r in o.rings) {
            var penDown = false
            var lastX = 0f
            var lastY = 0f
            var along = 0.0
            var px0 = ((r[0] - x0) * scale).toFloat()
            var py0 = ((r[1] - y0) * scale).toFloat()
            var k = 2
            // Closing segment included: rings are stored open.
            val n = r.size
            while (k <= n) {
                val i = if (k == n) 0 else k
                val px1 = ((r[i] - x0) * scale).toFloat()
                val py1 = ((r[i + 1] - y0) * scale).toFloat()
                val onScreen = max(px0, px1) >= left && min(px0, px1) <= right && max(py0, py1) >= top && min(py0, py1) <= bottom
                if (onScreen) {
                    if (!penDown) {
                        path.moveTo(px0, py0)
                        lastX = px0
                        lastY = py0
                        runStart = along
                        penDown = true
                    }
                    if (abs(px1 - lastX) + abs(py1 - lastY) >= MIN_SEGMENT_PX || k == n) {
                        path.lineTo(px1, py1)
                        lastX = px1
                        lastY = py1
                    }
                } else if (penDown) {
                    // Through the last merged point too, so the corner just inside the edge is kept.
                    if (lastX != px0 || lastY != py0) path.lineTo(px0, py0)
                    path.lineTo(px1, py1)
                    penDown = false
                    endRun()
                }
                if (dashed) along += hypot((px1 - px0).toDouble(), (py1 - py0).toDouble())
                px0 = px1
                py0 = py1
                k += 2
            }
            if (penDown) endRun()
        }
        if (dashed || path.isEmpty) return
        for (st in strokes) c.drawPath(path, st.paint)
    }

    private val boundaryPath = Path()

    /** A line style in dp (boundaries are stroked in screen pixels, see [strokeOutline]). */
    private inner class Stroke(argb: Int, widthDp: Float, dashDp: Float = 0f) {
        private val dash = if (dashDp > 0) floatArrayOf(dashDp * density, dashDp * density * 0.8f) else null
        /** Length of one dash and gap, or 0 for a solid line. */
        val dashPeriod = dash?.sum() ?: 0f
        fun dashAt(phase: Float) = DashPathEffect(dash, phase)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = widthDp * density
        }
    }

    /** Name + rate of each entered region at the level that suits the zoom; overlapping labels are skipped. */
    private fun drawLabels(c: Canvas, r: RegionLayer, px: Double, x0: Double, y0: Double, x1: Double, y1: Double) {
        fun label(o: Outline?, name: String, rate: Double) {
            o ?: return
            if (o.labelX < x0 || o.labelX > x1 || o.labelY < y0 || o.labelY > y1) return
            val x = ((o.labelX - x0) * px).toFloat()
            var y = ((o.labelY - y0) * px).toFloat()
            val rateStr = formatRate(rate)
            val half = max(nameText.measureText(name), rateText.measureText(rateStr)) / 2 + 6 * density
            val box = RectF(x - half, y - nameText.textSize, x + half, y + rateText.textSize * 1.3f)
            // Taken: try once just below the obstacle (usually the car dot) before giving up.
            placedLabels.firstOrNull { RectF.intersects(it, box) }?.let { hit ->
                box.offset(0f, hit.bottom - box.top + 4 * density)
                y = box.top + nameText.textSize
                if (placedLabels.any { RectF.intersects(it, box) }) return
            }
            placedLabels.add(box)
            c.drawText(name, x, y, textHalo)
            c.drawText(name, x, y, nameText)
            val ry = y + rateText.textSize * 1.15f
            c.drawText(rateStr, x, ry, rateHalo)
            c.drawText(rateStr, x, ry, rateText)
        }
        val n = r.counts
        when {
            px >= DONG_LINES_PX -> r.atlas.dongs.forEachIndexed { i, d -> if (n.dong[i] > 0) label(d.outline, d.name, n.dongRate(i)) }
            px >= DISTRICT_LINES_PX -> r.atlas.districts.forEachIndexed { i, d -> if (n.district[i] > 0) label(d.outline, d.name, n.districtRate(i)) }
            else -> r.atlas.provinces.forEachIndexed { i, p -> if (n.province[i] > 0) label(p.outline, p.name, n.provinceRate(i)) }
        }
    }

    companion object {
        const val PROVINCE = 0
        const val DISTRICT = 1
        const val DONG = 2
        /** Screen px per grid cell from which 읍·면·동 (zoom ~13) / 시·군·구 (zoom ~9) are drawn. */
        const val DONG_LINES_PX = 4.0
        const val DISTRICT_LINES_PX = 0.25
        /** Screen px per cell from which worn roads are drawn (single cells visible, zoom ~13). */
        const val WORN_MIN_PX = 3.0
        /**
         * Worn-road levels: the blue deepens over the first few repeats, then the hue walks on
         * (indigo → purple → orange → red), so daily commutes still read apart from weekly routes.
         */
        val WORN_LEVELS = listOf(
            WornLevel(2, Color.rgb(0x00, 0x81, 0xF8), 60),
            WornLevel(3, Color.rgb(0x00, 0x71, 0xE0), 110),
            WornLevel(5, Color.rgb(0x3D, 0x3A, 0xE8), 130),
            WornLevel(10, Color.rgb(0x8E, 0x2D, 0xC8), 140),
            WornLevel(20, Color.rgb(0xF0, 0x7A, 0x00), 150),
            WornLevel(50, Color.rgb(0xE0, 0x30, 0x30), 165),
        )
        /** Drop-off pins closer than this on screen are shown as one numbered pin. */
        const val CLUSTER_DP = 44f
        /** Boundary points closer than this (screen px, Manhattan) are merged when stroking. */
        private const val MIN_SEGMENT_PX = 1.5f
        const val MASK_DIV = 3
        /** Extra fog rendered on each side of the view, as a share of its size. */
        private const val TILE_MARGIN = 0.3
        /** At most one fog redraw this often for new cells alone (driving reveals cells every fix). */
        private const val DATA_REDRAW_MS = 300L
        /** How long a replaced tile's bitmaps stay untouched before the renderer may reuse them. */
        private const val RETIRE_MS = 250L
        const val MAX_DZ = 10
        const val TEXTURE_SIZE = 128
        private val textures = HashMap<Pair<Boolean, Boolean>, Bitmap>()

        /**
         * Tileable cloud texture (two octaves of smoothed value noise), built once per kind.
         * Dark fog is fully opaque, so nothing of a never-entered 동 shows. Light fog is a grey
         * at ~62-70 % so the map of an entered 동 shows faintly through it.
         */
        @Synchronized
        fun cloudTexture(night: Boolean, light: Boolean): Bitmap = textures.getOrPut(night to light) {
            val size = TEXTURE_SIZE
            val base = when {
                // Neutral greys/near-black, like the built-in apps' monochrome UI.
                light && night -> intArrayOf(66, 68, 72)
                light -> intArrayOf(124, 128, 134)
                night -> intArrayOf(6, 6, 8)
                else -> intArrayOf(18, 19, 22)
            }
            val rnd = java.util.Random(19)
            fun grid(n: Int) = Array(n) { FloatArray(n) { rnd.nextFloat() } }
            val coarse = grid(8)
            val fine = grid(32)
            fun sample(g: Array<FloatArray>, x: Float, y: Float): Float {
                val n = g.size
                val gx = x * n / size
                val gy = y * n / size
                val ix = gx.toInt()
                val iy = gy.toInt()
                val fx = (gx - ix).let { it * it * (3 - 2 * it) }
                val fy = (gy - iy).let { it * it * (3 - 2 * it) }
                val a = g[iy % n][ix % n]
                val b = g[iy % n][(ix + 1) % n]
                val c = g[(iy + 1) % n][ix % n]
                val d = g[(iy + 1) % n][(ix + 1) % n]
                return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
            }
            val pixels = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) {
                val v = 0.7f * sample(coarse, x.toFloat(), y.toFloat()) + 0.3f * sample(fine, x.toFloat(), y.toFloat())
                val lift = (v * 26).toInt()            // lighter wisps
                val alpha = if (light) (158 + v * 20).toInt() else 255
                pixels[y * size + x] = Color.argb(alpha, base[0] + lift, base[1] + lift, base[2] + lift)
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}

/** What the overlay needs from the region atlas: shapes, and explored cells per region. */
class RegionLayer(val atlas: RegionAtlas, val counts: RegionCounts)

/** Android paths of the region outlines, built on first use and kept (shapes never change). */
object OutlinePaths {
    private val cache = HashMap<Long, Path>()

    @Synchronized
    fun get(level: Int, index: Int, o: Outline): Path = cache.getOrPut((level.toLong() shl 32) or index.toLong()) {
        val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
        val ox = o.bbox[0]
        val oy = o.bbox[1]
        for (r in o.rings) {
            path.moveTo((r[0] - ox).toFloat(), (r[1] - oy).toFloat())
            for (k in 2 until r.size step 2) path.lineTo((r[k] - ox).toFloat(), (r[k + 1] - oy).toFloat())
            path.close()
        }
        path
    }
}

/** One worn-road tint: roads driven on at least [minTrips] trips, drawn in [color] at layer [alpha]. */
class WornLevel(val minTrips: Int, val color: Int, val alpha: Int)
