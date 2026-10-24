package com.zobo.fogdrive.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zobo.fogdrive.FogRuntime
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

// Clean, low-clutter light basemap: explored areas read clearly against the dark fog.
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"

/** Holds the MapView + overlay so Compose state changes can push data into them. */
class FogMapHolder(val mapView: MapView, val overlay: FogOverlayView) {
    var map: MapLibreMap? = null
}

/** OpenMapTiles carries `name:ko`; prefer it over the local/English name on every label layer. */
private fun useKoreanLabels(style: Style) {
    val korean = Expression.coalesce(Expression.get("name:ko"), Expression.get("name"))
    for (layer in style.layers) {
        if (layer is SymbolLayer && layer.textField.value != null) {
            layer.setProperties(PropertyFactory.textField(korean))
        }
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
    /** Driving: no gestures, camera locked on the car without animation. */
    driving: Boolean = false,
) {
    val context = LocalContext.current
    val holder = remember {
        MapLibre.getInstance(context)
        val mapView = MapView(context)
        val overlay = FogOverlayView(context)
        FogMapHolder(mapView, overlay)
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val mv = holder.mapView
        mv.onCreate(null)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mv.onStart()
                Lifecycle.Event.ON_RESUME -> mv.onResume()
                Lifecycle.Event.ON_PAUSE -> mv.onPause()
                Lifecycle.Event.ON_STOP -> mv.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mv.getMapAsync { map ->
            holder.map = map
            holder.overlay.map = map
            holder.overlay.cellsAtLevel = { FogRuntime.repo.cellsAtLevel(it) }
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.uiSettings.isCompassEnabled = false
            map.setMinZoomPreference(5.0)
            map.setMaxZoomPreference(18.0)
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(current?.first ?: 37.5446, current?.second ?: 127.0559))
                .zoom(14.0)
                .build()
            map.setStyle(STYLE_URL) { style ->
                useKoreanLabels(style)
                holder.overlay.invalidate()
            }
            map.addOnCameraMoveListener { holder.overlay.invalidate() }
            map.addOnCameraIdleListener { holder.overlay.invalidate() }
            map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) = onUserMoved()
                override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
                override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
            })
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mv.onPause()
            mv.onStop()
            mv.onDestroy()
        }
    }

    LaunchedEffect(version, current, landings, detour) {
        holder.overlay.current = current
        holder.overlay.landings = landings
        holder.overlay.detour = detour
        holder.overlay.invalidate()
    }
    LaunchedEffect(driving, holder.map) {
        holder.map?.uiSettings?.setAllGesturesEnabled(!driving)
        if (!driving) {
            holder.map?.uiSettings?.isRotateGesturesEnabled = false
            holder.map?.uiSettings?.isTiltGesturesEnabled = false
        }
    }
    LaunchedEffect(current, follow, driving) {
        val map = holder.map ?: return@LaunchedEffect
        if (current == null) return@LaunchedEffect
        val target = LatLng(current.first, current.second)
        when {
            // Moving map without animation while driving (Pleos: minimise motion on screen).
            driving -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, 16.0))
            follow -> map.animateCamera(CameraUpdateFactory.newLatLng(target), 600)
        }
    }
    LaunchedEffect(recenterSignal) {
        val map = holder.map ?: return@LaunchedEffect
        if (recenterSignal > 0 && current != null) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(current.first, current.second), 15.0), 800)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            FrameLayout(it).apply {
                val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                (holder.mapView.parent as? ViewGroup)?.removeView(holder.mapView)
                (holder.overlay.parent as? ViewGroup)?.removeView(holder.overlay)
                addView(holder.mapView, lp)
                addView(holder.overlay, lp)
            }
        },
    )
}
