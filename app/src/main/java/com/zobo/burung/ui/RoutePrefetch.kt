package com.zobo.burung.ui

import android.content.Context
import android.util.Log
import com.zobo.burung.car.Place
import com.zobo.burung.geo.Tiles
import org.json.JSONObject
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.geometry.LatLngBounds
import com.mapbox.mapboxsdk.offline.OfflineGeometryRegionDefinition
import com.mapbox.mapboxsdk.offline.OfflineManager
import com.mapbox.mapboxsdk.offline.OfflineRegion
import com.mapbox.mapboxsdk.offline.OfflineRegionDefinition
import com.mapbox.mapboxsdk.offline.OfflineRegionError
import com.mapbox.mapboxsdk.offline.OfflineRegionStatus
import com.mapbox.mapboxsdk.offline.OfflineTilePyramidRegionDefinition
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point

/**
 * Downloads the map along a route before it is driven: the demo course picked in settings and the
 * area around the navigation's destination. Tiles fetched while driving arrive late over the car's
 * link and render as blank squares for a moment; these are already on disk (MapLibre's offline
 * database, which the map reads first). Keeps the last few routes; older ones are deleted.
 * Call from the main thread (MapLibre's offline callbacks run there).
 */
object RoutePrefetch {
    private const val TAG = "RoutePrefetch"
    /** Driving shows z16, parked z14; z13 covers zooming out a step. */
    private const val MIN_ZOOM = 13.0
    private const val MAX_ZOOM = 16.0
    /** Around a destination: about 1.5 km each way (walking distance from the drop-off). */
    private const val AREA_M = 1_500.0
    private const val KEEP = 4
    private const val KEY = "route"
    /**
     * The downloader fetches its style over HTTP (not asset:// or file://), so it is given the
     * published OpenFreeMap Positron style our bundled day/night styles are built from: the same
     * tile source, so the tiles it stores are the ones the map asks for.
     */
    private const val DOWNLOAD_STYLE = "https://tiles.openfreemap.org/styles/positron"

    fun course(context: Context, id: String, route: List<Pair<Double, Double>>) {
        if (route.size < 2) return
        val line = LineString.fromLngLats(route.map { (lat, lng) -> Point.fromLngLat(lng, lat) })
        fetch(context, "course:$id") { style, ratio -> OfflineGeometryRegionDefinition(style, line, MIN_ZOOM, MAX_ZOOM, ratio) }
    }

    fun destination(context: Context, place: Place) {
        // NaviHelper reports 0,0 when no destination is set.
        if (place.lat == 0.0 && place.lng == 0.0) return
        val (north, east) = Tiles.offset(place.lat, place.lng, AREA_M, AREA_M)
        val (south, west) = Tiles.offset(place.lat, place.lng, -AREA_M, -AREA_M)
        val bounds = LatLngBounds.from(north, east, south, west)
        // Rounded so the same place picked again isn't fetched twice.
        val key = "dest:%.3f,%.3f".format(place.lat, place.lng)
        fetch(context, key) { style, ratio -> OfflineTilePyramidRegionDefinition(style, bounds, MIN_ZOOM, MAX_ZOOM, ratio) }
    }

    private fun fetch(context: Context, key: String, definition: (String, Float) -> OfflineRegionDefinition) {
        Mapbox.getInstance(context)
        val manager = OfflineManager.getInstance(context)
        val style = DOWNLOAD_STYLE
        val ratio = context.resources.displayMetrics.density
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = offlineRegions.orEmpty().sortedBy { it.id }
                val same = regions.firstOrNull { keyOf(it) == key && it.definition.styleURL == style }
                if (same != null) {
                    // Cut short before: resume (finished tiles aren't fetched again). Already complete:
                    // left alone, as re-activating it re-checks every tile over the network each launch.
                    same.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status?.isComplete != true) resume(same, key)
                        }

                        override fun onError(error: String?) = resume(same, key)
                    })
                    return
                }
                regions.dropLast(KEEP - 1).forEach { it.delete(Deleted) }
                val meta = JSONObject().put(KEY, key).toString().toByteArray()
                manager.createOfflineRegion(definition(style, ratio), meta, object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) = resume(offlineRegion, key)
                    override fun onError(error: String) = Log.w(TAG, "$key: $error").let {}
                })
            }

            override fun onError(error: String) = Log.w(TAG, "list: $error").let {}
        })
    }

    private fun resume(region: OfflineRegion, key: String) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (!status.isComplete) return
                Log.i(TAG, "$key: ${status.completedResourceCount} resources, ${status.completedResourceSize / 1024} KB")
                region.setObserver(null)
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }

            override fun onError(error: OfflineRegionError) = Log.w(TAG, "$key: ${error.reason} ${error.message}").let {}
            override fun mapboxTileCountLimitExceeded(limit: Long) {
                // Stays incomplete; stop it rather than leave it active (it is resumed next launch).
                Log.w(TAG, "$key: over $limit tiles")
                region.setObserver(null)
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }
        })
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    private fun keyOf(region: OfflineRegion): String? =
        runCatching { JSONObject(String(region.metadata)).optString(KEY) }.getOrNull()

    private object Deleted : OfflineRegion.OfflineRegionDeleteCallback {
        override fun onDelete() = Unit
        override fun onError(error: String) = Log.w(TAG, "delete: $error").let {}
    }
}
