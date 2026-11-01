package com.zobo.burung

import com.zobo.burung.explore.Milestones
import com.zobo.burung.explore.StampBook
import com.zobo.burung.explore.StampField
import com.zobo.burung.explore.StampProgress
import com.zobo.burung.explore.Titles
import com.zobo.burung.explore.WeeklyPicks
import com.zobo.burung.record.StampCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ExpeditionTest {
    private val seoul = TimeZone.getTimeZone("Asia/Seoul")

    private fun at(y: Int, m: Int, d: Int, h: Int = 9): Long = Calendar.getInstance(seoul).run {
        clear()
        set(y, m - 1, d, h, 0)
        timeInMillis
    }

    @Test
    fun elevenStampsNoneRiskyNoneBySeat() {
        assertEquals(11, StampBook.ACTIVE.size)
        assertEquals(listOf(4, 4, 3), StampField.entries.map { f -> StampBook.ACTIVE.count { StampBook.field(it) == f } })
        assertFalse(StampCode.FAMILY in StampBook.ACTIVE)
        assertTrue(StampBook.ACTIVE.none { it in StampBook.RETIRED })
        assertNull(StampBook.field(StampCode.RAIN))
    }

    @Test
    fun aFullFieldEarnsItsTitle() {
        val daily = StampBook.ACTIVE.filter { StampBook.field(it) == StampField.DAILY }.toSet()
        assertEquals("daily", Titles.shown("", daily)?.id)
        assertNull(Titles.shown("", daily - StampCode.KM_1000))
        // Stamps outside the book count for nothing.
        assertNull(Titles.shown("", setOf(StampCode.RAIN, StampCode.ARRIVAL, StampCode.ECO)))
        assertEquals("captain", Titles.shown("", StampBook.ACTIVE.toSet())?.id)
        assertEquals("daily", Titles.shown("daily", StampBook.ACTIVE.toSet())?.id)
    }

    @Test
    fun milestonesFromProgress() {
        val p = StampProgress(daysThisWeek = 3, streakWeeks = 4, km = 1_000, dongs = 100, provinces = 3)
        assertEquals(
            setOf(StampCode.WEEK_THREE, StampCode.STREAK_4W, StampCode.KM_1000, StampCode.DONG_10, StampCode.DONG_100, StampCode.PROVINCE_3),
            Milestones.reached(p).toSet(),
        )
        assertTrue(Milestones.reached(StampProgress(daysThisWeek = 2, streakWeeks = 3, km = 999, dongs = 9, provinces = 2)).isEmpty())
        assertEquals("820 / 1,000 km", StampBook.progressLabel(StampCode.KM_1000, StampProgress(km = 820)))
    }

    @Test
    fun weeksAreMondayBasedAndAStreakSurvivesAnUndrivenCurrentWeek() {
        // 2026-11-02 is a Monday; Sunday the 8th is the same week.
        assertEquals(Milestones.week(at(2026, 11, 2), seoul), Milestones.week(at(2026, 11, 8, 23), seoul))
        assertEquals(Milestones.week(at(2026, 11, 2), seoul) - 1, Milestones.week(at(2026, 11, 1, 23), seoul))
        val trips = listOf(at(2026, 10, 12), at(2026, 10, 19), at(2026, 10, 26), at(2026, 11, 2), at(2026, 11, 3), at(2026, 11, 3, 18), at(2026, 11, 5))
        assertEquals(3 to 4, Milestones.weeks(trips, at(2026, 11, 6), seoul))
        // The next Monday, nothing driven yet: still 4 weeks in a row (counted from last week).
        assertEquals(0 to 4, Milestones.weeks(trips, at(2026, 11, 9), seoul))
    }

    @Test
    fun weeklyPicksPutTheTitleClosingStampFirst() {
        val got = setOf(StampCode.FIRST_DRIVE, StampCode.WEEK_THREE, StampCode.STREAK_4W)
        val p = StampProgress(km = 750, dongs = 4, provinces = 1)
        val picks = WeeklyPicks.pick(got, p, 1000L, placeNear = true, weekendPaused = false)
        assertEquals(3, picks.size)
        assertEquals(StampCode.KM_1000, picks.first())  // the last 꾸준함 stamp, 250 km to go
        assertFalse(StampCode.FIRST_DRIVE in picks)
        assertFalse(StampCode.DONG_100 in picks)  // 96 동 to go
        assertFalse(StampCode.DONG_10 in picks)  // 6 동 to go: more than a week
        assertFalse(StampCode.PROVINCE_3 in picks)  // one 시·도 so far
        assertEquals(picks, WeeklyPicks.pick(got, p, 1000L, placeNear = true, weekendPaused = false))
    }

    @Test
    fun weeklyPicksOnlySuggestWhatAWeekCanEarn() {
        val got = setOf(StampCode.FIRST_DRIVE)
        val far = StampProgress(streakWeeks = 1, km = 100, dongs = 30, provinces = 2)
        val picks = WeeklyPicks.pick(got, far, 7L, placeNear = false, weekendPaused = false, provinceNear = false)
        for (c in listOf(StampCode.STREAK_4W, StampCode.KM_1000, StampCode.DONG_100, StampCode.NEW_DISTRICT, StampCode.PROVINCE_3)) {
            assertFalse(c in picks)
        }
        val close = StampProgress(streakWeeks = 3, km = 800, dongs = 96, provinces = 2)
        val all = (0L until 20L).flatMap { WeeklyPicks.pick(got + StampCode.DONG_10, close, it, placeNear = true, weekendPaused = false) }.toSet()
        assertTrue(StampCode.STREAK_4W in all && StampCode.KM_1000 in all && StampCode.DONG_100 in all)
    }

    @Test
    fun weeklyPicksSkipARestedWeekendAndStopWhenAllAreIn() {
        val p = StampProgress()
        assertFalse(StampCode.WEEKEND in WeeklyPicks.pick(setOf(StampCode.FIRST_DRIVE), p, 5L, placeNear = false, weekendPaused = true))
        assertTrue(WeeklyPicks.pick(StampBook.ACTIVE.toSet(), p, 5L, placeNear = true, weekendPaused = false).isEmpty())
    }
}
