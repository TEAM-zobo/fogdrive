package com.zobo.burung.ui

import android.content.res.Configuration
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zobo.burung.FogRuntime
import com.zobo.burung.demo.DemoCourses
import com.zobo.burung.geo.TileBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

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
    /** Navigation destination, marked with a flag. */
    destination: Pair<Double, Double>? = null,
    /** The navigation's 집, marked as the base camp. */
    home: Pair<Double, Double>? = null,
) {
    val context = LocalContext.current
    // One map for the whole activity when it provides one: switching tabs (or into the driving
    // view) re-attaches it instead of building a new map, style, tiles and fog every time.
    val shared = LocalFogMapHolder.current
    val holder = shared ?: remember {
        MapLibre.getInstance(context)
        FogMapHolder(context)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnUserMoved by rememberUpdatedState(onUserMoved)
    val latestOnFocusShown by rememberUpdatedState(onFocusShown)
    val mapReady by holder.ready.collectAsState()

    // The setup below runs once per map, possibly after this screen has left: it must not use this
    // screen's parameters beyond the starting camera (the effects keyed on mapReady apply them).
    DisposableEffect(holder, lifecycleOwner) {
        holder.onUserMoved = { latestOnUserMoved() }
        holder.onFocusShown = { latestOnFocusShown() }
        // Created before attaching: attach replays ON_START/ON_RESUME at once, and the map view must
        // see onCreate before them.
        holder.create { map ->
            holder.onMapReady(map)
            holder.overlay.map = map
            holder.overlay.cellLog = FogRuntime.repo
            holder.overlay.revisitLog = FogRuntime.repo::revisitsSince
            holder.overlay.revisits = { FogRuntime.repo.revisits }
            holder.overlay.tracks = FogRuntime.repo.tracks
            holder.overlay.onReady = {
                holder.fogShown.value = true
                FogRuntime.fogReady.value = true
            }
            holder.overlay.cellsVersion = { FogRuntime.repo.version.value }
            holder.overlay.revisitVersion = { FogRuntime.repo.revisitVersion }
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.uiSettings.isCompassEnabled = false
            map.setMinZoomPreference(5.0)
            map.setMaxZoomPreference(18.0)
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(current?.first ?: DemoCourses.START.lat, current?.second ?: DemoCourses.START.lng))
                // Always the parked view: if this screen is (or later becomes) the driving view,
                // setDriving(true) moves in to the driving zoom once the map is ready.
                .zoom(PARKED_ZOOM)
                .build()
            holder.setNight(context, isNight(context))
            // While driving the fog is re-based only when a re-render lands (adoptRenderedCamera).
            map.addOnCameraMoveListener { if (!holder.driving) holder.overlay.invalidate() }
            map.addOnCameraIdleListener { if (!holder.driving) holder.overlay.invalidate() }
            map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) = holder.onUserMoved()
                override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
                override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
            })
            if (holder.showFocus(animate = false)) holder.onFocusShown()
        }
        val token = holder.attach(lifecycleOwner)
        onDispose {
            if (shared == null) holder.destroy() else holder.detach(token)
        }
    }

    val cellsLoaded by FogRuntime.repo.loaded.collectAsState()
    LaunchedEffect(version, cellsLoaded) {
        // Not before the stored cells are in: a layer counted from an empty log would let the first
        // tile (and the launch loading screen's fogReady) go out with dark fog everywhere.
        if (!cellsLoaded) return@LaunchedEffect
        if (holder.regionsVersion == version) return@LaunchedEffect  // a re-attached map already has it
        // Light fog over the 읍·면·동 already entered, boundaries, and the rates in the labels.
        val layer = withContext(Dispatchers.Default) {
            FogRuntime.regions?.let { RegionLayer(it.atlas, it.levels(FogRuntime.repo)) }
        }
        if (layer == null) holder.overlay.regionsUnavailable = true else holder.overlay.regions = layer
        holder.regionsVersion = version
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
    LaunchedEffect(driving, mapReady) {
        if (mapReady) holder.setDriving(driving)
    }
    val night = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    LaunchedEffect(night) {
        holder.setNight(context, night)
    }
    LaunchedEffect(current, follow, driving, mapReady) {
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
    LaunchedEffect(destination, home) {
        holder.overlay.destination = destination
        holder.overlay.home = home
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

    val fogShown by holder.fogShown.collectAsState()
    Box(modifier.clipToBounds()) {
        AndroidView(
            // The map's own touches (pan, zoom, tap) are not button taps: no click sound.
            modifier = Modifier.fillMaxSize().muteClickSounds(),
            factory = {
                (holder.frame.parent as? ViewGroup)?.removeView(holder.frame)
                holder.frame
            },
        )
        // Only until this map's fog is first drawn (a shared map that is already up shows at once).
        MapLoadingCover(visible = !fogShown)
    }
}

/** The activity's one map (see [FogMap]); null where none is provided (a map per screen then). */
val LocalFogMapHolder = staticCompositionLocalOf<FogMapHolder?> { null }
