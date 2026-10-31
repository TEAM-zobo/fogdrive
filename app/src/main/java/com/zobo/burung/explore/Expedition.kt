package com.zobo.burung.explore

import com.zobo.burung.record.StampCode
import java.util.Calendar
import kotlin.random.Random

/** The four shelves of the stamp book, each feeding its own titles. */
enum class StampField(val label: String, val kidsLabel: String) {
    DAILY("일상", "매일 탐험"),
    TRIP("주말·여행", "주말 나들이"),
    REGION("지역", "새 동네 찾기"),
    TOGETHER("함께", "함께 탐험"),
}

/** Which stamps are collected, on which shelf, and how a missing one is earned. */
object StampBook {
    /**
     * No longer awarded or shown: they reward driving in the rain, at night, on a nearly empty battery,
     * in sport mode or 100 km without a break. Kept in [StampCode] so earlier records still read.
     */
    val RETIRED: Set<StampCode> = setOf(
        StampCode.RAIN, StampCode.NIGHT, StampCode.RAINY_NIGHT, StampCode.MIDNIGHT,
        StampCode.LOW_BATTERY, StampCode.SPORT, StampCode.MARATHON,
    )

    val ACTIVE: List<StampCode> = StampCode.entries.filter { it !in RETIRED }

    fun field(code: StampCode): StampField = when (code) {
        StampCode.FIRST_DRIVE, StampCode.ARRIVAL, StampCode.HOMECOMING, StampCode.CHARGE,
        StampCode.COMMUTE, StampCode.ECO, StampCode.OPEN_AIR, StampCode.SUNROOF -> StampField.DAILY
        StampCode.WEEKEND, StampCode.LONG_DRIVE, StampCode.DAWN, StampCode.SUNSET, StampCode.MARATHON -> StampField.TRIP
        StampCode.FAMILY, StampCode.FULL_HOUSE, StampCode.CREW_MISSION -> StampField.TOGETHER
        else -> StampField.REGION
    }

    /** How to earn [code], short enough for one line under its coin. */
    fun hint(code: StampCode, kids: Boolean): String = if (!kids) code.description else when (code) {
        StampCode.WEEKEND -> "주말에 새 길로 나들이 가요"
        StampCode.NEW_DISTRICT -> "한 번도 안 가 본 동네에 가 봐요"
        StampCode.NEW_PROVINCE -> "다른 지역으로 멀리 떠나 봐요"
        StampCode.LONG_DRIVE -> "한 번에 30 km 넘게 달려요"
        StampCode.FAMILY -> "뒷자리에 누가 타고 새 길을 가요"
        StampCode.CREW_MISSION -> "함께 탄 사람이랑 미션을 깨요"
        StampCode.SUNSET -> "해 질 녘에 새 길을 달려요"
        else -> code.description
    }
}

/** A title: earned once [need] stamps of its [field] (or of any field when null) are collected. */
data class Title(val id: String, val name: String, val kidsName: String, val field: StampField?, val need: Int) {
    fun name(kids: Boolean) = if (kids) kidsName else name

    /** Collected stamps that count toward this title. */
    fun progress(collected: Set<StampCode>): Int =
        collected.count { it !in StampBook.RETIRED && (field == null || StampBook.field(it) == field) }

    fun earned(collected: Set<StampCode>) = progress(collected) >= need

    fun condition(kids: Boolean): String =
        (field?.let { if (kids) it.kidsLabel else it.label }?.let { "$it 스탬프 " } ?: "스탬프 ") + "${need}개"
}

object Titles {
    val ALL: List<Title> = listOf(
        Title("rookie", "새내기 탐험가", "꼬마 탐험가", null, 1),
        Title("dong", "동네 탐험가", "동네 탐험가", StampField.REGION, 2),
        Title("commute", "출퇴근 개척자", "매일 탐험가", StampField.DAILY, 5),
        Title("weekend", "주말 여행가", "주말 모험왕", StampField.TRIP, 2),
        Title("alley", "골목 개척자", "골목 대장", StampField.REGION, 4),
        Title("expedition", "주말 원정대장", "주말 원정대장", StampField.TRIP, 4),
        Title("family", "가족 탐험대장", "가족 모험대장", StampField.TOGETHER, 2),
        Title("national", "전국 탐험가", "전국 모험왕", null, 15),
    )

    /** The picked title if still earned, else the hardest one earned; null before the first stamp. */
    fun shown(picked: String, collected: Set<StampCode>): Title? =
        ALL.firstOrNull { it.id == picked && it.earned(collected) }
            ?: ALL.filter { it.earned(collected) }.maxByOrNull { it.need }

    /** The locked title fewest stamps away (ties go to the earlier one in the list). */
    fun next(collected: Set<StampCode>): Title? =
        ALL.filter { !it.earned(collected) }.minByOrNull { it.need - it.progress(collected) }
}

/** This week's three suggested stamps: picked on the first look of the week, kept until Monday. */
object WeeklyPicks {
    const val COUNT = 3

    /** Monday-based week number, unique across years. */
    fun weekKey(now: Long): Int = Calendar.getInstance().run {
        firstDayOfWeek = Calendar.MONDAY
        minimalDaysInFirstWeek = 4
        timeInMillis = now
        getWeekYear() * 100 + get(Calendar.WEEK_OF_YEAR)
    }

    /**
     * Stamps not collected yet: [first] in order (what fits now, e.g. a place to go), then the rest in
     * an order fixed for the [week], so the suggestion doesn't change on every look.
     */
    fun pick(collected: Set<StampCode>, week: Int, first: List<StampCode>): List<StampCode> {
        val open = StampBook.ACTIVE.filter { it !in collected }
        val lead = first.filter { it in open }.distinct()
        val rest = (open - lead.toSet()).shuffled(Random(week))
        return (lead + rest).take(COUNT)
    }
}
