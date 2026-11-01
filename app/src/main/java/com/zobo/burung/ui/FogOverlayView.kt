package com.zobo.burung.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.zobo.burung.data.Tracks
import com.zobo.burung.explore.CellLog
import com.zobo.burung.explore.RegionCounts
import com.zobo.burung.geo.Outline
import com.zobo.burung.geo.RegionAtlas
import com.zobo.burung.geo.Tiles
import com.mapbox.mapboxsdk.maps.MapboxMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a veil over the ground not driven yet, in two strengths: darker over 읍·면·동 the driver has
 * never entered, lighter inside the ones already entered; the map shows through both. The veil is
 * painted into a reduced-resolution mask and opened along every driven route as one straight band
 * with a soft white edge, then scaled up with filtering, which softens the edges.
 * The trip being driven is drawn over it as a gold trail, the navigation destination as a pennant and
 * its 집 bookmark as a base camp tent. It is rendered off the UI thread for an area a bit larger
 * than the view ([FogMaskRenderer]) and only moved and scaled per frame.
 * Over the fog go the administrative boundaries (시·도 always, 시·군·구 and 읍·면·동 as the map zooms
 * in) and, for regions already entered, their name and achievement rate.
 * At low zoom it switches to coarser parent tiles so the cost stays bounded.
 * Map rotation/tilt are disabled, so tile coordinates map linearly to the screen.
 */
