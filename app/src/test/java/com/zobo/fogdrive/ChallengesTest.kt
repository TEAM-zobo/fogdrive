package com.zobo.fogdrive

import com.zobo.fogdrive.explore.ChallengePeriod
import com.zobo.fogdrive.explore.Challenges
import com.zobo.fogdrive.explore.PeriodStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ChallengesTest {
    private fun at(year: Int, month: Int, day: Int, hour: Int = 15): Long =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, hour, 30) }.timeInMillis

    private fun ymd(t: Long): Triple<Int, Int, Int> = Calendar.getInstance().run {
        timeInMillis = t
        Triple(get(Calendar.YEAR), get(Calendar.MONTH) + 1, get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun periodStarts() {
        val sat = at(2026, 10, 24)  // a Saturday
        assertEquals(Triple(2026, 10, 24), ymd(ChallengePeriod.TODAY.start(sat)))
        assertEquals(Triple(2026, 10, 19), ymd(ChallengePeriod.WEEK.start(sat)))  // Monday
        assertEquals(Triple(2026, 10, 1), ymd(ChallengePeriod.MONTH.start(sat)))
        assertEquals(Triple(2026, 9, 1), ymd(ChallengePeriod.SEASON.start(sat)))  // 가을
        assertEquals(Triple(2026, 12, 1), ymd(ChallengePeriod.SEASON.start(at(2027, 1, 15))))  // 겨울 spans the year
        assertEquals(Triple(2026, 10, 19), ymd(ChallengePeriod.WEEK.start(at(2026, 10, 19))))  // Monday itself
    }

    @Test
    fun seasonLabels() {
        assertEquals("가을 시즌 (9~11월)", ChallengePeriod.SEASON.label(at(2026, 10, 24)))
        assertEquals("겨울 시즌 (12~2월)", ChallengePeriod.SEASON.label(at(2027, 2, 1)))
        assertEquals("봄 시즌 (3~5월)", ChallengePeriod.SEASON.label(at(2027, 3, 1)))
    }

    @Test
    fun goalsNeedARealOuting() {
        // A 10 km commute on known roads: nothing done today.
        val commute = Challenges.goals(ChallengePeriod.TODAY, PeriodStats(areaM2 = 20_000, distanceM = 10_000, landings = 1, stampKinds = 1))
        assertTrue(commute.none { it.done })
        // A proper exploring trip: area, distance and new 동 goals met.
        val outing = Challenges.goals(ChallengePeriod.TODAY, PeriodStats(areaM2 = 600_000, newDongs = 3, distanceM = 42_000, landings = 2, stampKinds = 2))
        assertTrue(outing.all { it.done })
        assertEquals("42 km", outing[1].label(outing[1].current))
        assertEquals("1,500 km", Challenges.goals(ChallengePeriod.SEASON, PeriodStats()).last().label(1_500_000))
    }
}
