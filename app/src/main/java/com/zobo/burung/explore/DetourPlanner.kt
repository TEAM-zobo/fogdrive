package com.zobo.burung.explore

import com.zobo.burung.geo.Tiles

/**
 * Finds a fogged spot just off the straight line to the destination, so a small detour
 * clears new ground. The navigation app does the real routing once it is added as a waypoint.
 */
object DetourPlanner {
    data class Suggestion(
        val lat: Double,
        val lng: Double,
        val extraMeters: Double,
        val extraMinutes: Int,
        val foggedCells: Int,
    )

    private const val ROAD_FACTOR = 1.3
    private const val AVG_SPEED_KMH = 35.0
    private const val BLOCK_RADIUS = 2
    private const val MIN_FOGGED = 20

    /** Range kept in reserve beyond the trip there and back, km. */
    private const val RANGE_RESERVE_KM = 30.0

    /**
     * Whether [rangeKm] covers going to the destination by the detour and back home by road
     * (straight lines x [ROAD_FACTOR]) with [RANGE_RESERVE_KM] to spare.
     */
    fun fitsRange(rangeKm: Double, directMeters: Double, extraMeters: Double): Boolean {
        val needKm = (2 * directMeters + extraMeters) * ROAD_FACTOR / 1000.0 + RANGE_RESERVE_KM
        return rangeKm >= needKm
    }

    fun plan(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        isExplored: (Long) -> Boolean,
        maxExtraMeters: Double = 4_000.0,
    ): Suggestion? {
        val direct = Tiles.distanceMeters(originLat, originLng, destLat, destLng)
        if (direct < 1_500) return null
        // Unit vectors (north, east) along and across the route.
        val (nLat, nLng) = Tiles.offset(originLat, originLng, 1.0, 0.0)
        val mPerDegLat = 1.0 / (nLat - originLat)
        val (eLat, eLng) = Tiles.offset(originLat, originLng, 0.0, 1.0)
        val mPerDegLng = 1.0 / (eLng - originLng)
        val north = (destLat - originLat) * mPerDegLat
        val east = (destLng - originLng) * mPerDegLng
        val len = kotlin.math.hypot(north, east)
        val crossN = -east / len
        val crossE = north / len

        var best: Suggestion? = null
        for (i in 2..8) {
            val t = i / 10.0
            for (off in listOf(-1500.0, -1000.0, -600.0, 600.0, 1000.0, 1500.0)) {
                val (cLat, cLng) = Tiles.offset(
                    originLat, originLng,
                    north * t + crossN * off,
                    east * t + crossE * off,
                )
                val block = Tiles.neighborhood(Tiles.cellOf(cLat, cLng), BLOCK_RADIUS)
                val fogged = block.count { !isExplored(it) }
                if (fogged < MIN_FOGGED) continue
                val extra = Tiles.distanceMeters(originLat, originLng, cLat, cLng) +
                    Tiles.distanceMeters(cLat, cLng, destLat, destLng) - direct
                if (extra > maxExtraMeters) continue
                if (best == null || extra < best.extraMeters) {
                    val minutes = kotlin.math.max(1, Math.round(extra * ROAD_FACTOR / 1000.0 / AVG_SPEED_KMH * 60).toInt())
                    best = Suggestion(cLat, cLng, extra, minutes, fogged)
                }
            }
        }
        return best
    }
}
