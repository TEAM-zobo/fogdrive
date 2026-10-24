package com.zobo.fogdrive.explore

import kotlin.math.floor
import kotlin.math.sqrt

/** Level / XP rules shown on the map header and the record screen. */
object Progress {
    const val XP_PER_CELL = 10
    const val XP_PER_LANDING = 50
    const val XP_PER_STAMP = 100

    fun xp(cells: Int, landings: Int, stamps: Int): Int =
        cells * XP_PER_CELL + landings * XP_PER_LANDING + stamps * XP_PER_STAMP

    fun level(xp: Int): Int = floor(sqrt(xp / 100.0)).toInt() + 1

    fun xpForLevel(level: Int): Int = (level - 1) * (level - 1) * 100

    /** 0..1 progress toward the next level. */
    fun levelProgress(xp: Int): Float {
        val lv = level(xp)
        val start = xpForLevel(lv)
        val end = xpForLevel(lv + 1)
        return ((xp - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    }
}

/** Monthly season challenge targets. */
data class SeasonGoal(val title: String, val current: Int, val target: Int, val label: (Int) -> String = { "$it" }) {
    val done: Boolean get() = current >= target
}
