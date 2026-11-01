package com.zobo.burung.explore

import com.zobo.burung.geo.Area

/**
 * One mission for the people riding along, picked when a trip starts with a passenger aboard.
 * The driving view shows only its one-line progress (glanceable, nothing to touch); finishing it
 * earns the "함께한 미션" stamp. Targets are small enough for one ordinary drive.
 */
data class CrewMission(val kind: Kind, val target: Int) {
    enum class Kind { NEW_DONGS, AREA, NEW_ROAD_KM }

    fun title(): String = when (kind) {
        Kind.NEW_DONGS -> "새 동 ${target}곳 지나기"
        Kind.AREA -> "새로운 곳 ${Area.formatInt(target)} 탐험하기"
        Kind.NEW_ROAD_KM -> "새 길 ${target} km 달리기"
    }

    /** Progress in the mission's own unit: 동 count, m² cleared, or km of new road. */
    fun progress(newDongs: Int, areaM2: Double, newRoadMeters: Double): Int = when (kind) {
        Kind.NEW_DONGS -> newDongs
        Kind.AREA -> areaM2.toInt()
        Kind.NEW_ROAD_KM -> (newRoadMeters / 1000).toInt()
    }

    /** "1/2" style progress, capped at the target. */
    fun progressLabel(current: Int): String = when (kind) {
        Kind.AREA -> "${Area.formatInt(current.coerceAtMost(target))} / ${Area.formatInt(target)}"
        else -> "${current.coerceAtMost(target)}/$target"
    }

    companion object {
        /** Picks a mission from the trip start minute: varied between trips, stable for one trip. */
        fun pick(startedAt: Long): CrewMission = when (Math.floorMod(startedAt / 60_000, 3L).toInt()) {
            0 -> CrewMission(Kind.NEW_DONGS, 2)
            1 -> CrewMission(Kind.AREA, 100_000)
            else -> CrewMission(Kind.NEW_ROAD_KM, 3)
        }
    }
}
