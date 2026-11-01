package com.zobo.burung.explore

import com.zobo.burung.record.StampCode
import java.util.TimeZone
import kotlin.random.Random

/** The three shelves of the stamp book; filling one (all its stamps) earns its title. */
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
    /** Weekday trips ending at the navigation's 회사. */
    val commutes: Int = 0,
    /** Island 시·군 entered (see [Islands]). */
    val islands: Int = 0,
    /** 서울 자치구 entered. */
    val seoulDistricts: Int = 0,
)

/** The 시·군 that are islands (or made of islands), for 섬 탐험가. */
object Islands {
    val DISTRICTS = setOf("제주시", "서귀포시", "울릉군", "강화군", "옹진군", "신안군", "완도군", "진도군")
}

/** Which stamps are collected, on which shelf, how a missing one is earned and how far along it is. */
object StampBook {
    /** The stamps of each field, in the order shown. */
    val BY_FIELD: Map<StampField, List<StampCode>> = linkedMapOf(
        StampField.DAILY to listOf(StampCode.FIRST_DRIVE, StampCode.WEEK_THREE, StampCode.STREAK_4W, StampCode.KM_1000),
        StampField.REGION to listOf(StampCode.DONG_10, StampCode.NEW_DISTRICT, StampCode.PROVINCE_3, StampCode.DONG_100),
        // No stamp for who sits where: the car knows a seat is taken, not who is in it.
        StampField.TRIP to listOf(StampCode.WEEKEND, StampCode.FAR_DAY, StampCode.CREW_MISSION),
    )

    /** The eleven stamps shown and awarded, field by field. */
    val ACTIVE: List<StampCode> = BY_FIELD.values.flatten()

    /**
     * Never awarded again: they reward driving in the rain, at night, on a nearly empty battery, in
     * sport mode or 100 km without a break. (Every code outside [ACTIVE] is neither awarded nor shown;
     * these are the ones left out on purpose, for safety.)
     */
    val RETIRED: Set<StampCode> = setOf(
        StampCode.RAIN, StampCode.NIGHT, StampCode.RAINY_NIGHT, StampCode.MIDNIGHT,
        StampCode.LOW_BATTERY, StampCode.SPORT, StampCode.MARATHON,
    )

    fun field(code: StampCode): StampField? = BY_FIELD.entries.firstOrNull { code in it.value }?.key

    fun inField(f: StampField): List<StampCode> = BY_FIELD.getValue(f)

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
        StampCode.CREW_MISSION -> "누군가와 함께 탐험"
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
        StampCode.CREW_MISSION -> "누군가와 함께 탐험해요"
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

/** What opens a title: a full stamp field, or a count from the driver's own record. */
enum class TitleKind { FIELD, COMMUTE, ISLAND, SEOUL }

/** A title: earned once its progress reaches [need] (stamps of its [field], commutes, islands or 서울 구). */
data class Title(
    val id: String,
    val name: String,
    val kidsName: String,
    val field: StampField?,
    val need: Int,
    val kind: TitleKind = TitleKind.FIELD,
) {
    fun name(kids: Boolean) = if (kids) kidsName else name

    fun progress(collected: Set<StampCode>, p: StampProgress = StampProgress()): Int = when (kind) {
        TitleKind.FIELD -> collected.count { it in StampBook.ACTIVE && StampBook.field(it) == field }
        TitleKind.COMMUTE -> p.commutes
        TitleKind.ISLAND -> p.islands
        TitleKind.SEOUL -> p.seoulDistricts
    }.coerceAtMost(need)

    fun earned(collected: Set<StampCode>, p: StampProgress = StampProgress()) = progress(collected, p) >= need

    private fun fieldLabel(kids: Boolean) = field?.let { if (kids) it.kidsLabel else it.label } ?: ""

    /** How the title is opened, short (under its shield). */
    fun condition(kids: Boolean): String = when (kind) {
        TitleKind.FIELD -> "${fieldLabel(kids)} 스탬프 모두"
        TitleKind.COMMUTE -> if (kids) "회사에 ${need}번 출근" else "평일 회사 주행 ${need}회"
        TitleKind.ISLAND -> if (kids) "섬에 놀러 가기" else "섬 지역 가 보기"
        TitleKind.SEOUL -> "서울 ${need}개 구 모두"
    }

    /** What is left, for the "다음 칭호" box. */
    fun remaining(kids: Boolean, collected: Set<StampCode>, p: StampProgress): String {
        val left = need - progress(collected, p)
        return when (kind) {
            TitleKind.FIELD -> "${fieldLabel(kids)} " + if (kids) "스탬프 ${left}개만 더 모아요!" else "스탬프 ${left}개만 더!"
            TitleKind.COMMUTE -> if (kids) "출근 ${left}번만 더 해요!" else "회사 주행 ${left}회만 더!"
            TitleKind.ISLAND -> if (kids) "섬에 놀러 가면 받아요!" else "섬 지역에 가 보면 받아요!"
            TitleKind.SEOUL -> if (kids) "서울 동네 ${left}곳만 더 가 봐요!" else "서울 구 ${left}곳만 더!"
        }
    }
}

object Titles {
    /** Shown before the first title. */
    const val NONE = "탐험대원"
    const val COMMUTE_NEED = 20
    const val SEOUL_DISTRICTS = 25

