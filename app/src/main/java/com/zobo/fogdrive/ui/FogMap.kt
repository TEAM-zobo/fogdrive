package com.zobo.fogdrive.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
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
import kotlin.math.abs

private const val TAG = "FogMap"
private const val DRIVING_ZOOM = 16.0
private const val DRIVING_MAX_FPS = 20
/** Extra map rendered around the viewport so the pre-rendered map can slide under the car. */
private const val PAN_MARGIN_PX = 320
/** Re-render the map once the slide uses this much of the margin. */
private const val RECENTER_AT_PX = PAN_MARGIN_PX * 0.6f
/** Slide steps smaller than this are skipped: invisible, and each step costs a full-screen composite. */
private const val MIN_STEP_PX = 0.5f
/** A fix this far from the shown position is a jump (demo start, GPS reset): snap instead of gliding. */
private const val SNAP_METERS = 500.0
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
 * Between 1 Hz fixes the shown position glides linearly from where it was to the new fix, so the
 * motion is continuous. Only when the slide nears the margin is the map re-rendered around the car,
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

    // Glide between fixes.
    private var fromLat = 0.0
    private var fromLng = 0.0
    private var toLat = 0.0
    private var toLng = 0.0
    private var segStart = 0L
    private var segDuration = 1L
    private var lastFixAt = 0L
    private var hasFix = false

    // Rendered camera centre and its pixel scale (pixels per zoom-20 tile unit).
    private var camX = 0.0
    private var camY = 0.0
    private var pxPerX = 0.0
    private var pxPerY = 0.0
    private var camReady = false
    private var recenterPending = false
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
        mapView.addOnDidFinishRenderingFrameListener(object : MapView.OnDidFinishRenderingFrameListener {
            override fun onDidFinishRenderingFrame(fully: Boolean, frameEncodingTime: Double, frameRenderingTime: Double) {
                // Any rendered frame already shows the new camera (partial frames are displayed too),
                // so re-base now; waiting for `fully` misaligns the slide until all tiles load, and
                // never re-bases at all when tiles fail to load (offline).
                if (recenterPending) adoptRenderedCamera()
            }
        })
    }

    private var night: Boolean? = null

    /** The pan layer changed size: the rendered map moved, so re-base the slide on the next render. */
    private fun onPanResized() {
        overlay.invalidate()
        if (driving && hasFix) {
            val (lat, lng) = shownAt(SystemClock.uptimeMillis())
            recenter(lat, lng)
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
        if (value) {
            startTicking()
        } else {
            stopTicking()
            hasFix = false
            camReady = false
            recenterPending = false
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
            for (id in DRIVING_HIDDEN_LAYERS) {
                style.getLayer(id)?.setProperties(PropertyFactory.visibility(if (driving) Property.NONE else Property.VISIBLE))
            }
        }
    }

    /** New location while driving: glide from the shown position to it over one fix interval. */
    fun onFix(lat: Double, lng: Double) {
        if (!driving) return
        val now = SystemClock.uptimeMillis()
        val (shownLat, shownLng) = shownAt(now)
        val jump = !hasFix || Tiles.distanceMeters(shownLat, shownLng, lat, lng) > SNAP_METERS
        if (jump) {
            fromLat = lat
            fromLng = lng
            segDuration = 1
            recenter(lat, lng)
        } else {
            fromLat = shownLat
            fromLng = shownLng
            segDuration = (now - lastFixAt).coerceIn(250L, 2_000L)
            if (Tiles.distanceMeters(shownLat, shownLng, lat, lng) >= 3.0) {
                val east = Tiles.lngToX(lng) - Tiles.lngToX(shownLng)
                val north = Tiles.latToY(shownLat) - Tiles.latToY(lat)
                car.bearing = Math.toDegrees(kotlin.math.atan2(east, north)).toFloat()
            }
        }
        toLat = lat
        toLng = lng
        segStart = now
        lastFixAt = now
        hasFix = true
        startTicking()
    }

    private fun shownAt(now: Long): Pair<Double, Double> {
        if (!hasFix) return toLat to toLng
        val t = ((now - segStart).toDouble() / segDuration).coerceIn(0.0, 1.0)
        return (fromLat + (toLat - fromLat) * t) to (fromLng + (toLng - fromLng) * t)
    }

    private fun recenter(lat: Double, lng: Double) {
        val m = map ?: return
        recenterPending = true
        startTicking()
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), DRIVING_ZOOM))
        if (!camReady) adoptRenderedCamera()
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
        Log.d(TAG, "re-rendered around the car")
        // Apply the slide relative to the new base on this frame, even if the step looks small.
        forceStep = true
        overlay.captureTransform()
        overlay.freezeTransform = true
        overlay.invalidate()
        doFrame(System.nanoTime())
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (driving && hasFix && camReady) {
            val (lat, lng) = shownAt(SystemClock.uptimeMillis())
            val dx = ((Tiles.lngToX(lng) - camX) * pxPerX).toFloat()
            val dy = ((Tiles.latToY(lat) - camY) * pxPerY).toFloat()
            if (forceStep || abs(-dx - mapFrame.slideX) >= MIN_STEP_PX || abs(-dy - mapFrame.slideY) >= MIN_STEP_PX) {
                forceStep = false
                mapFrame.slide(-dx, -dy)
            }
            if (!recenterPending && (abs(dx) > RECENTER_AT_PX || abs(dy) > RECENTER_AT_PX)) recenter(lat, lng)
        }
        // Keep ticking only while the glide is running or a re-base is due; at a standstill (no new
        // fixes) the loop sleeps until the next fix instead of waking the main thread every vsync.
        val gliding = hasFix && SystemClock.uptimeMillis() < segStart + segDuration
        if (ticking && driving && (gliding || recenterPending || forceStep)) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            ticking = false
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
            current?.let { holder.onFix(it.first, it.second) }
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
    LaunchedEffect(version, current, landings, detour) {
        holder.overlay.current = current
        holder.overlay.landings = landings
        holder.overlay.detour = detour
        holder.overlay.invalidate()
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
            holder.onFix(current.first, current.second)
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
