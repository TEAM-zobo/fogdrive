package com.zobo.fogdrive.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zobo.fogdrive.FogRuntime
import com.zobo.fogdrive.demo.DemoCourses
import com.zobo.fogdrive.geo.TileBox
import com.zobo.fogdrive.geo.Tiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.TransitionOptions
import kotlin.math.abs

private const val TAG = "FogMap"
private const val DRIVING_ZOOM = 16.0
private const val DRIVING_MAX_FPS = 20
/** Re-base anyway if the re-rendered map hasn't reached the screen by then (no frame, lost surface). */
private const val ADOPT_TIMEOUT_MS = 400L
/** Extra map rendered around the viewport so the pre-rendered map can slide under the car. */
private const val PAN_MARGIN_PX = 320
/** Re-render the map once the slide uses this much of the margin. */
private const val RECENTER_AT_PX = PAN_MARGIN_PX * 0.6f
/** Slide steps smaller than this are skipped: invisible, and each step costs a full-screen composite. */
private const val MIN_STEP_PX = 0.5f
/** A fix this far from the shown position is a jump (demo start, GPS reset): snap instead of gliding. */
private const val SNAP_METERS = 500.0
/** Fixes kept for replay, and the replay delay range (one fix interval and a bit, see replay). */
private const val FIX_HISTORY = 8
private const val MIN_LAG_MS = 400
private const val MAX_LAG_MS = 2_500
private const val LAG_EASE_MS = 2_000.0
/** Replay delay after a snap, before the fix rate is known (eases to the measured one from there). */
private const val START_LAG_MS = 600.0
private const val ZOOM_ANIM_MS = 250
/** Margin around a region framed from the dex. */
private const val FOCUS_PADDING_PX = 120
/** Detail hidden while driving for a glanceable map (and fewer symbols to place per render). */
private val DRIVING_HIDDEN_LAYERS = listOf(
    "building", "highway-name-path", "highway-name-minor", "waterway_line_label", "label_other", "airport",
)

/** FogDrive styles bundled in assets (built by tools/mapstyle/build_styles.py from OpenFreeMap Positron). */
private fun styleJson(context: Context, night: Boolean): String =
    context.assets.open(if (night) "map/fogdrive-night.json" else "map/fogdrive-day.json")
        .bufferedReader().use { it.readText() }

private fun isNight(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Viewport that holds an oversized pan layer (map + fog) and a car marker fixed at the centre. */
private class MapFrame(context: Context, val pan: FrameLayout, val car: View, val credit: View) : FrameLayout(context) {
    /** Oversize around the viewport: PAN_MARGIN_PX while driving, 0 when parked (no slide). */
    var margin = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }
    /** Called after the pan layer was resized (mode switch), so the driving camera can re-base. */
    var onPanResized: () -> Unit = {}
    init {
        clipChildren = true
        // Nothing here is meaningful to accessibility; hiding the whole subtree keeps per-frame
        // slides from emitting content-change events (TalkBack noise; UI tools never see "idle").
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(pan)
        addView(car)
        addView(credit)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        pan.measure(
            MeasureSpec.makeMeasureSpec(w + 2 * margin, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h + 2 * margin, MeasureSpec.EXACTLY),
        )
        car.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        credit.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
        setMeasuredDimension(w, h)
    }

    var slideX = 0f
        private set
    var slideY = 0f
        private set

    /**
     * Slides the pan layer at draw time only. Changing the child's translationX/Y would send a
     * TYPE_WINDOW_CONTENT_CHANGED accessibility event per step (even with the subtree hidden).
     */
    fun slide(x: Float, y: Float) {
        slideX = x
        slideY = y
        invalidate()
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child !== pan || (slideX == 0f && slideY == 0f)) return super.drawChild(canvas, child, drawingTime)
        val save = canvas.save()
        canvas.translate(slideX, slideY)
        val result = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(save)
        return result
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val oldW = pan.width
        val oldH = pan.height
        pan.layout(-margin, -margin, r - l + margin, b - t + margin)
        if (oldW != 0 && (oldW != pan.width || oldH != pan.height)) post { onPanResized() }
        car.layout(0, 0, r - l, b - t)
        val pad = 12
        credit.layout(pad, b - t - credit.measuredHeight - pad, pad + credit.measuredWidth, b - t - pad)
        // The oversized pan layer must never paint outside the viewport (it covered the nav rail).
        clipBounds = Rect(0, 0, r - l, b - t)
    }
}

