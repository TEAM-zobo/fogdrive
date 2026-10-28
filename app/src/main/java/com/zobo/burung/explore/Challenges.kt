package com.zobo.burung.explore

import com.zobo.burung.geo.Area
import java.util.Calendar

/** Challenge windows, from a day to a three-month season. */
enum class ChallengePeriod(val title: String) {
    TODAY("오늘의 챌린지"),
    WEEK("이번 주 챌린지"),
    MONTH("이번 달 챌린지"),
    SEASON("시즌 챌린지");

    /** Start of the window holding [now] (local time): midnight, Monday, the 1st, or the season's 1st. */
    fun start(now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        when (this) {
            TODAY -> Unit
            WEEK -> {
                val back = (c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
                c.add(Calendar.DAY_OF_MONTH, -back)
            }
            MONTH -> c.set(Calendar.DAY_OF_MONTH, 1)
            SEASON -> {
                // 봄 3-5월, 여름 6-8월, 가을 9-11월, 겨울 12-2월.
                val month = c.get(Calendar.MONTH)
                val first = month - (month + 1) % 3
                c.set(Calendar.DAY_OF_MONTH, 1)
                c.add(Calendar.MONTH, first - month)
            }
        }
        return c.timeInMillis
    }

    /** "10월 24일", "10월 20일 주", "10월", "가을 시즌 (9~11월)". */
    fun label(now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = start(now) }
        val month = c.get(Calendar.MONTH) + 1
        return when (this) {
            TODAY -> "${month}월 ${c.get(Calendar.DAY_OF_MONTH)}일"
            WEEK -> "${month}월 ${c.get(Calendar.DAY_OF_MONTH)}일 주"
            MONTH -> "${month}월"
            SEASON -> when (month) {
                3 -> "봄 시즌 (3~5월)"
                6 -> "여름 시즌 (6~8월)"
                9 -> "가을 시즌 (9~11월)"
                else -> "겨울 시즌 (12~2월)"
            }
        }
    }
}

/** What the challenges are measured on, counted from the start of one period. */
data class PeriodStats(
    val areaM2: Int = 0,
    val newDongs: Int = 0,
    val newDistricts: Int = 0,
    val newProvinces: Int = 0,
    val trips: Int = 0,
    val distanceM: Int = 0,
    val landings: Int = 0,
    val stampKinds: Int = 0,
)

data class ChallengeGroup(val period: ChallengePeriod, val label: String, val goals: List<ChallengeGoal>) {
    val done: Int get() = goals.count { it.done }
}

/**
 * Goals per period. Each takes a deliberate effort within its window, not something an ordinary
 * commute already does: about 1 km² of fog clears per 10 km of new road, and a 동 is ~1-3 km across.
 * Today asks for a proper outing, a week for several new neighbourhoods, a month for a new 시·군·구
 * or three, a season for leaving home turf.
 */
object Challenges {
    private fun km(m: Int) = if (m < 10_000) "%.1f km".format(m / 1000.0) else "%,d km".format(m / 1000)

    fun goals(period: ChallengePeriod, s: PeriodStats): List<ChallengeGoal> = when (period) {
        ChallengePeriod.TODAY -> listOf(
            ChallengeGoal("새 길 0.5 km² 밝히기", s.areaM2, 500_000, Area::formatInt),
            ChallengeGoal("40 km 달리기", s.distanceM, 40_000, ::km),
            ChallengeGoal("새 동 3곳 개척", s.newDongs, 3),
            ChallengeGoal("다른 곳 두 군데에서 하차", s.landings, 2),
            ChallengeGoal("스탬프 2종 모으기", s.stampKinds, 2),
        )
        ChallengePeriod.WEEK -> listOf(
            ChallengeGoal("2 km² 밝히기", s.areaM2, 2_000_000, Area::formatInt),
            ChallengeGoal("새 동 10곳 개척", s.newDongs, 10),
            ChallengeGoal("새 시·군·구 1곳", s.newDistricts, 1),
            ChallengeGoal("7번 주행하기", s.trips, 7),
            ChallengeGoal("150 km 달리기", s.distanceM, 150_000, ::km),
            ChallengeGoal("스탬프 4종 모으기", s.stampKinds, 4),
        )
        ChallengePeriod.MONTH -> listOf(
            ChallengeGoal("6 km² 밝히기", s.areaM2, 6_000_000, Area::formatInt),
            ChallengeGoal("새 동 30곳 개척", s.newDongs, 30),
            ChallengeGoal("새 시·군·구 3곳", s.newDistricts, 3),
            ChallengeGoal("하차 지점 15곳", s.landings, 15),
            ChallengeGoal("스탬프 8종 모으기", s.stampKinds, 8),
            ChallengeGoal("500 km 달리기", s.distanceM, 500_000, ::km),
        )
        ChallengePeriod.SEASON -> listOf(
            ChallengeGoal("15 km² 밝히기", s.areaM2, 15_000_000, Area::formatInt),
            ChallengeGoal("새 동 80곳 개척", s.newDongs, 80),
            ChallengeGoal("새 시·군·구 8곳", s.newDistricts, 8),
            ChallengeGoal("새 시·도 1곳", s.newProvinces, 1),
            ChallengeGoal("스탬프 15종 모으기", s.stampKinds, 15),
            ChallengeGoal("하차 지점 40곳", s.landings, 40),
            ChallengeGoal("1,500 km 달리기", s.distanceM, 1_500_000, ::km),
        )
    }
}
