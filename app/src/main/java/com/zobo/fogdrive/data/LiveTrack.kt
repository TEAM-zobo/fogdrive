package com.zobo.fogdrive.data

import com.zobo.fogdrive.geo.Tiles

/**
 * The newest trip's route so far, in zoom-20 tile units, for the trail drawn on the map. Written by
 * the recorder, read by the map on the UI thread; a new trip starts it over.
 */
class LiveTrack {
    private var trip = -1L
    private var xs = DoubleArray(512)
    private var ys = DoubleArray(512)
    private var n = 0

    @Synchronized
    fun add(tripId: Long, lat: Double, lng: Double) {
        if (tripId != trip) {
            trip = tripId
            n = 0
        }
        if (n == xs.size) {
            xs = xs.copyOf(n * 2)
            ys = ys.copyOf(n * 2)
        }
        xs[n] = Tiles.lngToX(lng)
        ys[n] = Tiles.latToY(lat)
        n++
    }

    /** Runs [block] on the points (x, y arrays and their count) while no point is being added. */
    @Synchronized
    fun <T> read(block: (xs: DoubleArray, ys: DoubleArray, count: Int) -> T): T = block(xs, ys, n)
}