class FogOverlayView(context: Context) : View(context) {
    var map: MapboxMap? = null
    /** The explored-cell log, to tell the renderer which part changed (see [changedArea]). */
    var cellLog: CellLog? = null
    /** Called once, the first time the fog is drawn with the region data (or without it for good). */
    var onReady: (() -> Unit)? = null
    /** Changes whenever explored cells change (the fog is redrawn then). */
    var cellsVersion: () -> Int = { 0 }
    var current: Pair<Double, Double>? = null
    var landings: List<Pair<Double, Double>> = emptyList()
    var detour: Pair<Double, Double>? = null
    /** A past trip picked on the record screen: its route as a line, or (older trips) its cells as dots. */
    var route: TripRoute? = null
    /** Every trip's route: the fog is opened along it; the newest trip's is drawn as a gold trail. */
    var tracks = Tracks()
    /** Navigation destination, drawn as a pennant. */
    var destination: Pair<Double, Double>? = null
    /** The navigation's 집 bookmark, drawn as a base camp tent. */
    var home: Pair<Double, Double>? = null
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
            // A new layer comes with every explored-cells change; the fog only depends on the atlas and
            // the visited 읍·면·동, so keep the old one in the fog key while those are unchanged.
            val fog = fogRegions
            if (value == null || fog == null || fog.atlas !== value.atlas || fog.counts.visitedDongs != value.counts.visitedDongs) {
                fogRegions = value
                invalidate()
            } else if (showLabels) {
                // Only the rates in the labels changed: nothing to redraw while driving (no labels).
                invalidate()
            }
        }
    /** [regions] as the fog tile's key sees it (see the setter). */
    private var fogRegions: RegionLayer? = null
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

    /**
     * While driving this layer is redrawn only when a new fog tile lands (a few hundred ms after new
     * cells, and not at all on roads already explored) or the map re-renders around the car, so the
     * gold trail drawn with it came in late and in chunks behind the car. Now it draws the trail only
     * up to the car, and [trailHead], a plain view above it, draws the rest from there to the car on
     * every frame: a few segments, against the whole trail in this oversized layer.
     */
    var trailApart = false
        set(value) {
            if (field == value) return
            field = value
            trailLine = null
            trailVersion = -1
            trailTipX = Double.NaN
            trailHead.visibility = if (value) VISIBLE else GONE
            invalidate()
        }
    val trailHead: View = object : View(context) {
        override fun onDraw(canvas: Canvas) = drawTrailHead(canvas)
    }.apply { visibility = GONE }

    override fun invalidate() {
        super.invalidate()
        // Same frame: the head starts where this layer's trail ends, in its transform.
        if (trailApart) trailHead.invalidate()
    }

    private var trailLine: Tracks.Line? = null
    private var trailVersion = -1
    private var trailEpoch = -1
    // The car's shown position (tile units), NaN until the first one.
    private var trailTipX = Double.NaN
    private var trailTipY = Double.NaN
    /** Last point of [trailLine] the car has passed (see [trailCut]). */
    private var trailCut = -1
    /** The car is on the trail's newest segments: the head ends at the car. */
    private var trailHeadOn = false
    /** Last point drawn in this layer, and the distance along the trail to it (px, for the dashes). */
    private var trailDrawnTo = -1
    private var trailAlong = 0.0
    // What the head showed when last drawn, to tell when it has to be drawn again.
    private var headDrawnX = Float.NaN
    private var headDrawnY = Float.NaN
    private var headDrawnOn = false
    private var headDrawnCut = -1

    /** The car is shown at ([lat], [lng]) this frame (driving): moves the head to it. */
    fun setTrailTip(lat: Double, lng: Double) {
        if (!trailApart) return
        val v = tracks.version
        var reset = false
        if (v != trailVersion) {
            trailVersion = v
            val old = trailLine
            val line = tracks.latest()
            trailLine = line
            // Another trip (or everything reloaded): not just points added at the end.
            val sameTrip = line == null && old == null ||
                line != null && old != null && line.count >= old.count && line.xs[0] == old.xs[0]
            if (tracks.epoch != trailEpoch || !sameTrip) {
                trailEpoch = tracks.epoch
                reset = true
            }
        }
        trailTipX = Tiles.lngToX(lng)
        trailTipY = Tiles.latToY(lat)
        updateTrailCut()
        // The head stays a handful of segments: past that (or on a new trip) the layer takes them over.
        if (reset || trailCut - trailDrawnTo > TRAIL_HEAD_MAX || trailCut < trailDrawnTo) invalidate()
        if (trailHeadOn != headDrawnOn || trailCut != headDrawnCut) {
            trailHead.invalidate()
        } else if (trailHeadOn && hasTransform && tx1 > tx0) {
            val px = width / (tx1 - tx0)
            val hx = ((trailTipX - tx0) * px).toFloat()
            val hy = ((trailTipY - ty0) * px).toFloat()
            if (abs(hx - headDrawnX) >= 0.25f || abs(hy - headDrawnY) >= 0.25f) trailHead.invalidate()
        }
    }

    /**
     * The segment nearest the car among the trail's newest: the car has passed its start (or its end
     * once past it). Far from all of them (a gap, a jump, no car yet): the whole trail, no head.
     */
    private fun updateTrailCut() {
        val line = trailLine
        trailHeadOn = false
        if (line == null) {
            trailCut = -1
            return
        }
        trailCut = line.count - 1
        if (trailTipX.isNaN()) return
        var bestD = TRAIL_SNAP_TILES * TRAIL_SNAP_TILES
        for (j in line.count - 1 downTo max(1, line.count - TRAIL_SEARCH)) {
            val ax = line.xs[j - 1]
            val bx = line.xs[j]
            if (ax.isNaN() || bx.isNaN()) continue
            val ay = line.ys[j - 1]
            val dx = bx - ax
            val dy = line.ys[j] - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) ((trailTipX - ax) * dx + (trailTipY - ay) * dy) / len2 else 1.0
            val k = t.coerceIn(0.0, 1.0)
            val ex = ax + dx * k - trailTipX
            val ey = ay + dy * k - trailTipY
            val d = ex * ex + ey * ey
            if (d < bestD) {
                bestD = d
                trailCut = if (t >= 1.0) j else j - 1
                trailHeadOn = true
            }
        }
    }

    /** Draws the trail from the last point in this layer through the points passed since to the car. */
    private fun drawTrailHead(c: Canvas) {
        headDrawnX = Float.NaN
        headDrawnOn = trailHeadOn
        headDrawnCut = trailCut
        if (!trailApart || !hasTransform || route != null || tx1 <= tx0) return
        val line = trailLine ?: return
        val from = trailDrawnTo
        if (from < 0 || from >= line.count || line.xs[from].isNaN()) return
        val px = width / (tx1 - tx0)
        for (st in trailStrokes) st.clear()
        var ax = ((line.xs[from] - tx0) * px).toFloat()
        var ay = ((line.ys[from] - ty0) * px).toFloat()
        var along = trailAlong
        fun to(bx: Float, by: Float) {
            for (st in trailStrokes) st.add(ax, ay, bx, by, along)
            along += hypot((bx - ax).toDouble(), (by - ay).toDouble())
            ax = bx
            ay = by
        }
        var pen = true
        for (i in from + 1..min(trailCut, line.count - 1)) {
            // A gap: lift the pen, the next point starts a new stroke (as in [drawTrail]).
            if (line.xs[i].isNaN()) {
                pen = false
                continue
            }
            val bx = ((line.xs[i] - tx0) * px).toFloat()
            val by = ((line.ys[i] - ty0) * px).toFloat()
            if (pen) {
                to(bx, by)
            } else {
                ax = bx
                ay = by
                pen = true
            }
        }
        if (trailHeadOn) {
            val bx = ((trailTipX - tx0) * px).toFloat()
            val by = ((trailTipY - ty0) * px).toFloat()
            to(bx, by)
            headDrawnX = bx
            headDrawnY = by
        }
        for (st in trailStrokes) st.draw(c)
    }

    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG)
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
    /** Drawing order: finer levels first, each halo under its line. */
    private val strokes = listOf(dongLine, dongLineIn, districtHalo, districtLine, districtHaloIn, districtLineIn, provinceHalo, provinceLine)
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
    // The road driven on this trip: gold over a brown edge, with a light dashed centre line. Segments
    // like the boundaries (see [drawTrail]); all opaque, so the round caps overlapping at the joints
    // look like one line.
    private val trailStrokes = listOf(
        Stroke(Color.parseColor("#A5742F"), 15f),
        Stroke(Color.parseColor("#FFD25E"), 10f),
        Stroke(Color.parseColor("#FFF6C9"), 2.6f, dashDp = 7f),
    )
    private val markShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(64, 0, 0, 0) }
    private val flagPole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A5A3A") }
    private val flagRed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF6B6B") }
    private val flagKnob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD25E") }
    private val campTent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB04F") }
    private val campDoor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C9762A") }
    private val campLog = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A5A3A") }
    private val campFire = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB03B") }
    private val markPath = Path()
    private val markRect = RectF()
    /** Ink outlines in marker units (see [beginMark]), so they scale with it. */
    private fun markInk(width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2F4A2C")
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val markInk2 = markInk(2f)
    private val markInk25 = markInk(2.5f)
    private val markInk3 = markInk(3f)
    private val markInk35 = markInk(3.5f)
    private val markInk4 = markInk(4f)

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
            drawBoundariesCached(canvas, regions, pxPerCell, x0, y0, x1, y1)
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
        if (route == null) {
            if (!trailApart) {
                tracks.latest()?.let { drawTrail(canvas, it, it.count - 1, x0, y0, pxPerCell) }
            } else {
                // Up to the car; the head draws on from here (see [trailApart]).
                if (trailLine == null) {
                    trailLine = tracks.latest()
                    updateTrailCut()
                }
                trailDrawnTo = -1
                trailLine?.let { line ->
                    if (trailCut >= 0) {
                        trailAlong = drawTrail(canvas, line, trailCut, x0, y0, pxPerCell)
                        trailDrawnTo = trailCut
                    }
                }
            }
        }
        drawLandingPins(canvas, x0, y0, pxPerCell)
        home?.let { (lat, lng) -> toScreen(lat, lng).let { drawBaseCamp(canvas, it.x, it.y) } }
        destination?.let { (lat, lng) -> toScreen(lat, lng).let { drawFlag(canvas, it.x, it.y) } }
        detour?.let { (lat, lng) ->
            val p = toScreen(lat, lng)
            canvas.drawCircle(p.x, p.y, min(160f, max(48f, pxPerCell.toFloat() * 3)), detourPaint)
        }
        current?.takeIf { showCurrent }?.let { (lat, lng) ->
            val p = toScreen(lat, lng)
            CarIcon.draw(canvas, p.x, p.y, 0f, 0.8f)
        }
    }

    /**
     * The trip's road as line segments, only those on screen, with points closer than
     * [MIN_SEGMENT_PX] merged. Stroked paths (with a dash effect) were stroked on the CPU and
     * stencilled again on every redraw of the fog layer, the whole trip each time, so they cost more
     * the longer the drive; during the demo that was the largest part of each fog layer redraw.
     * Only the newest trip's road is gold (the one being driven, or the one just ended): earlier trips
     * stay as opened ground, so a course driven before does not show the road ahead in gold. Draws
     * points 0..[upTo] and returns the distance along them (px).
     */
    private fun drawTrail(c: Canvas, line: Tracks.Line, upTo: Int, x0: Double, y0: Double, px: Double): Double {
        for (st in trailStrokes) st.clear()
        val pad = 16f * density
        val right = width + pad
        val bottom = height + pad
        var pen = false
        var ax = 0f
        var ay = 0f
        var along = 0.0
        val last = min(upTo, line.count - 1)
        for (i in 0..last) {
            val xv = line.xs[i]
            if (xv.isNaN()) {
                pen = false
                continue
            }
            val x = ((xv - x0) * px).toFloat()
            val y = ((line.ys[i] - y0) * px).toFloat()
            if (!pen) {
                ax = x
                ay = y
                pen = true
                continue
            }
            // Keep the last point before the end or a gap, or the merged run before it is lost.
            val endOfRun = i == last || line.xs[i + 1].isNaN()
            if (abs(x - ax) + abs(y - ay) < MIN_SEGMENT_PX && !endOfRun) continue
            val seg = hypot((x - ax).toDouble(), (y - ay).toDouble())
            if (max(ax, x) >= -pad && min(ax, x) <= right && max(ay, y) >= -pad && min(ay, y) <= bottom) {
                for (st in trailStrokes) st.add(ax, ay, x, y, along)
            }
            along += seg
            ax = x
            ay = y
        }
        for (st in trailStrokes) st.draw(c)
        return along
    }

    /**
     * The markers are drawn in their design's units (a pole ~110 tall), scaled to the screen with
     * the foot at ([x], [y]); [MARK_DP] dp per unit puts the flag at ~70 dp.
     */
    private fun beginMark(c: Canvas, x: Float, y: Float) {
        c.save()
        c.translate(x, y)
        c.scale(MARK_DP * density, MARK_DP * density)
    }

    /** Destination: a thick pole with a gold knob and a waving red pennant. */
    private fun drawFlag(c: Canvas, x: Float, y: Float) {
        beginMark(c, x, y)
        c.drawOval(-26f, -3.3f, 26f, 11.3f, markShadow)
        markRect.set(-4f, -110f, 4f, 0f)
        c.drawRoundRect(markRect, 4f, 4f, flagPole)
        c.drawRoundRect(markRect, 4f, 4f, markInk3)
        markPath.rewind()
        markPath.moveTo(4f, -104f)
        markPath.quadTo(30f, -108f, 62f, -88f)
        markPath.quadTo(30f, -74f, 4f, -70f)
        markPath.close()
        c.drawPath(markPath, flagRed)
        c.drawPath(markPath, markInk4)
        c.drawCircle(0f, -112f, 7f, flagKnob)
        c.drawCircle(0f, -112f, 7f, markInk25)
        c.restore()
    }

    /** Base camp (the navigation's 집): a big tent with a pennant on top and a campfire beside it. */
    private fun drawBaseCamp(c: Canvas, x: Float, y: Float) {
        beginMark(c, x, y)
        c.drawOval(-46f, -8.9f, 46f, 16.9f, markShadow)
        markPath.rewind()
        markPath.moveTo(-46f, 0f)
        markPath.lineTo(0f, -78f)
        markPath.lineTo(46f, 0f)
        markPath.close()
        c.drawPath(markPath, campTent)
        c.drawPath(markPath, markInk4)
        markPath.rewind()
        markPath.moveTo(-12f, 0f)
        markPath.lineTo(0f, -40f)
        markPath.lineTo(12f, 0f)
        markPath.close()
        c.drawPath(markPath, campDoor)
        c.drawPath(markPath, markInk3)
        c.drawLine(0f, -78f, 0f, -104f, markInk35)
        markPath.rewind()
        markPath.moveTo(0f, -104f)
        markPath.lineTo(24f, -96f)
        markPath.lineTo(0f, -88f)
        markPath.close()
        c.drawPath(markPath, flagRed)
        c.drawPath(markPath, markInk25)
        c.drawCircle(56f, -6f, 9f, campLog)
        markPath.rewind()
        markPath.moveTo(56f, -30f)
        markPath.quadTo(66f, -12f, 56f, -8f)
        markPath.quadTo(46f, -12f, 56f, -30f)
        markPath.close()
        c.drawPath(markPath, campFire)
        c.drawPath(markPath, markInk2)
        c.restore()
    }

    private val renderer = FogMaskRenderer(onTile = { onTile(it) }, onFailed = { onRenderFailed(it) })
    private var tile: FogMaskTile? = null
    /** The newest request sent to [renderer] (its key and rectangle), so a frame doesn't resend it. */
    private var requested: FogMaskRenderer.Job? = null
    private var lastDataRequest = 0L
    private val tileMatrix = Matrix()
    private val chunkSrc = Rect()
    private val chunkDst = RectF()
    private val fogFallback = Paint()

    private fun onTile(t: FogMaskTile) {
        // Tile bitmaps are never reused or changed once handed out, so nothing to give back here.
        tile = t
        invalidate()
    }

    private fun onRenderFailed(job: FogMaskRenderer.Job) {
        // Forget the request, or later frames would wait for it forever ("already on its way").
        if (requested === job) requested = null
        postInvalidateDelayed(DATA_REDRAW_MS)
    }

    /**
     * Draws the fog from the current [tile] (moved and scaled to the view) and asks the renderer for a
     * new one when the view left it, the zoom moved too far from it, or what it shows changed. Data
     * changes are throttled to [DATA_REDRAW_MS]: while driving new cells arrive with every fix.
     */
    private fun drawFog(canvas: Canvas, x0: Double, y0: Double, x1: Double, y1: Double, px: Double) {
        val t = tile
        if (t == null || !t.covers(x0, y0, x1, y1)) {
            // Before the first tile, or past the rendered area until the new tile lands: the dark veil,
            // never the bare map. Only outside the tile: the tile is see-through where explored, and
            // the veil under it darkened the explored roads for a frame (a flash).
            fogFallback.color = fogColor(night, light = false)
            val save = canvas.save()
            if (t != null) {
                // Inset a little: the filtered bitmap's outermost pixels are partly transparent.
                val inset = 2f
                canvas.clipOutRect(
                    ((t.x0 - x0) * px).toFloat() + inset, ((t.y0 - y0) * px).toFloat() + inset,
                    ((t.x1 - x0) * px).toFloat() - inset, ((t.y1 - y0) * px).toFloat() - inset,
                )
            }
            canvas.drawPaint(fogFallback)
            canvas.restoreToCount(save)
        }
        if (t != null) {
            val s = px / t.cellPx
            val left = (t.x0 - x0) * px
            val top = (t.y0 - y0) * px
            val size = FogMaskRenderer.CHUNK * s
            t.chunks.forEachIndexed { k, c ->
                // Each chunk carries a pixel of its neighbours; drawing only its own square from it
                // keeps the filtered edges seamless.
                chunkSrc.set(c.offX, c.offY, c.offX + FogMaskRenderer.CHUNK, c.offY + FogMaskRenderer.CHUNK)
                val dx = left + (k % t.cols) * size
                val dy = top + (k / t.cols) * size
                chunkDst.set(dx.toFloat(), dy.toFloat(), (dx + size).toFloat(), (dy + size).toFloat())
                canvas.drawBitmap(c.bitmap, chunkSrc, chunkDst, maskPaint)
            }
            if (t.key.regions != null || t.key.regionsUnavailable) onReady?.let { onReady = null; it() }
        }
        requestTile(x0, y0, x1, y1, px)
    }

    private val requestAgain = Runnable { dataChanged() }

    /**
     * Explored cells changed. While driving the fog is a hardware layer drawn with a frozen transform:
     * the new tile is asked for right away, without redrawing the layer with the old tile first just
     * to get to [requestTile] (at demo speed new cells come with nearly every fix, and those redraws
     * of the whole oversized layer were most of its redraws and the late frames). Parked, it redraws.
     */
    fun dataChanged() {
        if (freezeTransform && hasTransform && map != null && tx1 > tx0 && ty1 > ty0) {
            requestTile(tx0, ty0, tx1, ty1, width / (tx1 - tx0))
        } else {
            invalidate()
        }
    }

    /** Asks the renderer for a new tile if the current one (or the one on its way) does not fit. */
    private fun requestTile(x0: Double, y0: Double, x1: Double, y1: Double, px: Double) {
        val cellPx = px / MASK_DIV
        var dz = 0
        while (dz < MAX_DZ && cellPx * (1 shl dz) < 1.5) dz++
        val data = (cellsVersion().toLong() shl 32) or (tracks.version.toLong() and 0xFFFFFFFFL)
        val key = FogMaskKey(data, dz, night, fogRegions, regionsUnavailable)
        val t = tile
        // Until the region data is in, a tile would be redrawn whole as soon as it lands (light fog over
        // entered 동): skip it; the plain dark fog covers the wait (behind the loading screen).
        if (t == null && fogRegions == null && !regionsUnavailable) return
        fun fits(k: FogMaskKey, jx0: Double, jy0: Double, jx1: Double, jy1: Double, jCellPx: Double) = k.dz == dz &&
            x0 >= jx0 && y0 >= jy0 && x1 <= jx1 && y1 <= jy1 && (cellPx / jCellPx) in 0.6..1.6
        val r = requested
        val tileFits = t != null && fits(t.key, t.x0, t.y0, t.x1, t.y1, t.cellPx)
        if (tileFits && t!!.key == key) return
        if (r != null && r.key == key && fits(r.key, r.x0, r.y0, r.x1, r.y1, r.cellPx)) return  // already on its way
        val now = android.os.SystemClock.uptimeMillis()
        // The region data arriving changes the whole fog (light fog over entered 동): no waiting then.
        val regionsArrived = t != null && t.key.regions == null && key.regions != null
        if (tileFits && !regionsArrived && now - lastDataRequest < DATA_REDRAW_MS) {
            // Only the content changed: keep showing the current tile a little longer.
            removeCallbacks(requestAgain)
            postDelayed(requestAgain, DATA_REDRAW_MS - (now - lastDataRequest))
            return
        }
        lastDataRequest = now
        // Render a margin around the view so panning and driving re-renders reuse the tile.
        // Driving: the view already includes the slide margin and the tile is redrawn for new cells
        // every few hundred ms anyway, so a smaller extra margin keeps the bitmaps (and uploads) small;
        // still enough for about two re-renders around the car before the next tile lands.
        val margin = if (freezeTransform) DRIVING_TILE_MARGIN else TILE_MARGIN
        val mx = (x1 - x0) * margin
        val my = (y1 - y0) * margin
        val job = FogMaskRenderer.Job(
            key, x0 - mx, y0 - my, x1 + mx, y1 + my, cellPx,
            tracks.snapshot(),
            changedArea(key),
        )
        requested = job
        renderer.submit(job)
    }

    // What the last request covered, to work out what changed since (see [changedArea]).
    private var logGeneration = Int.MIN_VALUE
    private var logPosition = 0
    private var tracksEpoch = Int.MIN_VALUE
    private var trackTrip = -1L
    private var trackCount = 0
    private var lastRequestKey: FogMaskKey? = null

    /**
     * The area whose fog changed since the last request: the cells explored since then and the route
     * points added since (a new stroke can run over cells already explored). Null means "could be
     * anywhere" (first request, a reset or reload, a new trip, too many changes): the renderer redraws
     * the whole tile.
     */
    private fun changedArea(key: FogMaskKey): DoubleArray? {
        val log = cellLog ?: return null
        val slice = log.readSince(logGeneration, logPosition)
        val sameLog = slice.generation == logGeneration
        logGeneration = slice.generation
        logPosition = slice.size
        val epoch = tracks.epoch
        val sameTracks = epoch == tracksEpoch
        tracksEpoch = epoch
        val growth = tracks.growthSince(trackTrip, trackCount)
        val otherTrip = growth.tripId != trackTrip && growth.count > 0
        val routeGrew = growth.count != trackCount
        trackTrip = growth.tripId
        trackCount = growth.count
        val previous = lastRequestKey
        lastRequestKey = key
        if (previous == null || !sameLog || !sameTracks) return null
        if (otherTrip || (routeGrew && growth.box == null)) return null
        if (slice.cells.size > MAX_CHANGED_CELLS) return null
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        fun add(x: Double, y: Double, w: Double) {
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x + w > maxX) maxX = x + w
            if (y + w > maxY) maxY = y + w
        }
        for (c in slice.cells) add(Tiles.x(c).toDouble(), Tiles.y(c).toDouble(), 1.0)
        growth.box?.let {
            add(it[0], it[1], 0.0)
            add(it[2], it[3], 0.0)
        }
        return if (minX == Double.MAX_VALUE) null else doubleArrayOf(minX, minY, maxX, maxY)
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

    private fun visible(o: Outline, x0: Double, y0: Double, x1: Double, y1: Double): Boolean = o.intersects(x0, y0, x1, y1)

    /** What the built boundary lines depend on. [regions] is [fogRegions]: a new layer comes with
     *  every explored-cells change, but the line styles only depend on the atlas and the visited
     *  읍·면·동 (a 시·군·구 is entered exactly when one of its 동 is). */
    private data class BoundaryKey(val x0: Double, val y0: Double, val px: Double, val regions: RegionLayer?, val w: Int, val h: Int)
    private var boundaryKey: BoundaryKey? = null

    /**
     * Boundary lines, built as plain line segments in screen pixels (see [strokeOutline]) and drawn
     * with one drawLines per style. Stroked paths with dash effects had to be rasterised by the GPU
     * on every fog redraw (~20 ms in dense areas such as Seoul, a late frame at every re-render
     * around the car); segments are cheap quads. They are rebuilt only when the view or the region
     * data changes: while driving that is once per re-render, not per new fog tile.
     */
    private fun drawBoundariesCached(c: Canvas, r: RegionLayer, px: Double, x0: Double, y0: Double, x1: Double, y1: Double) {
        val key = BoundaryKey(x0, y0, px, fogRegions, width, height)
        if (key != boundaryKey) {
            boundaryKey = null
            for (st in strokes) st.clear()
            buildBoundaries(r, px, x0, y0, x1, y1)
            boundaryKey = key
        }
        // Finer levels first, each halo under its line.
        for (st in strokes) st.draw(c)
    }

    private fun buildBoundaries(r: RegionLayer, px: Double, x0: Double, y0: Double, x1: Double, y1: Double) {
        val n = r.counts
        if (px >= DONG_LINES_PX) {
            r.atlas.dongs.forEachIndexed { i, d ->
                if (visible(d.outline, x0, y0, x1, y1)) {
                    strokeOutline(d.outline, px, x0, y0, if (n.dong[i] > 0) dongLineIn else dongLine)
                }
            }
        }
        if (px >= DISTRICT_LINES_PX) {
            r.atlas.districts.forEachIndexed { i, d ->
                val o = d.outline ?: return@forEachIndexed
                if (!visible(o, x0, y0, x1, y1)) return@forEachIndexed
                if (n.district[i] > 0) strokeOutline(o, px, x0, y0, districtHaloIn, districtLineIn)
                else strokeOutline(o, px, x0, y0, districtHalo, districtLine)
            }
        }
        r.atlas.provinces.forEach { p ->
            val o = p.outline ?: return@forEach
            if (visible(o, x0, y0, x1, y1)) strokeOutline(o, px, x0, y0, provinceHalo, provinceLine)
        }
    }

    /**
     * Adds the part of [o] that is on screen to each stroke as segments. Stroking the whole cached
     * outline under a canvas scale made the GPU rasterise every province and district border in full
     * (tens of thousands of points, almost all off screen). Here segments outside the view are
     * skipped and points closer than [MIN_SEGMENT_PX] merged. Dashes follow the distance along the
     * whole ring, so they stay put on the map while it pans.
     */
    private fun strokeOutline(o: Outline, scale: Double, x0: Double, y0: Double, vararg strokes: Stroke) {
        val pad = 8f * density
        val left = -pad
        val top = -pad
        val right = width + pad
        val bottom = height + pad
        fun emit(ax: Float, ay: Float, bx: Float, by: Float, along: Double) {
            for (st in strokes) st.add(ax, ay, bx, by, along)
        }
        for (r in o.rings) {
            var penDown = false
            var kx = 0f
            var ky = 0f
            var kAlong = 0.0
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
                val seg = hypot((px1 - px0).toDouble(), (py1 - py0).toDouble())
                val onScreen = max(px0, px1) >= left && min(px0, px1) <= right && max(py0, py1) >= top && min(py0, py1) <= bottom
                if (onScreen) {
                    if (!penDown) {
                        kx = px0
                        ky = py0
                        kAlong = along
                        penDown = true
                    }
                    if (abs(px1 - kx) + abs(py1 - ky) >= MIN_SEGMENT_PX || k == n) {
                        emit(kx, ky, px1, py1, kAlong)
                        kx = px1
                        ky = py1
                        kAlong = along + seg
                    }
                } else if (penDown) {
                    // Through the last merged point too, so the corner just inside the edge is kept.
                    if (kx != px0 || ky != py0) emit(kx, ky, px0, py0, kAlong)
                    emit(px0, py0, px1, py1, along)
                    penDown = false
                }
                along += seg
                px0 = px1
                py0 = py1
                k += 2
            }
        }
    }

    /** A line style in dp, holding the current view's segments (x0, y0, x1, y1 each). */
    private inner class Stroke(argb: Int, widthDp: Float, dashDp: Float = 0f) {
        private val on = dashDp * density
        private val period = on * 1.8f
        private var segs = FloatArray(1024)
        private var count = 0
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            style = Paint.Style.STROKE
            // Lines that come out at most 1.5 px are drawn as hairlines: the GPU draws those in one
            // batch, while each wider segment became a path of its own (a rounded rect per dash of the
            // 읍·면·동 lines), most of each frame's GPU work while the parked map was dragged.
            val px = widthDp * density
            strokeWidth = if (px <= HAIRLINE_PX) 0f else px
            // Round caps also fill the corners between consecutive segments.
            strokeCap = Paint.Cap.ROUND
        }

        fun clear() {
            count = 0
        }

        private fun push(ax: Float, ay: Float, bx: Float, by: Float) {
            if (count + 4 > segs.size) segs = segs.copyOf(segs.size * 2)
            segs[count] = ax
            segs[count + 1] = ay
            segs[count + 2] = bx
            segs[count + 3] = by
            count += 4
        }

        /** Adds a segment that starts [along] px from its ring's start (cut into dashes if dashed). */
        fun add(ax: Float, ay: Float, bx: Float, by: Float, along: Double) {
            if (on <= 0f) {
                push(ax, ay, bx, by)
                return
            }
            val len = hypot((bx - ax).toDouble(), (by - ay).toDouble()).toFloat()
            if (len <= 0f) return
            val ux = (bx - ax) / len
            val uy = (by - ay) / len
            var t = 0f
            var phase = (along % period).toFloat()
            while (t < len) {
                if (phase < on) {
                    val end = min(len, t + (on - phase))
                    push(ax + ux * t, ay + uy * t, ax + ux * end, ay + uy * end)
                    t = end
                    phase = on
                } else {
                    t += period - phase
                    phase = 0f
                }
            }
        }

        fun draw(c: Canvas) {
            if (count > 0) c.drawLines(segs, 0, count, paint)
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
        const val DONG = 2
        /** Marker scale: dp per design unit (the destination flag is ~110 units tall). */
        const val MARK_DP = 0.64f
        /** Screen px per grid cell from which 읍·면·동 (zoom ~13) / 시·군·구 (zoom ~9) are drawn. */
        const val DONG_LINES_PX = 4.0
        const val DISTRICT_LINES_PX = 0.25
        /** Drop-off pins closer than this on screen are shown as one numbered pin. */
        const val CLUSTER_DP = 44f
        /** Boundary points closer than this (screen px, Manhattan) are merged when stroking. */
        private const val MIN_SEGMENT_PX = 1.5f
        /** Boundary strokes this thin or thinner (screen px) are drawn as hairlines (see [Stroke]). */
        private const val HAIRLINE_PX = 1.5f
        /** The car farther than this (zoom-20 tiles, ~45 m) from the trail's newest points: no head. */
        private const val TRAIL_SNAP_TILES = 1.5
        /** How many of the trail's newest segments are searched for the car. */
        private const val TRAIL_SEARCH = 24
        /** The head draws at most this many passed points before the layer is redrawn with them. */
        private const val TRAIL_HEAD_MAX = 12
        const val MASK_DIV = 3
        /** Extra fog rendered on each side of the view, as a share of its size. */
        private const val TILE_MARGIN = 0.3
        /** More changed cells than this since the last fog (a jump, a long gap): redraw the whole tile. */
        private const val MAX_CHANGED_CELLS = 4_000
        private const val DRIVING_TILE_MARGIN = 0.2
        /** At most one fog redraw this often for new cells alone (driving reveals cells every fix). */
        private const val DATA_REDRAW_MS = 300L
        /** How long a replaced tile's bitmaps stay untouched before the renderer may reuse them. */
        const val MAX_DZ = 10

        /**
         * Veil over ground not driven yet. Darker over 읍·면·동 never entered, lighter inside the ones
         * already entered; the map shows through both (it is never recoloured, only covered).
         */
        fun fogColor(night: Boolean, light: Boolean): Int = when {
            light && night -> Color.argb(84, 8, 12, 20)
            light -> Color.argb(56, 24, 32, 44)
            night -> Color.argb(150, 8, 12, 20)
            else -> Color.argb(118, 24, 32, 44)
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

