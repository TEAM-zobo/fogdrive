package com.zobo.burung

import com.zobo.burung.explore.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTest {
    /** ~1,100 cells per km² of ~30 m cells. */
    private fun xpFor(km2: Double, landings: Int, stamps: Int, kinds: Int) =
        Progress.xp((km2 * 1_100).toInt(), landings, stamps, kinds)

    @Test
    fun levelsTakeSustainedExploring() {
        assertEquals(1, Progress.level(0))
        val bigDay = Progress.level(xpFor(2.0, 3, 8, 4))
        assertTrue("first day Lv.$bigDay", bigDay in 5..7)
        val season = Progress.level(xpFor(15.0, 40, 100, 15))
        assertTrue("season Lv.$season", season in 11..15)
        val city = Progress.level(xpFor(60.0, 150, 400, 24))
        assertTrue("city Lv.$city", city in 18..22)
    }

    @Test
    fun dailyDrivingLevelsUpWithoutNewRoads() {
        // 20 km a weekday on the same roads: no new cells, only distance.
        fun commute(days: Int) = Progress.level(Progress.xp(0, 0, 0, 0, distanceMeters = days * 20_000.0))
        assertTrue("week Lv.${commute(5)}", commute(5) in 3..5)
        assertTrue("month Lv.${commute(22)}", commute(22) in 6..8)
        assertTrue("year Lv.${commute(250)}", commute(250) in 16..20)
    }

    @Test
    fun levelStopsAtMax() {
        assertEquals(Progress.MAX_LEVEL, Progress.level(Progress.xpForLevel(Progress.MAX_LEVEL)))
        assertEquals(Progress.MAX_LEVEL, Progress.level(Int.MAX_VALUE / 2))
        assertEquals(1f, Progress.levelProgress(Int.MAX_VALUE / 2), 1e-6f)
    }

    @Test
    fun thresholdsAreConsistent() {
        for (lv in 1 until Progress.MAX_LEVEL) {
            assertEquals(lv, Progress.level(Progress.xpForLevel(lv)))
            assertEquals(lv, Progress.level(Progress.xpForLevel(lv + 1) - 1))
        }
        assertEquals(0f, Progress.levelProgress(Progress.xpForLevel(5)), 1e-6f)
    }
}
