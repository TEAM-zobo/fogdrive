package com.zobo.burung.record

import com.zobo.burung.car.ArrivalKind
import com.zobo.burung.car.DriveMode
import com.zobo.burung.car.Fix
import com.zobo.burung.car.Gear
import com.zobo.burung.car.VehicleState
import com.zobo.burung.data.CellSource
import com.zobo.burung.geo.Tiles
import java.util.Calendar

interface ExploreSink {
    fun reveal(newCells: Collection<Long>, source: Int): Int
    fun isExplored(cell: Long): Boolean
    /** Already-explored cells driven over again on this trip (each at most once per trip). */
    fun revisit(cells: Collection<Long>) {}
    /** No trip recorded yet (the "첫 시동" stamp); cells seeded from the navigation don't count. */
    fun noTripsYet(): Boolean = false
}

enum class StampCode(val title: String, val description: String) {
    RAIN("빗속 개척", "와이퍼가 켜진 채 새 길을 달렸어요"),
    NIGHT("야간 개척", "전조등을 켜고 새 길을 달렸어요"),
    FAMILY("가족 탐험", "뒷좌석에 동승자를 태우고 새 길을 달렸어요"),
    CHARGE("충전소 깃발", "정차 중 충전으로 배터리가 올랐어요"),
    ARRIVAL("목적지 정복", "내비 안내를 따라 목적지에 도착했어요"),
    EXPEDITION("안개 원정", "안개 구역 탐험 경유지에 도착했어요"),
    DAWN("새벽 개척", "새벽 5~7시에 새 길을 달렸어요"),
    SUNSET("노을 드라이브", "해 질 녘 5~7시에 새 길을 달렸어요"),
    WEEKEND("주말 탐험가", "토요일이나 일요일에 새 길을 달렸어요"),
    LONG_DRIVE("장거리 원정", "한 번의 주행으로 30 km 넘게 달렸어요"),
    PIONEER("개척왕", "한 번의 주행으로 0.1 km² 넘게 안개를 걷었어요"),
    NEW_DISTRICT("새 동네 첫발", "처음 가 보는 시·군·구에 들어섰어요"),
    MIDNIGHT("심야 질주", "자정부터 새벽 4시 사이에 새 길을 달렸어요"),
    COMMUTE("출근길 개척", "평일 아침 7~9시에 새 길을 달렸어요"),
    RAINY_NIGHT("비 오는 밤", "비 오는 밤에 전조등과 와이퍼를 켜고 새 길을 달렸어요"),
    FULL_HOUSE("만석 드라이브", "동승자 두 명 이상(뒷좌석 포함)을 태우고 새 길을 달렸어요"),
    LOW_BATTERY("아슬아슬 탐험", "배터리 20% 이하로 새 길을 달렸어요"),
    MARATHON("마라톤 드라이브", "한 번의 주행으로 100 km 넘게 달렸어요"),
    ALL_NEW("완전 개척", "5 km 넘는 주행을 90% 넘게 새 길로만 달렸어요"),
    HOMECOMING("귀소 본능", "10 km 넘게 달린 뒤 출발한 곳으로 돌아와 내렸어요"),
    FIRST_DRIVE("첫 시동", "부릉탐험대와 함께 첫 주행을 시작했어요"),
    DONG_HOPPER("동네 순례", "한 번의 주행으로 새 읍·면·동을 5곳 넘게 지났어요"),
    PROVINCE_CROSS("경계 넘어", "한 번의 주행으로 시·도 경계를 넘었어요"),
    NEW_PROVINCE("새 땅 밟기", "처음 가 보는 시·도에 들어섰어요"),
    OPEN_AIR("바람길 개척", "창문을 열고 새 길을 달렸어요"),
    SUNROOF("하늘 열고 개척", "선루프를 열고 새 길을 달렸어요"),
    ECO("에코 탐험가", "에코 모드로 새 길을 달렸어요"),
    SPORT("스포츠 개척", "스포츠 모드로 새 길을 달렸어요"),
    CREW_MISSION("함께한 미션", "동승자와 이번 주행 미션을 끝냈어요"),
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
 * - new cells revealed under rain / headlights / passengers / open windows / eco or sport mode earn
 *   situation stamps;
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
    private val revisitedThisTrip = HashSet<Long>()
    /** Cells the car itself has driven through this trip, so doubling back is not new road. */
    private val visitedThisTrip = HashSet<Long>()
    private var prevCenter: Long? = null
    private var prevStepNew = false
    private var lastLandingAt = 0L
    private var lastLandingLat = 0.0
    private var lastLandingLng = 0.0
    private var socAtPark: Int? = null
    private var tripStartLat = 0.0
    private var tripStartLng = 0.0
    /** The trip just started with nothing explored yet: "첫 시동" is awarded by the next [onFix]. */
    private var firstDrivePending = false
    private var chargeStamped = false

