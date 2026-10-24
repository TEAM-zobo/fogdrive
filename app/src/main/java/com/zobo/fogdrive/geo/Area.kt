package com.zobo.fogdrive.geo

/** Explored area for display: grid cells are an internal unit, people read km². */
object Area {
    /** Area of [cells] grid cells around latitude [lat] (cells shrink slowly towards the poles). */
    fun ofCells(cells: Int, lat: Double = DEFAULT_LAT): Double {
        val side = Tiles.cellSizeMeters(lat)
        return cells * side * side
    }

    /**
     * Always km², with more decimals for small areas so a single drop-off (~0.04 km²) or a short
     * drive still reads as progress: "12.4 km²", "2.31 km²", "0.045 km²", "0.0026 km²", "0 km²".
     */
    fun format(m2: Double): String {
        val km2 = m2 / 1_000_000
        val digits = when {
            km2 <= 0.0 -> return "0 km²"
            km2 >= 10 -> "%.1f"
            km2 >= 1 -> "%.2f"
            km2 >= 0.01 -> "%.3f"
            else -> "%.4f"
        }
        // Trailing zeros dropped, so goals read "0.5 km²" rather than "0.500 km²".
        return digits.format(km2).trimEnd('0').trimEnd('.') + " km²"
    }

    fun formatInt(m2: Int): String = format(m2.toDouble())

    fun formatCells(cells: Int, lat: Double = DEFAULT_LAT): String = format(ofCells(cells, lat))

    private const val DEFAULT_LAT = 37.5
}