    val ALL: List<Title> = listOf(
        Title("daily", "우리 동네 탐험가", "우리 동네 탐험가", StampField.DAILY, StampBook.inField(StampField.DAILY).size),
        Title("trip", "주말 원정대", "주말 모험왕", StampField.TRIP, StampBook.inField(StampField.TRIP).size),
        Title("commute", "프로 출근러", "출근 대장", null, COMMUTE_NEED, TitleKind.COMMUTE),
        Title("island", "섬 탐험가", "섬 모험가", null, 1, TitleKind.ISLAND),
        Title("seoul", "서울 마스터", "서울 대장", null, SEOUL_DISTRICTS, TitleKind.SEOUL),
    )

    /** The picked title if still earned, else the last earned one in [ALL] (the later, the rarer). */
    fun shown(picked: String, collected: Set<StampCode>, p: StampProgress = StampProgress()): Title? =
        ALL.firstOrNull { it.id == picked && it.earned(collected, p) }
            ?: ALL.lastOrNull { it.earned(collected, p) }

    /** The locked title nearest to done, by share of its need (ties go to the earlier one). */
    fun next(collected: Set<StampCode>, p: StampProgress = StampProgress()): Title? =
        ALL.filter { !it.earned(collected, p) }.maxByOrNull { it.progress(collected, p).toDouble() / it.need }

    /** Ids of the titles earned. */
    fun earnedIds(collected: Set<StampCode>, p: StampProgress): Set<String> =
        ALL.filter { it.earned(collected, p) }.mapTo(HashSet()) { it.id }
}

/** This week's suggested stamps: picked on the first look of the week, kept until Monday. */
object WeeklyPicks {
    const val COUNT = 3
    /** What an everyday driver adds in a week (commutes plus a weekend outing): ~300 km, a few new 동. */
    const val WEEK_KM = 300
    const val WEEK_DONGS = 5

    /**
     * Up to three stamps not collected yet that an everyday week of driving can earn, the most
     * motivating first:
     * 1. one that completes a field (its title is one stamp away),
     * 2. a progress stamp well on its way (60 % or more),
     * 3. a place to go now: a new 시·군·구 when one is near ([placeNear]), the third 시·도 when one is
     *    near ([provinceNear]) and two are in,
     * 4. the rest (a weekend drive unless [weekendPaused], a far trip, a drive together).
     * Never 첫 시동 (it comes with the first drive), and never what a week can't reach: 4 weeks in a
     * row before the third, more than [WEEK_KM] km or [WEEK_DONGS] 동 still to go, a place with
     * none near. Ties are broken in an order fixed for the [week].
     */
    fun pick(
        collected: Set<StampCode>,
        p: StampProgress,
        week: Long,
        placeNear: Boolean,
        weekendPaused: Boolean,
        provinceNear: Boolean = placeNear,
    ): List<StampCode> {
        val open = StampBook.ACTIVE.filter { it !in collected && it != StampCode.FIRST_DRIVE }
        val closing = StampField.entries.filter { f -> StampBook.inField(f).count { it !in collected } == 1 }.toSet()
        fun ratio(c: StampCode) = StampBook.progress(c, p)?.let { (n, t) -> n.toDouble() / t } ?: 0.0
        fun left(c: StampCode) = StampBook.progress(c, p)?.let { (n, t) -> t - n } ?: 0
        fun reachable(c: StampCode): Boolean = when (c) {
            StampCode.STREAK_4W -> p.streakWeeks >= 3
            StampCode.KM_1000 -> left(c) <= WEEK_KM
            StampCode.DONG_10, StampCode.DONG_100 -> left(c) <= WEEK_DONGS
            StampCode.NEW_DISTRICT -> placeNear
            StampCode.PROVINCE_3 -> p.provinces >= 2 && provinceNear
            StampCode.WEEKEND -> !weekendPaused
            else -> true
        }
        fun rank(c: StampCode): Int? = when {
            !reachable(c) -> null
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