/** Car marker at the exact centre of the viewport while driving: an arrow pointing the way we drive. */
private class CarMarkerView(context: Context) : View(context) {
    /** Degrees clockwise from north. */
    var bearing = 0f
        set(value) {
            if (abs(value - field) >= 2f) {
                field = value
                invalidate()
            }
        }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0x00, 0x81, 0xF8) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0081F8") }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path().apply {
        moveTo(0f, -30f)
        lineTo(22f, 24f)
        lineTo(0f, 12f)
        lineTo(-22f, 24f)
        close()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, 46f, halo)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(bearing)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, edge)
        canvas.restore()
    }
}

/**
 * Holds the map views and runs the driving camera.
 *
 * Driving keeps the car at the screen centre without re-rendering the map every fix:
 * the map is rendered larger than the screen, and each display frame the whole pan layer
 * (map + fog) is slid by the car's offset from the rendered centre — a cheap compositor move.
 * The shown position replays the recent fixes a little behind real time (see [replay]), so the
 * motion is continuous and at the speed the fixes were actually covered. Only when the slide nears the margin is the map re-rendered around the car,
 * and the slide and fog are swapped to the new centre on the frame that render finishes.
 */
class FogMapHolder(context: Context) : Choreographer.FrameCallback {
    val mapView = MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true))
    val overlay = FogOverlayView(context)
    private val pan = FrameLayout(context).apply {
        // The slid map has nothing for accessibility, and each sub-pixel slide would otherwise emit
        // content-change events (noise for TalkBack, and the screen never looks idle to UI tools).
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(mapView, lp)
        addView(this@FogMapHolder.overlay, lp) // plain `overlay` would resolve to View.getOverlay()
    }
    private val car = CarMarkerView(context).apply { visibility = View.GONE }
    /** Licence-required map credit, drawn above the fog (the engine's own sits under it). */
    private val credit = TextView(context).apply {
        text = "© OpenStreetMap · OpenFreeMap"
        textSize = 13f
        setTextColor(Color.argb(200, 0xE6, 0xEB, 0xF2))
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val mapFrame = MapFrame(context, pan, car, credit).also { it.onPanResized = { onPanResized() } }
    val frame: FrameLayout get() = mapFrame

    var map: MapLibreMap? = null
    var driving = false
        private set

    // Recent fixes (when taken, on the uptime clock; position), replayed [lag] ms behind real time.
    private val fixAt = LongArray(FIX_HISTORY)
    /** How late each fix reached the map (delivery through the recorder and recomposition). */
    private val fixDelay = LongArray(FIX_HISTORY)
    /** Fix clock to uptime: the smallest arrival-minus-fix-time seen, i.e. the fastest delivery. */
    private var clockOffset = Long.MAX_VALUE
    private val fixLat = DoubleArray(FIX_HISTORY)
    private val fixLng = DoubleArray(FIX_HISTORY)
    /** Heading of the segment ending at each fix (NaN when too short to tell), shown while that segment plays. */
    private val fixBearing = FloatArray(FIX_HISTORY)
    private var fixCount = 0
    private var lag = START_LAG_MS
    private var lagTarget = START_LAG_MS
    private var lagEasedAt = 0L
    private val hasFix get() = fixCount > 0

    // Rendered camera centre and its pixel scale (pixels per zoom-20 tile unit).
    private var camX = 0.0
    private var camY = 0.0
    private var pxPerX = 0.0
    private var pxPerY = 0.0
    private var camReady = false
    private var recenterPending = false
    /** Bumped per re-render, so late callbacks of an earlier one are ignored. */
    private var recenterGen = 0
    /** The render thread passed the marker queued after the camera move (see [recenter]). */
    private var markerPassed = false
    /** A frame rendered after the marker: drawn with the new camera. */
    private var newFrameRendered = false
    /** The new frame is on screen, held in place by a texture offset until [adoptRenderedCamera]. */
    private var compensated = false
    // The camera the next re-base moves to, in tile units.
    private var nextCamX = 0.0
    private var nextCamY = 0.0
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val textureShift = Matrix()
    private val adoptTimeout = Runnable { if (recenterPending) adoptRenderedCamera() }
    private var forceStep = false
    private var ticking = false

    /** Region waiting to be framed until the map is ready. */
    var pendingFocus: TileBox? = null

    /** Fits the pending region on screen; false while there is none or the map is not ready yet. */
    fun showFocus(animate: Boolean): Boolean {
        val map = map ?: return false
        val box = pendingFocus ?: return false
        pendingFocus = null
        val bounds = LatLngBounds.from(
            Tiles.yToLat(box.y0.toDouble()), Tiles.xToLng(box.x1.toDouble()),
            Tiles.yToLat(box.y1.toDouble()), Tiles.xToLng(box.x0.toDouble()),
        )
        val update = CameraUpdateFactory.newLatLngBounds(bounds, FOCUS_PADDING_PX)
        if (animate) map.animateCamera(update, 800) else map.moveCamera(update)
        return true
    }

    fun onMapReady(map: MapLibreMap) {
        this.map = map
        // The fog covers the engine's logo/attribution; our own credit label is drawn above the fog.
        map.uiSettings.isLogoEnabled = false
        map.uiSettings.isAttributionEnabled = false
        // Default prefetch loads tiles 4 zoom levels coarser than shown; 2 still covers zooming out
        // a step but holds far fewer tiles in native memory and fetches less over the car's link.
        map.prefetchZoomDelta = 2
        // Re-basing must land on the very frame that first shows the re-rendered map. Guessing it
        // (a fixed 2 vsyncs after "frame rendered") was sometimes a frame late: the new map was shown
        // with the old slide for one frame, a ~190 px jump and back. Now the frame is identified
        // exactly. These callbacks all arrive through the main looper in the order they happened:
        // the marker (queued on the render thread after the move) passes after any frame that was
        // already being rendered, so the next "frame rendered" (the FPS callback) is the first with
        // the new camera, and the texture update after it shows that frame.
        map.setOnFpsChangedListener { if (recenterPending && markerPassed) newFrameRendered = true }
        (mapView.renderView as? TextureView)?.let { tv ->
            val engine = tv.surfaceTextureListener
            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    engine?.onSurfaceTextureAvailable(st, w, h)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    engine?.onSurfaceTextureSizeChanged(st, w, h)
                }
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = engine?.onSurfaceTextureDestroyed(st) ?: true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    engine?.onSurfaceTextureUpdated(st)
                    onMapFrameShown(tv)
                }
            }
        }
    }

    /**
     * setTransform only invalidates the parent, and the texture view applies a new transform when
     * it is itself redrawn, which otherwise waits for the next map frame: the slide was already
     * re-based, so the map jumped by the shift until then.
     */
    private fun clearTextureShift() {
        (mapView.renderView as? TextureView)?.let {
            it.setTransform(null)
            it.invalidate()
        }
    }

    /**
     * Called while the map texture is being drawn with a new frame. If that frame is the first one
     * with the new camera, it is shifted back to where the old map was (this same draw applies the
     * transform), so nothing moves on screen; the slide and fog are re-based on the next frame,
     * together with removing the shift.
     */
    private fun onMapFrameShown(tv: TextureView) {
        if (!recenterPending || !newFrameRendered || compensated) return
        compensated = true
        textureShift.setTranslate(((nextCamX - camX) * pxPerX).toFloat(), ((nextCamY - camY) * pxPerY).toFloat())
        tv.setTransform(textureShift)
        val gen = recenterGen
        Choreographer.getInstance().postFrameCallback { if (recenterPending && gen == recenterGen) adoptRenderedCamera() }
    }

    private var night: Boolean? = null

    /** The pan layer changed size: the rendered map moved, so re-base the slide on the next render. */
    private fun onPanResized() {
        overlay.invalidate()
        if (driving && hasFix) {
            replay(SystemClock.uptimeMillis())
            recenter(shownLat, shownLng)
        }
    }

    /** Applies the day/night style; re-applied when the system theme flips (uiMode is a handled config change). */
    fun setNight(context: Context, value: Boolean) {
        val m = map ?: return
        if (night == value) return
        night = value
        overlay.night = value
        m.setStyle(Style.Builder().fromJson(styleJson(context, value))) {
            applyMode()
            overlay.invalidate()
        }
    }

    /** Lifecycle: no frame loop while the app is in the background. */
    fun onStart() {
        if (driving) startTicking()
    }

    /** Driving/parked map settings; called on mode change and again once the style loads. */
    fun setDriving(value: Boolean) {
        driving = value
        mapFrame.margin = if (value) PAN_MARGIN_PX else 0
        applyMode()
        car.visibility = if (value) View.VISIBLE else View.GONE
        overlay.showCurrent = !value
        overlay.showLabels = !value
        // Driving slides the pan layer every frame, and without a layer the fog's whole display list
        // (bitmap, boundary paths, an offscreen pass per worn-road level) was replayed on each one:
        // ~19 fps with 50-150 ms frames. As a hardware layer it is drawn once per change (a re-render
        // or new cells) and then only composited. Parked, the fog redraws on every camera move anyway.
        overlay.setLayerType(if (value) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE, null)
        if (value) {
            startTicking()
        } else {
            stopTicking()
            fixCount = 0
            camReady = false
            recenterPending = false
            main.removeCallbacks(adoptTimeout)
            if (compensated) {
                compensated = false
                clearTextureShift()
            }
            overlay.freezeTransform = false
            mapFrame.slide(0f, 0f)
            overlay.invalidate()
        }
    }

    fun applyMode() {
        mapView.setMaximumFps(if (driving) DRIVING_MAX_FPS else 60)
        val m = map ?: return
        m.uiSettings.setAllGesturesEnabled(!driving)
        m.uiSettings.isRotateGesturesEnabled = false
        m.uiSettings.isTiltGesturesEnabled = false
        m.style?.let { style ->
            // Labels fade in over 300 ms after every camera move. Driving re-renders around the car
            // ~3 times a second, so the fades kept the map rendering at its frame cap (~16 fps of a
            // 2200 px surface) and starved the UI of GPU time; while driving labels just appear.
            style.transition = if (driving) TransitionOptions(0, 0, false) else TransitionOptions(300, 0, true)
            for (id in DRIVING_HIDDEN_LAYERS) {
                style.getLayer(id)?.setProperties(PropertyFactory.visibility(if (driving) Property.NONE else Property.VISIBLE))
            }
        }
    }

    /**
     * New location while driving. Gliding from the shown position to each new fix over the last
     * interval changed speed at every fix whenever fixes came unevenly (demo corners, late
     * recompositions): at high speed each fix is ~100 m apart, so the map visibly surged and braked.
     * Instead the fixes are replayed along their own timeline, a little more than one interval late,
     * so between fixes the car moves at the speed the fixes were actually covered. The timeline is
     * when each fix was taken ([timeMs]), not when it arrived: delivery sometimes lags ~200 ms, and
     * replaying by arrival slowed the car before such a fix and rushed it after.
     */
    fun onFix(lat: Double, lng: Double, timeMs: Long? = null) {
        if (!driving) return
        val now = SystemClock.uptimeMillis()
        val last = fixCount - 1
        if (last >= 0 && fixLat[last] == lat && fixLng[last] == lng) return  // same fix, re-delivered
        val jump = last < 0 || Tiles.distanceMeters(fixLat[last], fixLng[last], lat, lng) > SNAP_METERS
        var bearing = Float.NaN
        if (jump) {
            fixCount = 0
            lag = START_LAG_MS
            clockOffset = Long.MAX_VALUE
        } else {
            advanceLag(now)  // bring the lag easing up to now before the history changes
            if (now - fixAt[last] > MAX_LAG_MS) {
                // Moving again after a stop or a feed gap: playback has long reached the last fix, so
                // start the new segment from it now instead of stretching it over the whole pause
                // (which would jump most of the segment at once and push the lag to its maximum).
                fixAt[0] = now - lag.toLong()
                fixLat[0] = fixLat[last]
                fixLng[0] = fixLng[last]
                fixBearing[0] = Float.NaN
                fixDelay[0] = 0
                fixCount = 1
            }
            val prev = fixCount - 1
            if (Tiles.distanceMeters(fixLat[prev], fixLng[prev], lat, lng) >= 3.0) {
                val east = Tiles.lngToX(lng) - Tiles.lngToX(fixLng[prev])
                val north = Tiles.latToY(fixLat[prev]) - Tiles.latToY(lat)
                bearing = Math.toDegrees(kotlin.math.atan2(east, north)).toFloat()
            }
        }
        if (fixCount == FIX_HISTORY) {
            System.arraycopy(fixAt, 1, fixAt, 0, FIX_HISTORY - 1)
            System.arraycopy(fixLat, 1, fixLat, 0, FIX_HISTORY - 1)
            System.arraycopy(fixLng, 1, fixLng, 0, FIX_HISTORY - 1)
            System.arraycopy(fixBearing, 1, fixBearing, 0, FIX_HISTORY - 1)
            System.arraycopy(fixDelay, 1, fixDelay, 0, FIX_HISTORY - 1)
            fixCount--
        }
        var at = now
        if (timeMs != null) {
            val offset = now - timeMs
            // A fix that seems to have taken longer than the whole replay delay to arrive means the fix
            // clock stepped back (time sync, a feed switching sources): re-base on it instead of keeping
            // every later fix "late", which would pin the lag at its maximum and squash the timeline.
            clockOffset = if (offset - clockOffset > MAX_LAG_MS) offset else minOf(clockOffset, offset)
            at = timeMs + clockOffset
        }
        if (fixCount > 0) at = at.coerceAtLeast(fixAt[fixCount - 1] + 1)
        fixAt[fixCount] = at
        fixDelay[fixCount] = (now - at).coerceAtLeast(0L)
        fixLat[fixCount] = lat
        fixLng[fixCount] = lng
        fixBearing[fixCount] = bearing
        fixCount++
        lagTarget = targetLag()
        if (jump) {
            lagEasedAt = now
            recenter(lat, lng)
        }
        startTicking()
    }

    /**
     * Replay delay that covers the longest recent gap between fixes plus the slowest recent
     * delivery, so playback never runs past the newest fix before the next one arrives.
     */
    private fun targetLag(): Double {
        var gap = 0L
        var delay = 0L
        for (i in 0 until fixCount) {
            if (i > 0) gap = maxOf(gap, fixAt[i] - fixAt[i - 1])
            delay = maxOf(delay, fixDelay[i])
        }
        return ((gap + delay) * 1.25).coerceIn(MIN_LAG_MS.toDouble(), MAX_LAG_MS.toDouble())
    }

    /**
     * Eases the replay delay toward [lagTarget] up to [now]: over a couple of seconds, so a change in
     * the fix rate bends the speed by a few percent instead of jumping. The only place the lag moves.
     */
    private fun advanceLag(now: Long) {
        val dt = (now - lagEasedAt).coerceAtLeast(0L)
        lagEasedAt = now
        // Growing the lag faster than real time would run the replay backwards: keep it at ≥ half speed.
        lag += ((lagTarget - lag) * (dt / LAG_EASE_MS).coerceAtMost(1.0)).coerceAtMost(dt * 0.5)
    }

    // Shown position, written by [replay] (fields, not a Pair: it runs every display frame).
    private var shownLat = 0.0
    private var shownLng = 0.0

    /**
     * Moves the shown car to the fix track sampled at `now - lag` (after [advanceLag]), and points
     * the arrow along the segment being drawn.
     */
    private fun replay(now: Long) {
        advanceLag(now)
        val n = fixCount
        if (n == 0) return
        val t = now - lag
        if (n == 1 || t <= fixAt[0]) {
            shownLat = fixLat[0]
            shownLng = fixLng[0]
            return
        }
        if (t >= fixAt[n - 1]) {
            shownLat = fixLat[n - 1]
            shownLng = fixLng[n - 1]
            return
        }
        var i = 1
        while (fixAt[i] < t) i++
        if (!fixBearing[i].isNaN()) car.bearing = fixBearing[i]
        val f = (t - fixAt[i - 1]) / (fixAt[i] - fixAt[i - 1])
        shownLat = fixLat[i - 1] + (fixLat[i] - fixLat[i - 1]) * f
        shownLng = fixLng[i - 1] + (fixLng[i] - fixLng[i - 1]) * f
    }

    private fun recenter(lat: Double, lng: Double) {
        val m = map ?: return
        recenterPending = true
        val gen = ++recenterGen
        markerPassed = false
        newFrameRendered = false
        nextCamX = Tiles.lngToX(lng)
        nextCamY = Tiles.latToY(lat)
        startTicking()
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), DRIVING_ZOOM))
        if (!camReady) {
            adoptRenderedCamera()
            return
        }
        mapView.queueEvent { main.post { if (gen == recenterGen) markerPassed = true } }
        main.removeCallbacks(adoptTimeout)
        main.postDelayed(adoptTimeout, ADOPT_TIMEOUT_MS)
    }

    /** The map now shows the new centre: re-base the slide and the fog on the same frame. */
    private fun adoptRenderedCamera() {
        val m = map ?: return
        val target = m.cameraPosition.target ?: return
        val p0 = m.projection.toScreenLocation(target)
        val probe = LatLng(target.latitude + 0.001, target.longitude + 0.001)
        val p1 = m.projection.toScreenLocation(probe)
        camX = Tiles.lngToX(target.longitude)
        camY = Tiles.latToY(target.latitude)
        pxPerX = (p1.x - p0.x) / (Tiles.lngToX(probe.longitude) - camX)
        pxPerY = (p1.y - p0.y) / (Tiles.latToY(probe.latitude) - camY)
        camReady = true
        recenterPending = false
        main.removeCallbacks(adoptTimeout)
        if (compensated) {
            compensated = false
            clearTextureShift()
        }
        Log.d(TAG, "re-rendered around the car")
        // Apply the slide relative to the new base on this frame, even if the step looks small.
        forceStep = true
        overlay.captureTransform()
        overlay.freezeTransform = true
        overlay.invalidate()
        // Not doFrame(): that also re-posts the frame callback, and each re-base then added one more
        // callback chain that ran every vsync for the rest of the drive (dozens per frame after a
        // few minutes at high speed).
        step()
    }

    override fun doFrame(frameTimeNanos: Long) {
        step()
        // Keep ticking only while the glide is running or a re-base is due; at a standstill (no new
        // fixes) the loop sleeps until the next fix instead of waking the main thread every vsync.
        val gliding = hasFix && SystemClock.uptimeMillis() - lag < fixAt[fixCount - 1]
        if (ticking && driving && (gliding || recenterPending || forceStep)) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            ticking = false
        }
    }

    /** Slides the pan layer to the shown position, and re-renders around the car near the margin. */
    private fun step() {
        if (driving && hasFix && camReady) {
            replay(SystemClock.uptimeMillis())
            val dx = ((Tiles.lngToX(shownLng) - camX) * pxPerX).toFloat()
            val dy = ((Tiles.latToY(shownLat) - camY) * pxPerY).toFloat()
            if (forceStep || abs(-dx - mapFrame.slideX) >= MIN_STEP_PX || abs(-dy - mapFrame.slideY) >= MIN_STEP_PX) {
                forceStep = false
                mapFrame.slide(-dx, -dy)
            }
            if (!recenterPending && (abs(dx) > RECENTER_AT_PX || abs(dy) > RECENTER_AT_PX)) recenter(shownLat, shownLng)
        }
    }

    private fun startTicking() {
        if (ticking || !driving) return
        ticking = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopTicking() {
        ticking = false
        Choreographer.getInstance().removeFrameCallback(this)
    }
}

