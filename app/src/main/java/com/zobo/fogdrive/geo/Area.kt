package com.zobo.fogdrive.geo

import kotlin.math.roundToLong

/** Explored area for display: grid cells are an internal unit, people read m² / km². */
object Area {
    /** Area of [cells] grid cells around latitude [lat] (cells shrink slowly towards the poles). */
    fun ofCells(cells: Int, lat: Double = DEFAULT_LAT): Double {
        val side = Tiles.cellSizeMeters(lat)
        return cells * side * side
    }

    /** "8,400 m²" below 0.1 km², "0.63 km²" from there on. */
    fun format(m2: Double): String = when {
        m2 < 100_000 -> "%,d m²".format((m2 / 10).roundToLong() * 10)
        else -> "%.2f km²".format(m2 / 1_000_000)
    }

    fun formatInt(m2: Int): String = format(m2.toDouble())

    fun formatCells(cells: Int, lat: Double = DEFAULT_LAT): String = format(ofCells(cells, lat))

    private const val DEFAULT_LAT = 37.5
}
