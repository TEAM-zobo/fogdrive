package com.zobo.burung.explore

import com.zobo.burung.record.StampCode
import java.util.TimeZone
import kotlin.random.Random

/** The three shelves of the stamp book; filling one (all four stamps) earns its title. */
enum class StampField(val label: String, val kidsLabel: String) {
    DAILY("꾸준함", "매일 탐험"),
    REGION("넓힘", "새 동네 찾기"),
    TRIP("함께·여행", "함께 나들이"),
}

/** Where a driver stands on the progress-type stamps, read from the records. */
data class StampProgress(
    /** Days with a drive in the current (Monday-based) week. */
    val daysThisWeek: Int = 0,
    /** Weeks in a row with at least one drive, ending this week or last week. */
    val streakWeeks: Int = 0,
    val km: Int = 0,
    /** 읍·면·동 / 시·도 entered by driving or getting off (not the cells seeded from the navigation). */
    val dongs: Int = 0,
    val provinces: Int = 0,
)

/** Which stamps are collected, on which shelf, how a missing one is earned and how far along it is. */
object StampBook {
    /** The twelve stamps shown and awarded, four per field, in the order shown. */
    val ACTIVE: List<StampCode> = listOf(
        StampCode.FIRST_DRIVE, StampCode.WEEK_THREE, StampCode.STREAK_4W, StampCode.KM_1000,
        StampCode.DONG_10, StampCode.NEW_DISTRICT, StampCode.PROVINCE_3, StampCode.DONG_100,
        StampCode.WEEKEND, StampCode.FAR_DAY, StampCode.FAMILY, StampCode.CREW_MISSION,
    )

    /**
     * Never awarded again: they reward driving in the rain, at night, on a nearly empty battery, in
     * sport mode or 100 km without a break. (Every code outside [ACTIVE] is neither awarded nor shown;
     * these are the ones left out on purpose, for safety.)
     */
    val RETIRED: Set<StampCode> = setOf(
        StampCode.RAIN, StampCode.NIGHT, StampCode.RAINY_NIGHT, StampCode.MIDNIGHT,
        StampCode.LOW_BATTERY, StampCode.SPORT, StampCode.MARATHON,
    )

    fun field(code: StampCode): StampField? = when (ACTIVE.indexOf(code)) {
        in 0..3 -> StampField.DAILY
        in 4..7 -> StampField.REGION
        in 8..11 -> StampField.TRIP
        else -> null
    }

    /** (now, target) for a stamp earned by reaching a number, else null. */
    fun progress(code: StampCode, p: StampProgress): Pair<Int, Int>? = when (code) {
        StampCode.WEEK_THREE -> p.daysThisWeek to 3
        StampCode.STREAK_4W -> p.streakWeeks to 4
        StampCode.KM_1000 -> p.km to 1_000
        StampCode.DONG_10 -> p.dongs to 10
        StampCode.DONG_100 -> p.dongs to 100
        StampCode.PROVINCE_3 -> p.provinces to 3
        else -> null
    }?.let { (n, t) -> n.coerceAtMost(t) to t }

    /** "820 / 1,000 km" under a progress stamp. */
    fun progressLabel(code: StampCode, p: StampProgress): String? = progress(code, p)?.let { (n, t) ->
        val unit = when (code) {
            StampCode.WEEK_THREE -> "일"
            StampCode.STREAK_4W -> "주"
            StampCode.KM_1000 -> " km"
            StampCode.PROVINCE_3 -> "곳"
            else -> "곳"
        }
        "%,d / %,d%s".format(n, t, unit)
    }

    /** How to earn [code], short enough for one line under its coin. */
    fun hint(code: StampCode, kids: Boolean): String = if (!kids) when (code) {
        StampCode.FIRST_DRIVE -> "첫 주행을 하면 받아요"
        StampCode.WEEK_THREE -> "한 주에 3일 주행"
        StampCode.STREAK_4W -> "4주 동안 매주 주행"
        StampCode.KM_1000 -> "누적 1,000 km"
        StampCode.DONG_10 -> "읍·면·동 10곳 탐험"
        StampCode.NEW_DISTRICT -> "처음 가는 시·군·구"
        StampCode.PROVINCE_3 -> "시·도 3곳 탐험"
        StampCode.DONG_100 -> "읍·면·동 100곳 탐험"
        StampCode.WEEKEND -> "주말에 새 길 주행"
        StampCode.FAR_DAY -> "집에서 50 km 넘게 떨어진 곳 도착"
        StampCode.FAMILY -> "뒷좌석 동승자와 새 길 주행"
        StampCode.CREW_MISSION -> "동승자 미션 성공"
        else -> code.description
    } else when (code) {
        StampCode.FIRST_DRIVE -> "처음 같이 달리면 받아요"
        StampCode.WEEK_THREE -> "이번 주에 3일 달려요"
        StampCode.STREAK_4W -> "4주 동안 매주 달려요"
        StampCode.KM_1000 -> "1,000 km까지 달려요"
        StampCode.DONG_10 -> "동네 10곳을 찾아요"
        StampCode.NEW_DISTRICT -> "한 번도 안 가 본 도시에 가 봐요"
        StampCode.PROVINCE_3 -> "세 지역을 여행해요"
        StampCode.DONG_100 -> "동네 100곳을 찾아요"
        StampCode.WEEKEND -> "주말에 새 길로 나들이 가요"
        StampCode.FAR_DAY -> "멀리 여행을 떠나요"
        StampCode.FAMILY -> "뒷자리에 가족을 태우고 새 길을 가요"
        StampCode.CREW_MISSION -> "함께 탄 사람이랑 미션을 깨요"
        else -> code.description
    }
}

