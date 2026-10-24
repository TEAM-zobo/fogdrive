package com.zobo.fogdrive.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sinh
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Exploration grid = Web Mercator tiles at zoom 19 (about 60 m per side in Korea).
 * A cell id packs the tile x/y into one Long. Coarser levels are the same tiles shifted by `dz`.
 */
object Tiles {
    const val Z = 19
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
    fun x(cell: Long): Int = (cell shr 32).toInt()
    fun y(cell: Long): Int = cell.toInt()

    fun lngToX(lng: Double, z: Int = Z): Double = (lng + 180.0) / 360.0 * (1 shl z)

    fun latToY(lat: Double, z: Int = Z): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)
    }

    fun xToLng(x: Double, z: Int = Z): Double = x / (1 shl z) * 360.0 - 180.0

    fun yToLat(y: Double, z: Int = Z): Double {
        val n = PI - 2.0 * PI * y / (1 shl z)
        return Math.toDegrees(atan(sinh(n)))
    }

    fun cellOf(lat: Double, lng: Double): Long =
        pack(floor(lngToX(lng)).toInt(), floor(latToY(lat)).toInt())

    fun centerLat(cell: Long): Double = yToLat(y(cell) + 0.5)
    fun centerLng(cell: Long): Double = xToLng(x(cell) + 0.5)

    /** Parent tile `dz` levels up. */
    fun parent(cell: Long, dz: Int): Long = if (dz == 0) cell else pack(x(cell) shr dz, y(cell) shr dz)

    /** Square block of cells around [cell] with Chebyshev radius [r]. */
    fun neighborhood(cell: Long, r: Int): List<Long> {
        val cx = x(cell)
        val cy = y(cell)
        val out = ArrayList<Long>((2 * r + 1) * (2 * r + 1))
        for (dx in -r..r) for (dy in -r..r) out.add(pack(cx + dx, cy + dy))
        return out
    }

    /** Cells crossed by the straight segment between two fixes, sampled every ~half cell. */
    fun cellsAlong(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Set<Long> {
        val x1 = lngToX(lng1)
        val y1 = latToY(lat1)
        val x2 = lngToX(lng2)
        val y2 = latToY(lat2)
        val steps = max(1, ceil(max(kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1)) * 2).toInt())
        val out = LinkedHashSet<Long>()
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            out.add(pack(floor(x1 + (x2 - x1) * t).toInt(), floor(y1 + (y2 - y1) * t).toInt()))
        }
        return out
    }

    /** Approximate side length of one cell in metres at [lat]. */
    fun cellSizeMeters(lat: Double): Double =
        2 * PI * EARTH_RADIUS_M * cos(Math.toRadians(lat)) / (1 shl Z)

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Moves a point by north/east metres (local flat approximation, fine for a few km). */
    fun offset(lat: Double, lng: Double, northM: Double, eastM: Double): Pair<Double, Double> {
        val dLat = Math.toDegrees(northM / EARTH_RADIUS_M)
        val dLng = Math.toDegrees(eastM / (EARTH_RADIUS_M * cos(Math.toRadians(lat))))
        return (lat + dLat) to (lng + dLng)
    }
}
