package com.zobo.burung.explore

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/**
 * Level / XP rules shown on the map header and the record screen.
 *
 * 1 XP per ~30 m cell (about 1,100 XP per km² cleared), 10 XP per km driven on any road (so the
 * daily commute counts too, not only new roads), a little for each drop-off and stamp, and a bonus
 * for each stamp kind collected the first time. Levels need (L-1)^2.5 x 40 XP, so they slow down as
 * they climb: a week of 20 km commutes is around Lv.4, a month around Lv.7, a year around Lv.18;
 * a big first day of exploring (~2 km²) is around Lv.6. Capped at [MAX_LEVEL] for now.
 */
object Progress {
    const val XP_PER_CELL = 1
    const val XP_PER_KM = 10
    const val XP_PER_LANDING = 20
    const val XP_PER_STAMP = 10
    const val XP_PER_STAMP_KIND = 200
    /** Highest level; raise it (thresholds keep the same curve) when more content needs room. */
    const val MAX_LEVEL = 50
    private const val LEVEL_BASE = 40.0
    private const val LEVEL_EXP = 2.5

    fun xp(cells: Int, landings: Int, stamps: Int, stampKinds: Int, distanceMeters: Double = 0.0): Int =
        cells * XP_PER_CELL + (distanceMeters / 1000 * XP_PER_KM).toInt() +
            landings * XP_PER_LANDING + stamps * XP_PER_STAMP + stampKinds * XP_PER_STAMP_KIND

    /** Highest level whose threshold [xp] has reached, up to [MAX_LEVEL] (integer thresholds: no float edge cases). */
    fun level(xp: Int): Int {
        var lv = floor((xp.coerceAtLeast(0) / LEVEL_BASE).pow(1 / LEVEL_EXP)).toInt() + 1
        while (xpForLevel(lv + 1) <= xp) lv++
        while (lv > 1 && xpForLevel(lv) > xp) lv--
        return lv.coerceAtMost(MAX_LEVEL)
    }

    fun xpForLevel(level: Int): Int = ceil(LEVEL_BASE * (level - 1).toDouble().pow(LEVEL_EXP)).toInt()

    /** 0..1 progress toward the next level; 1 at [MAX_LEVEL]. */
    fun levelProgress(xp: Int): Float {
        val lv = level(xp)
        if (lv >= MAX_LEVEL) return 1f
        val start = xpForLevel(lv)
        val end = xpForLevel(lv + 1)
        return ((xp - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    }
}

/** One challenge goal: progress toward [target], shown through [label] (km², km or a count). */
data class ChallengeGoal(val title: String, val current: Int, val target: Int, val label: (Int) -> String = { "$it" }) {
    val done: Boolean get() = current >= target
}
