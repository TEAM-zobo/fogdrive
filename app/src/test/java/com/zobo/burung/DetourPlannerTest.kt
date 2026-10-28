package com.zobo.burung

import com.zobo.burung.explore.DetourPlanner
import com.zobo.burung.geo.Tiles
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetourPlannerTest {
    @Test
    fun shortTripsGetNoSuggestion() {
        assertNull(DetourPlanner.plan(37.5, 127.0, 37.5, 127.005, { false }))
    }

    @Test
    fun suggestsFoggedSpotWithBoundedDetour() {
        val s = DetourPlanner.plan(37.50, 127.00, 37.50, 127.06, { false })
        assertNotNull(s)
        assertTrue(s!!.extraMeters in 0.0..4_000.0)
        assertTrue(s.extraMinutes >= 1)
        // Off the straight line by at least 600 m.
        assertTrue(Tiles.distanceMeters(37.50, s.lng, s.lat, s.lng) >= 550)
    }

    @Test
    fun fullyExploredAreaGetsNoSuggestion() {
        assertNull(DetourPlanner.plan(37.50, 127.00, 37.50, 127.06, { true }))
    }
}
