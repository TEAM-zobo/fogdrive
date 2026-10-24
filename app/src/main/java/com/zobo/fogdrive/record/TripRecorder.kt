package com.zobo.fogdrive.record

import com.zobo.fogdrive.car.ArrivalKind
import com.zobo.fogdrive.car.Fix
import com.zobo.fogdrive.car.Gear
import com.zobo.fogdrive.car.VehicleState
import com.zobo.fogdrive.data.CellSource
import com.zobo.fogdrive.geo.Tiles

interface ExploreSink {
    fun reveal(newCells: Collection<Long>, source: Int): Int
    fun isExplored(cell: Long): Boolean
}

enum class StampCode(val title: String, val description: String) {
    RAIN("빗속 개척", "와이퍼가 켜진 채 새 길을 달렸어요"),
    NIGHT("야간 개척", "전조등을 켜고 새 길을 달렸어요"),
    FAMILY("가족 탐험", "뒷좌석에 동승자를 태우고 새 길을 달렸어요"),
    CHARGE("충전소 깃발", "정차 중 충전으로 배터리가 올랐어요"),
    ARRIVAL("목적지 정복", "내비 안내를 따라 목적지에 도착했어요"),
    EXPEDITION("안개 원정", "안개 구역 탐험 경유지에 도착했어요"),
}

sealed interface RecorderEvent {
    data class TripStarted(val at: Long) : RecorderEvent
    data class TripEnded(val at: Long) : RecorderEvent
    data class Landing(val lat: Double, val lng: Double, val at: Long, val newCells: Int) : RecorderEvent
    data class Arrival(val kind: ArrivalKind, val name: String, val lat: Double, val lng: Double, val at: Long, val newCells: Int) : RecorderEvent
    data class Stamp(val code: StampCode, val lat: Double, val lng: Double, val at: Long) : RecorderEvent
}

data class TripStats(
    val newCells: Int = 0,
    val distanceMeters: Double = 0.0,
    /** Distance driven through cells that were still fogged — the "새 길 비율" numerator. */
    val newDistanceMeters: Double = 0.0,
)

/**
 * Pure recording rules (no Android dependencies, unit-tested):
 * - while not parked, each fix reveals the cells along the path plus a 1-cell margin (~90 m swath:
 *   the road and what is visible from it);
 * - P + a door opening = a landing that reveals a 7x7 block (~210 m, a walk around) and ends the trip;
 * - new cells revealed under rain / headlights / rear passengers earn situation stamps;
 * - a navigation arrival reveals the block around the car (wider for the fog detour) and earns a stamp.
 */
class TripRecorder(private val sink: ExploreSink) {
    var stats = TripStats()
        private set
    var tripActive = false
        private set

    private var last: Fix? = null
    private var prevVehicle = VehicleState()
    private val stampsThisTrip = mutableSetOf<StampCode>()
    /** Cells first revealed during this trip still count as new road (the swath reveals ahead of the car). */
    private val revealedThisTrip = HashSet<Long>()
    /** Cells the car itself has driven through this trip, so doubling back is not new road. */
    private val visitedThisTrip = HashSet<Long>()
    private var prevCenter: Long? = null
    private var prevStepNew = false
    private var lastLandingAt = 0L
    private var lastLandingLat = 0.0
    private var lastLandingLng = 0.0
    private var socAtPark: Int? = null
    private var chargeStamped = false

    fun onFix(fix: Fix, vehicle: VehicleState): List<RecorderEvent> {
        if ((fix.accuracyM ?: 0f) > MAX_ACCURACY_M) return emptyList()
        val events = mutableListOf<RecorderEvent>()
        val prev = last
        last = fix
        if (vehicle.parked) return events

        if (!tripActive) {
            tripActive = true
            stats = TripStats()
            stampsThisTrip.clear()
            revealedThisTrip.clear()
            visitedThisTrip.clear()
            prevCenter = null
            prevStepNew = false
            events += RecorderEvent.TripStarted(fix.timeMs)
        }

        val center = Tiles.cellOf(fix.lat, fix.lng)
        val path: Set<Long>
        var step = 0.0
        if (prev != null) {
            step = Tiles.distanceMeters(prev.lat, prev.lng, fix.lat, fix.lng)
            path = if (step <= MAX_JOIN_M) Tiles.cellsAlong(prev.lat, prev.lng, fix.lat, fix.lng) else setOf(center)
            if (step > MAX_JOIN_M) step = 0.0
        } else {
            path = setOf(center)
        }
        val fresh = center in revealedThisTrip || !sink.isExplored(center)
        val firstPass = center !in visitedThisTrip || (center == prevCenter && prevStepNew)
        val centerWasNew = fresh && firstPass
        visitedThisTrip.add(center)
        prevCenter = center
        prevStepNew = centerWasNew
        val swath = LinkedHashSet<Long>()
        for (c in path) swath.addAll(Tiles.neighborhood(c, DRIVE_RADIUS))
        for (c in swath) if (!sink.isExplored(c)) revealedThisTrip.add(c)
        val added = sink.reveal(swath, CellSource.DRIVE)
        stats = stats.copy(
            newCells = stats.newCells + added,
            distanceMeters = stats.distanceMeters + step,
            newDistanceMeters = stats.newDistanceMeters + if (centerWasNew) step else 0.0,
        )
        if (added > 0) {
            if (vehicle.wiperOn) award(StampCode.RAIN, fix, events)
            if (vehicle.lowBeamOn) award(StampCode.NIGHT, fix, events)
            if (vehicle.rearOccupied) award(StampCode.FAMILY, fix, events)
        }
        return events
    }