/** The milestone stamps a driver has reached, given the records (awarded when a trip ends). */
object Milestones {
    const val FAR_M = 50_000.0

    fun reached(p: StampProgress): List<StampCode> = buildList {
        if (p.daysThisWeek >= 3) add(StampCode.WEEK_THREE)
        if (p.streakWeeks >= 4) add(StampCode.STREAK_4W)
        if (p.km >= 1_000) add(StampCode.KM_1000)
        if (p.dongs >= 10) add(StampCode.DONG_10)
        if (p.dongs >= 100) add(StampCode.DONG_100)
        if (p.provinces >= 3) add(StampCode.PROVINCE_3)
    }

    /** Monday-based week number of [at] in local time (consecutive weeks differ by one). */
    fun week(at: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val day = Math.floorDiv(at + zone.getOffset(at), 86_400_000L)
        // 1970-01-01 was a Thursday: shift so weeks start on Monday.
        return Math.floorDiv(day + 3, 7L)
    }

    private fun day(at: Long, zone: TimeZone) = Math.floorDiv(at + zone.getOffset(at), 86_400_000L)

    /** Days driven this week and the run of weeks with a drive, from the trips' start times. */
    fun weeks(tripStarts: List<Long>, now: Long, zone: TimeZone = TimeZone.getDefault()): Pair<Int, Int> {
        val thisWeek = week(now, zone)
        val days = tripStarts.filter { week(it, zone) == thisWeek }.map { day(it, zone) }.toSet().size
        val weeks = tripStarts.map { week(it, zone) }.toSet()
        // A week not driven yet doesn't break the run: count back from last week then.
        var w = if (thisWeek in weeks) thisWeek else thisWeek - 1
        var streak = 0
        while (w in weeks) {
            streak++
            w--
        }
        return days to streak
    }
}

/** A title: earned once [need] of the active stamps of its [field] (or of all, when null) are collected. */
data class Title(val id: String, val name: String, val kidsName: String, val field: StampField?, val need: Int) {
    fun name(kids: Boolean) = if (kids) kidsName else name

    fun progress(collected: Set<StampCode>): Int =
        collected.count { it in StampBook.ACTIVE && (field == null || StampBook.field(it) == field) }

    fun earned(collected: Set<StampCode>) = progress(collected) >= need

    fun condition(kids: Boolean): String = field?.let { "${if (kids) it.kidsLabel else it.label} 스탬프 모두" }
        ?: "스탬프 ${need}개"
}

object Titles {
    /** Shown before the first title. */
    const val NONE = "탐험대원"

    val ALL: List<Title> = listOf(
        Title("daily", "우리 동네 탐험가", "우리 동네 탐험가", StampField.DAILY, 4),
        Title("region", "지도 개척자", "지도 대장", StampField.REGION, 4),
        Title("trip", "주말 원정대", "주말 모험왕", StampField.TRIP, 4),
        Title("veteran", "베테랑 탐험가", "베테랑 탐험가", null, 8),
        Title("captain", "부릉 탐험대장", "부릉 탐험대장", null, 12),
    )

    /** The picked title if still earned, else the hardest one earned; null before any. */
    fun shown(picked: String, collected: Set<StampCode>): Title? =
        ALL.firstOrNull { it.id == picked && it.earned(collected) }
            ?: ALL.filter { it.earned(collected) }.maxByOrNull { it.need }

    /** The locked title fewest stamps away (ties go to the earlier one in the list). */
    fun next(collected: Set<StampCode>): Title? =
        ALL.filter { !it.earned(collected) }.minByOrNull { it.need - it.progress(collected) }
}

/** This week's suggested stamps: picked on the first look of the week, kept until Monday. */
object WeeklyPicks {
    const val COUNT = 3

    /**
     * Up to three stamps not collected yet, the most motivating first:
     * 1. one that completes a field (its title is one stamp away),
     * 2. a progress stamp well on its way (60 % or more),
     * 3. a place to go now: a new 시·군·구 when one is near ([placeNear]), the third 시·도 once two are in,
     * 4. the rest that can be done in a week (a weekend drive unless [weekendPaused], a far trip, family).
     * Never 첫 시동 (it comes with the first drive), and never what can't be reached soon (100 동 under
     * half way, 3 시·도 with fewer than two). Ties are broken in an order fixed for the [week].
     */
    fun pick(
        collected: Set<StampCode>,
        p: StampProgress,
        week: Long,
        placeNear: Boolean,
        weekendPaused: Boolean,
    ): List<StampCode> {
        val open = StampBook.ACTIVE.filter { it !in collected && it != StampCode.FIRST_DRIVE }
        val closing = StampField.entries.filter { f -> StampBook.ACTIVE.count { StampBook.field(it) == f && it in collected } == 3 }.toSet()
        fun ratio(c: StampCode) = StampBook.progress(c, p)?.let { (n, t) -> n.toDouble() / t } ?: 0.0
        fun rank(c: StampCode): Int? = when {
            c == StampCode.DONG_100 && ratio(c) < 0.5 -> null
            c == StampCode.PROVINCE_3 && p.provinces < 2 -> null
            c == StampCode.WEEKEND && weekendPaused -> null
            c == StampCode.NEW_DISTRICT && !placeNear -> 3
            StampBook.field(c) in closing -> 0
            ratio(c) >= 0.6 -> 1
            c == StampCode.NEW_DISTRICT || c == StampCode.PROVINCE_3 -> 2
            else -> 3
        }
        val order = open.shuffled(Random(week))
        return order.mapNotNull { c -> rank(c)?.let { c to it } }
            .sortedWith(compareBy<Pair<StampCode, Int>> { it.second }.thenByDescending { ratio(it.first) })
            .map { it.first }
            .take(COUNT)
    }
}
