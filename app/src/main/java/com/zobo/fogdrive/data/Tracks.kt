package com.zobo.fogdrive.data

import com.zobo.fogdrive.geo.Tiles

/**
 * Every trip's driven route in zoom-20 tile units: the map opens the ground along it and draws it as
 * the gold trail. Loaded with the cells, then extended by the recorder; read by the map threads.
 */
class Tracks {
    private class Line {
        var xs = DoubleArray(64)
        var ys = DoubleArray(64)
        var n = 0
    }

    private val lines = LinkedHashMap<Long, Line>()
    /** The trip a point was last added to (the one being driven, or the last one driven). */
    private var latest = -1L

    /** Changes whenever a point is added or the routes are replaced. */
    @Volatile
    var version = 0
        private set

    @Synchronized
    fun add(tripId: Long, lat: Double, lng: Double) {
        put(tripId, lat, lng)
        latest = tripId
        version++
    }

    /** Replaces every route with the stored ones (start-up, restore). */
    @Synchronized
    fun replaceAll(points: List<TrackPointEntity>) {
        lines.clear()
        latest = -1L
        for (p in points.sortedWith(compareBy({ it.tripId }, { it.at }, { it.id }))) put(p.tripId, p.lat, p.lng)
        version++
    }

    @Synchronized
    fun clear() {
        lines.clear()
        latest = -1L
        version++
    }

    /** Runs [block] on the newest trip's route only, if it has points. */
    @Synchronized
    fun forLatest(block: (xs: DoubleArray, ys: DoubleArray, count: Int) -> Unit) {
        lines[latest]?.takeIf { it.n > 0 }?.let { block(it.xs, it.ys, it.n) }
    }

    /** Runs [block] on each route (x, y arrays and their count) while no point is being added. */
    @Synchronized
    fun forEach(block: (xs: DoubleArray, ys: DoubleArray, count: Int) -> Unit) {
        for (l in lines.values) if (l.n > 0) block(l.xs, l.ys, l.n)
    }

    private fun put(tripId: Long, lat: Double, lng: Double) {
        val l = lines.getOrPut(tripId) { Line() }
        if (l.n == l.xs.size) {
            l.xs = l.xs.copyOf(l.n * 2)
            l.ys = l.ys.copyOf(l.n * 2)
        }
        l.xs[l.n] = Tiles.lngToX(lng)
        l.ys[l.n] = Tiles.latToY(lat)
        l.n++
    }
}