    /**
     * Starts a trip on the first moving, accurate fix. The service calls this before [onFix] so the
     * trip row exists (and new cells are tagged with its id) before the first swath is revealed;
     * [onFix] still calls it for callers that don't.
     */
    fun startTripIfNeeded(fix: Fix, vehicle: VehicleState): RecorderEvent.TripStarted? {
        if (tripActive || vehicle.parked || (fix.accuracyM ?: 0f) > MAX_ACCURACY_M) return null
        tripActive = true
        stats = TripStats()
        stampsThisTrip.clear()
        revealedThisTrip.clear()
        revisitedThisTrip.clear()
        visitedThisTrip.clear()
        prevCenter = null
        prevStepNew = false
        // Set here, not in [onFix]: the service starts the trip itself before calling onFix.
        tripStartLat = fix.lat
        tripStartLng = fix.lng
        firstDrivePending = sink.noTripsYet()
        return RecorderEvent.TripStarted(fix.timeMs)
    }

    fun onFix(fix: Fix, vehicle: VehicleState): List<RecorderEvent> {
        if ((fix.accuracyM ?: 0f) > MAX_ACCURACY_M) return emptyList()
        val events = mutableListOf<RecorderEvent>()
        val prev = last
        last = fix
        if (vehicle.parked) return events

        startTripIfNeeded(fix, vehicle)?.let { events += it }
        if (firstDrivePending) {
            firstDrivePending = false
            award(StampCode.FIRST_DRIVE, fix, events)
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
        // Cells explored before this trip count one more drive, once per trip (stored as visits).
        val again = swath.filter { it !in revealedThisTrip && it !in revisitedThisTrip && sink.isExplored(it) }
        if (again.isNotEmpty()) {
            revisitedThisTrip.addAll(again)
            sink.revisit(again)
        }
        for (c in swath) if (!sink.isExplored(c)) revealedThisTrip.add(c)
        val added = sink.reveal(swath, CellSource.DRIVE)
        stats = stats.copy(
            newCells = stats.newCells + added,
            distanceMeters = stats.distanceMeters + step,
            newDistanceMeters = stats.newDistanceMeters + if (centerWasNew) step else 0.0,
        )
        if (added > 0) {
            // Nothing for rain, night, a low battery or sport mode (StampBook.RETIRED): no reward for riskier driving.
            if (vehicle.rearOccupied) award(StampCode.FAMILY, fix, events)
            // Two or more besides the driver, at least one of them in the back.
            if (vehicle.occupiedSeats.count { it != DRIVER_SEAT } >= 2 && vehicle.rearOccupied) award(StampCode.FULL_HOUSE, fix, events)
            if (vehicle.windowsOpen.isNotEmpty()) award(StampCode.OPEN_AIR, fix, events)
            if (vehicle.sunroofOpen) award(StampCode.SUNROOF, fix, events)
            if (vehicle.drivingMode == DriveMode.ECO) award(StampCode.ECO, fix, events)
            // Time-of-day / day-of-week stamps, from the fix's local time.
            val time = Calendar.getInstance().apply { timeInMillis = fix.timeMs }
            val day = time.get(Calendar.DAY_OF_WEEK)
            val weekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY
            when (time.get(Calendar.HOUR_OF_DAY)) {
                in 5..6 -> award(StampCode.DAWN, fix, events)
                in 7..8 -> if (!weekend) award(StampCode.COMMUTE, fix, events)
                in 17..18 -> award(StampCode.SUNSET, fix, events)
            }
            if (weekend) award(StampCode.WEEKEND, fix, events)
        }
        if (stats.distanceMeters >= LONG_DRIVE_M) award(StampCode.LONG_DRIVE, fix, events)
        if (stats.newCells >= PIONEER_CELLS) award(StampCode.PIONEER, fix, events)
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
                    // Judged on the finished trip, so they land in its stamps before it closes.
                    val s = stats
                    if (s.distanceMeters >= ALL_NEW_MIN_M && s.newDistanceMeters >= s.distanceMeters * ALL_NEW_RATIO) {
                        award(StampCode.ALL_NEW, fix, events)
                    }
                    if (s.distanceMeters >= HOMECOMING_MIN_M &&
                        Tiles.distanceMeters(tripStartLat, tripStartLng, fix.lat, fix.lng) <= HOMECOMING_RADIUS_M
                    ) {
                        award(StampCode.HOMECOMING, fix, events)
                    }
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
        val area = Tiles.neighborhood(Tiles.cellOf(fix.lat, fix.lng), radius)
        // A waypoint arrival keeps the trip going: what it clears is new on this trip, not a second drive.
        if (tripActive) for (c in area) if (!sink.isExplored(c)) revealedThisTrip.add(c)
        val added = sink.reveal(area, CellSource.ARRIVAL)
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
        const val LONG_DRIVE_M = 30_000.0
        /** ~0.1 km² of ~30 m cells. */
        const val PIONEER_CELLS = 110
        const val DRIVER_SEAT = "ROW_1_LEFT"
        const val ALL_NEW_MIN_M = 5_000.0
        const val ALL_NEW_RATIO = 0.9
        const val HOMECOMING_MIN_M = 10_000.0
        const val HOMECOMING_RADIUS_M = 300.0
    }
}
