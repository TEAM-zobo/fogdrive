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
    fun thresholdsAreConsistent() {
        for (lv in 1..40) {
            assertEquals(lv, Progress.level(Progress.xpForLevel(lv)))
            assertEquals(lv, Progress.level(Progress.xpForLevel(lv + 1) - 1))
        }
        assertEquals(0f, Progress.levelProgress(Progress.xpForLevel(5)), 1e-6f)
    }
}
