package com.zobo.burung.demo

import android.os.SystemClock
import com.zobo.burung.car.Arrival
import com.zobo.burung.car.ArrivalKind
import com.zobo.burung.car.Fix
import com.zobo.burung.car.Gear
import com.zobo.burung.car.Guidance
import com.zobo.burung.car.LocationFeed
import com.zobo.burung.car.TurnHint
import com.zobo.burung.car.TurnKind
import com.zobo.burung.car.VehicleState
import com.zobo.burung.geo.Tiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Scripted drive for demos and the submission video, along one of [DemoCourses]: rain, night
 * lights or a rear passenger on the way, and an arrival plus a drop-off at the end. It feeds the
 * same recorder pipeline as real vehicle data, and stands in for the navigation's guidance (next
 * turn, speed limit) and destination arrival, which the emulator cannot produce.
 */
class DemoDrive(
    private val scope: CoroutineScope,
    private val onFix: (Fix) -> Unit,
    private val onVehicle: (VehicleState) -> Unit,
    private val onGuidance: (Guidance?) -> Unit,
    private val onArrival: (Arrival) -> Unit,
    private val onFinished: () -> Unit,
) {
    private var job: Job? = null
    val running: Boolean get() = job?.isActive == true

    /**
     * Plays [course]. [speedKmh] is read at every step, so the speed set in Settings applies while
     * the drive is playing; time runs [speedUp] times faster than that speed so a drive fits a demo.
     */
    fun start(
        course: DemoCourse,
        speedKmh: () -> Int,
        /** Read on arrival: drive the same road back to the start (a second trip, after the drop-off). */
        roundTrip: () -> Boolean = { false },
        onReturn: () -> Unit = {},
        speedUp: Double = SPEED_UP,
    ) {
        stop()
        job = scope.launch {
            val seats = buildSet {
                add("ROW_1_LEFT")
                if (course.frontPassenger) add("ROW_1_RIGHT")
                if (course.rearPassenger) add("ROW_2_LEFT")
            }
            var vehicle = VehicleState(
                gear = Gear.PARK, occupiedSeats = seats, lowBeamOn = course.lowBeam,
                sunroofOpen = course.sunroof, drivingMode = course.driveMode, rangeKm = DEMO_RANGE_KM,
                aaosConnected = true, pleosVehicleConnected = true,
            )
            onVehicle(vehicle)
            onFix(fix(course.route.first()))
            // Parked briefly before departure (the detour suggestion card shows here). It was 6 s, which
            // read as the demo not starting; 1 s was too short for the card to be planned and shown.
            delay(PARKED_BEFORE_DEPARTURE_MS)
            vehicle = drive(course, vehicle, speedKmh, speedUp) ?: return@launch
            if (roundTrip()) {
                onReturn()
                delay(4_000)  // parked at the destination for a moment, then back the same way
                drive(course.returnTrip(), vehicle, speedKmh, speedUp) ?: return@launch
            }
            onFinished()
        }
    }

    /**
     * One leg: depart, follow [course], arrive, park and open the driver's door (a drop-off).
     * Returns the parked vehicle state, or null if the demo was stopped on the way.
     */
    private suspend fun CoroutineScope.drive(
        course: DemoCourse,
        start: VehicleState,
        speedKmh: () -> Int,
        speedUp: Double,
    ): VehicleState? {
        var vehicle = start.copy(gear = Gear.DRIVE, drivingRestricted = true)
        onVehicle(vehicle)
        val points = densify(course, stepMeters = STEP_M)
        // At high demo speeds 15 m steps meant 20+ fixes a second, each re-rendering the UI and map;
        // a real car reports 1 a second. Fixes are sent at most every MIN_FIX_INTERVAL_MS (plus every
        // route corner, so the cleared path never cuts across a block); the recorder joins the cells
        // in between, so the same road is cleared either way.
        // Each fix is sent when the car reaches it, on a running clock. Sending first and then waiting
        // for the stretch just driven put that wait before the *next* fix instead: a corner 15 m on
        // came a whole interval late and the fix after it almost at once, so the driving map crawled
        // and then jumped at every corner.
        var pendingMs = 0.0
        var due = SystemClock.uptimeMillis().toDouble()
        for ((i, p) in points.withIndex()) {
            if (!isActive) return null
            val mps = speedKmh().coerceIn(MIN_KMH, MAX_KMH) / 3.6
            val stepMs = STEP_M / mps * 1000 / speedUp
            pendingMs += stepMs
            due += stepMs
            val last = i == points.lastIndex
            if (pendingMs < MIN_FIX_INTERVAL_MS && !p.corner && !last) continue
            val now = SystemClock.uptimeMillis()
            // Running late (a busy main thread): carry on from now rather than bursting to catch up.
            if (now - due > MAX_LATE_MS) due = now.toDouble()
            delay((due - now).toLong())
            val progress = i.toDouble() / points.size
            onGuidance(guidanceAt(course, p.along))
            val wiper = course.rain?.contains(progress) == true
            val window = course.windowOpen?.contains(progress) == true
            if (wiper != vehicle.wiperOn || window != vehicle.windowsOpen.isNotEmpty()) {
                vehicle = vehicle.copy(wiperOn = wiper, windowsOpen = if (window) setOf("ROW_1_LEFT") else emptySet())
                onVehicle(vehicle)
            }
            // Stamp the fix with when the car reached it (its due time), not when it was sent, so a
            // late send does not show up as uneven driving on the map's fix timeline.
            onFix(fix(p.latLng, System.currentTimeMillis() + (due.toLong() - SystemClock.uptimeMillis())))
            pendingMs = 0.0
        }
        onGuidance(null)
        onArrival(Arrival(ArrivalKind.DESTINATION, course.destination.name, demo = true))
        delay(1_200)
        // Parked: wipers and windows off, and the range down by the leg just driven.
        vehicle = vehicle.copy(
            gear = Gear.PARK, drivingRestricted = false, wiperOn = false, windowsOpen = emptySet(),
            rangeKm = vehicle.rangeKm?.let { (it - course.lengthMeters / 1000).toFloat().coerceAtLeast(0f) },
        )
        onVehicle(vehicle)
        delay(1_200)
        onVehicle(vehicle.copy(doorsOpen = setOf("ROW_1_LEFT")))
        delay(1_500)
        onVehicle(vehicle)
        return vehicle
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun fix(p: Pair<Double, Double>, timeMs: Long = System.currentTimeMillis()) =
        Fix(p.first, p.second, timeMs, 5f, LocationFeed.SOURCE_DEMO)

    /** [corner]: an original route vertex (always sent as a fix), not a point filled in between. */
    private class RoutePoint(val latLng: Pair<Double, Double>, val along: Double, val corner: Boolean = false)

    private fun densify(course: DemoCourse, stepMeters: Double): List<RoutePoint> {
        val route = course.route
        val out = ArrayList<RoutePoint>()
        for (i in 0 until route.size - 1) {
            val (a, b) = route[i] to route[i + 1]
            val d = Tiles.distanceMeters(a.first, a.second, b.first, b.second)
            val n = kotlin.math.max(1, (d / stepMeters).toInt())
            for (k in 0 until n) {
                val t = k.toDouble() / n
                out += RoutePoint((a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t), course.along[i] + d * t, k == 0)
            }
        }
        out += RoutePoint(route.last(), course.lengthMeters)
        return out
    }

    /** Next turn ahead of [along] metres into the course, and its speed limit. */
    private fun guidanceAt(course: DemoCourse, along: Double): Guidance {
        val turn = course.turns.firstOrNull { it.first > along + 1 }
        val hint = if (turn != null) TurnHint(turn.second, (turn.first - along).toInt())
        else TurnHint(TurnKind.ARRIVE, (course.lengthMeters - along).toInt())
        return Guidance(routing = true, speedLimitKmh = course.speedLimitAt(along), nextTurn = hint)
    }

    companion object {
        const val MIN_KMH = 20
        const val MAX_KMH = 200
        /** Remaining range the demo car starts with (a charged EV), km. */
        const val DEMO_RANGE_KM = 480f
        const val DEFAULT_KMH = 60
        private const val PARKED_BEFORE_DEPARTURE_MS = 2_000L
        private const val STEP_M = 15.0
        private const val MIN_FIX_INTERVAL_MS = 200.0
        private const val MAX_LATE_MS = 500
        /** Demo time runs this much faster than the set speed (a 16 km course takes ~1.6 min at 60 km/h). */
        const val SPEED_UP = 10.0
    }
}
