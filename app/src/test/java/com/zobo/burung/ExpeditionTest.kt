package com.zobo.burung

import com.zobo.burung.explore.StampBook
import com.zobo.burung.explore.StampField
import com.zobo.burung.explore.Titles
import com.zobo.burung.explore.WeeklyPicks
import com.zobo.burung.record.StampCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpeditionTest {
    @Test
    fun riskyStampsAreRetiredAndEveryOtherHasAShelf() {
        assertTrue(StampCode.RAIN in StampBook.RETIRED && StampCode.SPORT in StampBook.RETIRED)
        assertTrue(StampBook.ACTIVE.none { it in StampBook.RETIRED })
        // Every title can still be earned with the stamps left.
        for (t in Titles.ALL) assertTrue(t.id, t.earned(StampBook.ACTIVE.toSet()))
        assertEquals(StampField.REGION, StampBook.field(StampCode.NEW_PROVINCE))
    }

    @Test
    fun retiredStampsCountForNoTitle() {
        val old = setOf(StampCode.RAIN, StampCode.NIGHT, StampCode.MIDNIGHT)
        assertNull(Titles.shown("", old))
    }

    @Test
    fun shownTitleIsThePickWhenEarnedElseTheHardest() {
        val got = setOf(StampCode.FIRST_DRIVE, StampCode.NEW_DISTRICT, StampCode.PIONEER)
        assertEquals("dong", Titles.shown("", got)?.id)
        assertEquals("rookie", Titles.shown("rookie", got)?.id)
        // A pick that isn't earned (or no longer exists) falls back.
        assertEquals("dong", Titles.shown("national", got)?.id)
        assertNull(Titles.shown("", emptySet()))
    }

    @Test
    fun nextTitleIsTheClosest() {
        val got = setOf(StampCode.NEW_DISTRICT, StampCode.PIONEER, StampCode.DONG_HOPPER, StampCode.FAMILY)
        // 골목 개척자 needs 4 region stamps (3 held), 가족 탐험대장 2 together (1 held): a tie, list order wins.
        assertEquals("alley", Titles.next(got)?.id)
    }

    @Test
    fun weeklyPicksLeadWithWhatFitsAndStayForTheWeek() {
        val got = setOf(StampCode.WEEKEND)
        val a = WeeklyPicks.pick(got, 202645, listOf(StampCode.NEW_PROVINCE, StampCode.WEEKEND))
        assertEquals(3, a.size)
        assertEquals(StampCode.NEW_PROVINCE, a.first())
        assertFalse(StampCode.WEEKEND in a)
        assertTrue(a.none { it in StampBook.RETIRED })
        assertEquals(a, WeeklyPicks.pick(got, 202645, listOf(StampCode.NEW_PROVINCE)))
        assertTrue(WeeklyPicks.pick(StampBook.ACTIVE.toSet(), 202645, emptyList()).isEmpty())
    }
}
