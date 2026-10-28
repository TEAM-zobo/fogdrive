package com.zobo.burung

import com.zobo.burung.data.ClearedCell
import com.zobo.burung.geo.Tiles
import com.zobo.burung.ui.Timelapse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Timelapse.of]: cleared cells in time order, one per drawn cell, and a camera that ends on all of them. */
class TimelapseTest {
    private val x0 = 894_000
    private val y0 = 406_000

    private fun cell(dx: Int, dy: Int, at: Long) = ClearedCell(Tiles.pack(x0 + dx, y0 + dy), at)

    @Test
    fun emptyHistoryHasNoTimelapse() = assertNull(Timelapse.of(emptyList()))

    @Test
    fun cellsPlayInTheOrderTheyWereCleared() {
        val lapse = Timelapse.of(listOf(cell(2, 0, 300), cell(0, 0, 100), cell(1, 0, 200)))!!
        assertArrayEquals(longArrayOf(100, 200, 300), lapse.times)
        assertArrayEquals(floatArrayOf(0f, 1f, 2f), lapse.xs, 0f)
        assertEquals(1f, lapse.cell)
    }

    @Test
    fun aLongHistoryIsDrawnCoarserAtTheFirstTimeEachCellWasReached() {
        // A 2000-cell line: coarser than the 480-cell grid, so neighbours merge into one drawn cell.
        val line = (0 until 2_000).map { cell(it, 0, 10L * (2_000 - it)) }
        val lapse = Timelapse.of(line)!!
        assertTrue(lapse.cell > 1f)
        assertTrue(lapse.size < line.size)
        // Each drawn cell keeps its earliest time, and the play stays in time order.
        assertTrue(lapse.times.asList().zipWithNext().all { (a, b) -> a <= b })
        assertEquals(10L, lapse.times.first())
    }

    @Test
    fun theCameraEndsFramingTheWholeFootprint() {
        val square = (0 until 200).flatMap { x -> (0 until 200 step 20).map { y -> cell(x, y, (x * 1_000 + y).toLong()) } }
        val lapse = Timelapse.of(square)!!
        val (cx, cy, side) = lapse.camera(Timelapse.END)
        for (i in 0 until lapse.size) {
            assertTrue(lapse.xs[i] in cx - side / 2..cx + side / 2)
            assertTrue(lapse.ys[i] in cy - side / 2..cy + side / 2)
        }
    }
}
