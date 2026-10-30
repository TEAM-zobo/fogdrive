package com.zobo.burung.data

import com.zobo.burung.geo.Tiles
import com.zobo.burung.record.TripRecorder

/**
 * What the map opens the veil along: every trip's driven route (in zoom-20 tile units, as a line) and
 * the cells cleared without driving there (a drop-off, an arrival, the first-run seeding, trips from
 * before routes were stored), as spots. Loaded after the cells, then extended by the recorder.
 *
 * Read by the map's render thread and the UI thread through [snapshot]: point arrays are append-only
 * (a full one is replaced by a larger copy, never changed in place), so a snapshot's arrays and counts
 * can be read without the lock while points keep being added.
 */
class Tracks {
    /** One trip's route. A [Double.NaN] point marks a gap: the next point starts a new stroke. */
    class Line internal constructor(val xs: DoubleArray, val ys: DoubleArray, val count: Int, val bbox: DoubleArray)

    class Snapshot(val lines: List<Line>, val latest: Line?, val spots: LongArray, val spotCount: Int)

    private class Building {
        var xs = DoubleArray(64)
        var ys = DoubleArray(64)
        var n = 0
        var lastLat = Double.NaN
        var lastLng = Double.NaN
        val bbox = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
    }

    private val lines = LinkedHashMap<Long, Building>()
    private var spots = LongArray(256)
    private var spotCount = 0

    /** Changes whenever a point or spot is added or everything is replaced. */
    @Volatile
    var version = 0
        private set

    @Synchronized
    fun add(tripId: Long, lat: Double, lng: Double) {
        put(tripId, lat, lng)
        version++
    }

    /** Cells cleared without a route through them (see the class comment). */
    @Synchronized
    fun addSpots(cells: Collection<Long>) {
        for (c in cells) putSpot(c)
        version++
    }

    /** Replaces everything with the stored routes and spots (start-up, restore). */
    @Synchronized
    fun replaceAll(points: List<TrackPoint>, spotCells: List<Long>) {
        lines.clear()
        // New arrays: a snapshot taken before may still be reading the old ones.
        spots = LongArray(maxOf(256, spotCells.size))
        spotCount = 0
        for (p in points) put(p.tripId, p.lat, p.lng)
        for (c in spotCells) putSpot(c)
        epoch++
        version++
    }

    @Synchronized
    fun clear() {
        lines.clear()
        spots = LongArray(256)
        spotCount = 0
        epoch++
        version++
    }

    /**
     * The routes and spots as they are now. The newest trip (highest id: trips are numbered in order)
     * is [Snapshot.latest], the one drawn as the gold trail, whether it is still being driven or just
     * ended, and the same before and after a restart.
     */
    @Synchronized
    fun snapshot(): Snapshot {
        val out = ArrayList<Line>(lines.size)
        var latestId = Long.MIN_VALUE
        var latest: Line? = null
        for ((id, b) in lines) {
            if (b.n == 0) continue
            val line = Line(b.xs, b.ys, b.n, b.bbox.copyOf())
            out.add(line)
            if (id > latestId) {
                latestId = id
                latest = line
            }
        }
        return Snapshot(out, latest, spots, spotCount)
    }

    /** Where trip [tripId]'s route starts and ends (x0, y0, x1, y1 in tiles), or null without a route. */
    @Synchronized
    fun ends(tripId: Long): DoubleArray? {
        val b = lines[tripId] ?: return null
        val first = (0 until b.n).firstOrNull { !b.xs[it].isNaN() } ?: return null
        val last = (b.n - 1 downTo 0).first { !b.xs[it].isNaN() }
        return doubleArrayOf(b.xs[first], b.ys[first], b.xs[last], b.ys[last])
    }

    /**
     * Bumped when everything changes at once ([replaceAll], [clear]): a reader that saw another epoch
     * has to redraw everything, not just what was added.
     */
    @Volatile
    var epoch = 0
        private set

    /** Where points were added since a reading: the newest trip and its point count then. */
    class Growth(val tripId: Long, val count: Int, val box: DoubleArray?)

    /**
     * The newest trip, its point count, and the area (x0, y0, x1, y1 in tiles) covered by its points
     * after [fromCount] (and the one before, which the next stroke starts from) if [tripId] is still the
     * newest; null box when nothing was added or another trip started (a full redraw is cheaper then).
     */
    @Synchronized
    fun growthSince(tripId: Long, fromCount: Int): Growth {
        val e = lines.entries.maxByOrNull { it.key } ?: return Growth(-1L, 0, null)
        val b = e.value
        if (e.key != tripId || fromCount >= b.n || fromCount <= 0) return Growth(e.key, b.n, null)
        var x0 = Double.MAX_VALUE
        var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE
        var y1 = -Double.MAX_VALUE
        for (k in fromCount - 1 until b.n) {
            val x = b.xs[k]
            if (x.isNaN()) continue
            val y = b.ys[k]
            x0 = minOf(x0, x)
            y0 = minOf(y0, y)
            x1 = maxOf(x1, x)
            y1 = maxOf(y1, y)
        }
        return Growth(e.key, b.n, if (x0 == Double.MAX_VALUE) null else doubleArrayOf(x0, y0, x1, y1))
    }

    private fun put(tripId: Long, lat: Double, lng: Double) {
        val b = lines.getOrPut(tripId) { Building() }
        // A long jump (a tunnel, a parking garage, a GPS outage) is not a road driven: break the line
        // there, as the recorder does not join such fixes either.
        if (b.n > 0 && Tiles.distanceMeters(b.lastLat, b.lastLng, lat, lng) > TripRecorder.MAX_JOIN_M) append(b, Double.NaN, Double.NaN)
        val x = Tiles.lngToX(lng)
        val y = Tiles.latToY(lat)
        append(b, x, y)
        b.lastLat = lat
        b.lastLng = lng
        b.bbox[0] = minOf(b.bbox[0], x)
        b.bbox[1] = minOf(b.bbox[1], y)
        b.bbox[2] = maxOf(b.bbox[2], x)
        b.bbox[3] = maxOf(b.bbox[3], y)
    }

    private fun append(b: Building, x: Double, y: Double) {
        if (b.n == b.xs.size) {
            b.xs = b.xs.copyOf(b.n * 2)
            b.ys = b.ys.copyOf(b.n * 2)
        }
        b.xs[b.n] = x
        b.ys[b.n] = y
        b.n++
    }

    private fun putSpot(cell: Long) {
        if (spotCount == spots.size) spots = spots.copyOf(spotCount * 2)
        spots[spotCount++] = cell
    }
}

/** One stored route point, as read for the map (no row id or time). */
data class TrackPoint(val tripId: Long, val lat: Double, val lng: Double)