    fun onVehicle(vehicle: VehicleState, now: Long): List<RecorderEvent> {
        val events = mutableListOf<RecorderEvent>()
        val prev = prevVehicle
        prevVehicle = vehicle
        val fix = last

        val doorJustOpened = vehicle.anyDoorOpen && !prev.anyDoorOpen
        if (doorJustOpened && vehicle.parked && fix != null) {
            val farFromLast = Tiles.distanceMeters(lastLandingLat, lastLandingLng, fix.lat, fix.lng) > LANDING_MIN_GAP_M
            if (lastLandingAt == 0L || farFromLast || now - lastLandingAt > LANDING_MIN_INTERVAL_MS) {
                val added = sink.reveal(Tiles.neighborhood(Tiles.cellOf(fix.lat, fix.lng), LANDING_RADIUS), CellSource.LANDING)
                stats = stats.copy(newCells = stats.newCells + added)
                lastLandingAt = now
                lastLandingLat = fix.lat
                lastLandingLng = fix.lng
                events += RecorderEvent.Landing(fix.lat, fix.lng, now, added)
                if (tripActive) {
                    tripActive = false
                    events += RecorderEvent.TripEnded(now)
                }
            }
        }

        // Charging: state of charge rising while parked.
        if (vehicle.parked && vehicle.evSocPercent != null) {
            val start = socAtPark
            // Baseline = lowest value seen while parked, so a drain before plugging in still counts.
            if (start == null || vehicle.evSocPercent < start) {
                socAtPark = vehicle.evSocPercent
            } else if (!chargeStamped && vehicle.evSocPercent - start >= CHARGE_STAMP_DELTA && fix != null) {
                chargeStamped = true
                events += RecorderEvent.Stamp(StampCode.CHARGE, fix.lat, fix.lng, now)
            }
        } else if (!vehicle.parked) {
            socAtPark = null
            chargeStamped = false
        }
        if (prev.gear == Gear.PARK && vehicle.gear != Gear.PARK && vehicle.gear != Gear.UNKNOWN) {
            socAtPark = null
        }
        return events
    }

    fun onArrival(kind: ArrivalKind, name: String, now: Long): List<RecorderEvent> {
        // Without a recent fix (tunnel, underground car park) the car's position is unknown.
        val fix = last?.takeIf { now - it.timeMs <= MAX_ARRIVAL_FIX_AGE_MS } ?: return emptyList()
        val radius = if (kind == ArrivalKind.DETOUR) DETOUR_RADIUS else ARRIVAL_RADIUS
        val added = sink.reveal(Tiles.neighborhood(Tiles.cellOf(fix.lat, fix.lng), radius), CellSource.ARRIVAL)
        if (tripActive) stats = stats.copy(newCells = stats.newCells + added)
        val events = mutableListOf<RecorderEvent>()
        when (kind) {
            ArrivalKind.DESTINATION -> award(StampCode.ARRIVAL, fix, events)
            ArrivalKind.DETOUR -> award(StampCode.EXPEDITION, fix, events)
            ArrivalKind.WAYPOINT -> Unit
        }
        // Arrival last, so its "+N칸" message is the one left on screen after the stamp's.
        events += RecorderEvent.Arrival(kind, name, fix.lat, fix.lng, now, added)
        return events
    }

    /**
     * Closes the current trip without a landing (service stopping, demo stopped, records reset).
     * Also forgets the last fix so the next fix is not joined to a position from another session.
     */
    fun endTrip(now: Long): List<RecorderEvent> {
        last = null
        if (!tripActive) return emptyList()
        tripActive = false
        return listOf(RecorderEvent.TripEnded(now))
    }

    private fun award(code: StampCode, fix: Fix, events: MutableList<RecorderEvent>) {
        if (stampsThisTrip.add(code)) events += RecorderEvent.Stamp(code, fix.lat, fix.lng, fix.timeMs)
    }

    companion object {
        const val DRIVE_RADIUS = 1
        const val LANDING_RADIUS = 3
        const val ARRIVAL_RADIUS = 2
        const val DETOUR_RADIUS = 3
        const val MAX_ARRIVAL_FIX_AGE_MS = 2 * 60_000L
        const val MAX_ACCURACY_M = 60f
        const val MAX_JOIN_M = 1_000.0
        const val LANDING_MIN_GAP_M = 300.0
        const val LANDING_MIN_INTERVAL_MS = 10 * 60_000L
        const val CHARGE_STAMP_DELTA = 2
    }
}
