package com.zobo.burung

import com.zobo.burung.geo.Tiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TilesTest {
    @Test
    fun cellRoundTripStaysInsideCell() {
        val cell = Tiles.cellOf(37.5446, 127.0559)
        assertEquals(cell, Tiles.cellOf(Tiles.centerLat(cell), Tiles.centerLng(cell)))
    }

    @Test
    fun cellIsAboutThirtyMetresInSeoul() {
        val size = Tiles.cellSizeMeters(37.5)
        assertTrue("size=$size", size in 27.0..33.0)
    }

    @Test
    fun cellsAlongHasNoGaps() {
        val cells = Tiles.cellsAlong(37.5446, 127.0559, 37.5440, 127.0450).toList()
        for (i in 1 until cells.size) {
            val dx = kotlin.math.abs(Tiles.x(cells[i]) - Tiles.x(cells[i - 1]))
            val dy = kotlin.math.abs(Tiles.y(cells[i]) - Tiles.y(cells[i - 1]))
            assertTrue(dx <= 1 && dy <= 1)
        }
    }

    @Test
    fun parentShiftsCoordinates() {
        val cell = Tiles.pack(1000, 2000)
        assertEquals(Tiles.pack(31, 62), Tiles.parent(cell, 5))
    }
}