@Composable
fun FogMap(
    modifier: Modifier,
    version: Int,
    current: Pair<Double, Double>?,
    /** When [current] was taken (Fix.timeMs); paces the driving glide. */
    fixTimeMs: Long? = null,
    landings: List<Pair<Double, Double>>,
    detour: Pair<Double, Double>?,
    follow: Boolean,
    recenterSignal: Int,
    onUserMoved: () -> Unit,
    /** Driving: no gestures; the car stays centred and the map glides under it. */
    driving: Boolean = false,
    /** Bumped by the zoom buttons. */
    zoomInSignal: Int = 0,
    zoomOutSignal: Int = 0,
    /** Region to frame (from the dex); [onFocusShown] is called once the camera has moved there. */
    focus: TileBox? = null,
    onFocusShown: () -> Unit = {},
    /** Past trip to draw (record screen). */
    route: TripRoute? = null,
    /** Tint roads driven on several trips. */
    showWornRoads: Boolean = true,
) {
    val context = LocalContext.current
    val holder = remember {
        MapLibre.getInstance(context)
        FogMapHolder(context)
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val mv = holder.mapView
        mv.onCreate(null)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    mv.onStart()
                    holder.onStart()
                }
                Lifecycle.Event.ON_RESUME -> mv.onResume()
                Lifecycle.Event.ON_PAUSE -> mv.onPause()
                Lifecycle.Event.ON_STOP -> {
                    holder.stopTicking()
                    mv.onStop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mv.getMapAsync { map ->
            holder.onMapReady(map)
            holder.overlay.map = map
            holder.overlay.cellsAtLevel = { FogRuntime.repo.cellsAtLevel(it) }
            holder.overlay.revisits = { FogRuntime.repo.revisits }
            holder.overlay.cellsVersion = { FogRuntime.repo.version.value }
            holder.overlay.revisitVersion = { FogRuntime.repo.revisitVersion }
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.uiSettings.isCompassEnabled = false
            map.setMinZoomPreference(5.0)
            map.setMaxZoomPreference(18.0)
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(current?.first ?: DemoCourses.START.lat, current?.second ?: DemoCourses.START.lng))
                .zoom(if (driving) DRIVING_ZOOM else 14.0)
                .build()
            holder.setNight(context, isNight(context))
            // While driving the fog is re-based only when a re-render lands (adoptRenderedCamera).
            map.addOnCameraMoveListener { if (!holder.driving) holder.overlay.invalidate() }
            map.addOnCameraIdleListener { if (!holder.driving) holder.overlay.invalidate() }
            map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) = onUserMoved()
                override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
                override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
            })
            holder.setDriving(driving)
            current?.let { holder.onFix(it.first, it.second, fixTimeMs) }
            if (holder.showFocus(animate = false)) onFocusShown()
        }
        onDispose {
            holder.stopTicking()
            lifecycleOwner.lifecycle.removeObserver(observer)
            mv.onPause()
            mv.onStop()
            mv.onDestroy()
        }
    }

    LaunchedEffect(version) {
        // Light fog over the 읍·면·동 already entered, boundaries, and the rates in the labels.
        val layer = withContext(Dispatchers.Default) {
            FogRuntime.regions?.let { RegionLayer(it.atlas, it.levels(FogRuntime.repo)) }
        }
        if (layer == null) holder.overlay.regionsUnavailable = true else holder.overlay.regions = layer
    }
    LaunchedEffect(version, landings, detour) {
        holder.overlay.landings = landings
        holder.overlay.detour = detour
        holder.overlay.invalidate()
    }
    LaunchedEffect(current, driving) {
        holder.overlay.current = current
        // The car dot is drawn only when parked; while driving a fix alone changes nothing in the fog
        // (new cells bump `version`), and redrawing it 5 times a second cost a full CPU mask each time.
        if (!driving) holder.overlay.invalidate()
    }
    LaunchedEffect(driving) {
        if (holder.map != null) holder.setDriving(driving)
    }
    val night = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    LaunchedEffect(night) {
        holder.setNight(context, night)
    }
    LaunchedEffect(current, follow, driving) {
        val map = holder.map ?: return@LaunchedEffect
        if (current == null) return@LaunchedEffect
        if (driving) {
            holder.onFix(current.first, current.second, fixTimeMs)
        } else if (follow) {
            map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(current.first, current.second)), 600)
        }
    }
    LaunchedEffect(showWornRoads) {
        holder.overlay.showWornRoads = showWornRoads
        holder.overlay.invalidate()
    }
    LaunchedEffect(route) {
        holder.overlay.route = route
        holder.overlay.invalidate()
    }
    LaunchedEffect(focus) {
        holder.pendingFocus = focus
        if (holder.showFocus(animate = true)) onFocusShown()
    }
    LaunchedEffect(zoomInSignal) {
        if (zoomInSignal > 0) holder.map?.animateCamera(CameraUpdateFactory.zoomIn(), ZOOM_ANIM_MS)
    }
    LaunchedEffect(zoomOutSignal) {
        if (zoomOutSignal > 0) holder.map?.animateCamera(CameraUpdateFactory.zoomOut(), ZOOM_ANIM_MS)
    }
    LaunchedEffect(recenterSignal) {
        val map = holder.map ?: return@LaunchedEffect
        if (recenterSignal > 0) {
            // Before the first fix "my location" is the home point (향기5로 66), like the start camera.
            val (lat, lng) = current ?: (DemoCourses.START.lat to DemoCourses.START.lng)
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 15.0), 800)
        }
    }

    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = {
            (holder.frame.parent as? ViewGroup)?.removeView(holder.frame)
            holder.frame
        },
    )
}
