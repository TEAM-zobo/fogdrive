package com.zobo.fogdrive

import com.zobo.fogdrive.demo.DemoCourses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [com.zobo.fogdrive.demo.DemoCourse.speedLimits] / speedLimitAt over every demo course and its return trip. */
class DemoSpeedLimitTest {
    /** Mirrors the private limitsAround in DemoCourses.kt: (low, mid, high) per base limit. */
    private fun limitsAround(base: Int): Triple<Int, Int, Int> = when {
        base <= 30 -> Triple(30, 30, 50)
        base <= 40 -> Triple(30, 40, 50)
        base <= 50 -> Triple(30, 50, 60)
        base <= 60 -> Triple(50, 60, 80)
        base <= 80 -> Triple(60, 80, 100)
        else -> Triple(80, 100, 110)
    }

    private val courses = DemoCourses.all.flatMap { listOf(it, it.returnTrip()) }

    @Test
    fun stepsAreOrderedAndStartAtZero() {
        for (c in courses) {
            val steps = c.speedLimits
            assertTrue(c.id, steps.isNotEmpty())
            assertEquals(c.id, 0.0, steps.first().first, 0.0)
            for (k in 1 until steps.size) {
                assertTrue("${c.id} step $k", steps[k].first >= steps[k - 1].first)
                assertNotEquals("${c.id} step $k repeats its limit", steps[k - 1].second, steps[k].second)
            }
            assertTrue(c.id, steps.last().first <= c.lengthMeters)
        }
    }

    @Test
    fun limitsComeFromTheCourseSet() {
        for (c in courses) {
            val (low, mid, high) = limitsAround(c.speedLimit)
            val allowed = setOf(low, mid, high)
            for ((at, kmh) in c.speedLimits) assertTrue("${c.id} at $at: $kmh not in $allowed", kmh in allowed)
        }
    }

    @Test
    fun noStepShorterThan300m() {
        for (c in courses) {
            val steps = c.speedLimits
            for (k in 1 until steps.size) {
                val end = steps.getOrNull(k + 1)?.first ?: c.lengthMeters
                assertTrue("${c.id} step $k ${steps[k]} lasts ${end - steps[k].first} m", end - steps[k].first >= 300.0)
            }
        }
    }

    @Test
    fun bothEndsUseTheLowLimit() {
        for (c in courses) {
            val low = limitsAround(c.speedLimit).first
            assertEquals("${c.id} start", low, c.speedLimitAt(0.0))
            assertEquals("${c.id} end", low, c.speedLimitAt(c.lengthMeters))
        }
    }

    @Test
    fun speedLimitAtFollowsSteps() {
        for (c in courses) {
            val steps = c.speedLimits
            for ((k, step) in steps.withIndex()) {
                assertEquals(c.id, step.second, c.speedLimitAt(step.first))
                val end = steps.getOrNull(k + 1)?.first ?: c.lengthMeters
                assertEquals(c.id, step.second, c.speedLimitAt((step.first + end) / 2))
            }
        }
    }
}
