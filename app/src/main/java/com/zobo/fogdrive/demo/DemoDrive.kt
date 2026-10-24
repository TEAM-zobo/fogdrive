package com.zobo.fogdrive.demo

import com.zobo.fogdrive.car.Arrival
import com.zobo.fogdrive.car.ArrivalKind
import com.zobo.fogdrive.car.Fix
import com.zobo.fogdrive.car.Gear
import com.zobo.fogdrive.car.Guidance
import com.zobo.fogdrive.car.LocationFeed
import com.zobo.fogdrive.car.Place
import com.zobo.fogdrive.car.TurnHint
import com.zobo.fogdrive.car.TurnKind
import com.zobo.fogdrive.car.VehicleState
import com.zobo.fogdrive.geo.Tiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Scripted drive for demos and the submission video: a weekend trip from Seongsu
 * past Seoul Forest to the Han river park, with rain, a rear passenger and a landing at the end.
 * It feeds the same recorder pipeline as real vehicle data, and stands in for the navigation's
 * guidance (next turn, speed limit) and destination arrival, which the emulator cannot produce.
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

    fun start(speedUp: Double = 6.0) {
        stop()
        job = scope.launch {
            var vehicle = VehicleState(gear = Gear.PARK, occupiedSeats = setOf("ROW_2_LEFT"), aaosConnected = true, pleosVehicleConnected = true)
            onVehicle(vehicle)
            onFix(fix(ROUTE.first()))
            // Parked before departure: the detour suggestion card is visible here.
            delay(6_000)
            vehicle = vehicle.copy(gear = Gear.DRIVE, drivingRestricted = true)
            onVehicle(vehicle)

            val points = densify(ROUTE, stepMeters = 12.0)
            val stepDelayMs = (12.0 / SPEED_MPS * 1000 / speedUp).toLong().coerceAtLeast(40)
            for ((i, p) in points.withIndex()) {
                if (!isActive) return@launch
                val progress = i.toDouble() / points.size
                onGuidance(guidanceAt(p.along, progress))
                val wiper = progress in 0.30..0.62
                if (wiper != vehicle.wiperOn) {
                    vehicle = vehicle.copy(wiperOn = wiper)
                    onVehicle(vehicle)
                }
                onFix(fix(p.latLng))
                delay(stepDelayMs)
            }
            onGuidance(null)
            onArrival(Arrival(ArrivalKind.DESTINATION, DESTINATION.name))
            delay(1_200)
            vehicle = vehicle.copy(gear = Gear.PARK, drivingRestricted = false, wiperOn = false)
            onVehicle(vehicle)
            delay(1_200)
            vehicle = vehicle.copy(doorsOpen = setOf("ROW_1_LEFT"))
            onVehicle(vehicle)
            delay(1_500)
            onVehicle(vehicle.copy(doorsOpen = emptySet()))
            onFinished()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun fix(p: Pair<Double, Double>) = Fix(p.first, p.second, System.currentTimeMillis(), 5f, LocationFeed.SOURCE_DEMO)

    private class RoutePoint(val latLng: Pair<Double, Double>, val along: Double)

    private fun densify(route: List<Pair<Double, Double>>, stepMeters: Double): List<RoutePoint> {
        val out = ArrayList<RoutePoint>()
        for (i in 0 until route.size - 1) {
            val (a, b) = route[i] to route[i + 1]
            val d = Tiles.distanceMeters(a.first, a.second, b.first, b.second)
            val n = kotlin.math.max(1, (d / stepMeters).toInt())
            for (k in 0 until n) {
                val t = k.toDouble() / n
                out += RoutePoint((a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t), ALONG[i] + d * t)
            }
        }
        out += RoutePoint(route.last(), ALONG.last())
        return out
    }

    /** Next turn ahead of [along] metres into the route; 30 km/h once inside the park roads. */
    private fun guidanceAt(along: Double, progress: Double): Guidance {
        val turn = TURNS.firstOrNull { it.first > along + 1 }
        val hint = if (turn != null) TurnHint(turn.second, (turn.first - along).toInt())
        else TurnHint(TurnKind.ARRIVE, (ALONG.last() - along).toInt())
        return Guidance(routing = true, speedLimitKmh = if (progress > 0.88) 30 else 50, nextTurn = hint)
    }

    companion object {
        private const val SPEED_MPS = 11.0

        /** Demo destination (a park along the river) used for the detour suggestion. */
        val DESTINATION = Place("뚝섬한강공원", 37.5296, 127.0669, "서울 광진구 강변북로 139")

        /** Real road path (OSRM, © OpenStreetMap contributors): 성수역 → 뚝섬역 → 서울숲 → 뚝섬유원지, ~5.2 km. */
        val ROUTE: List<Pair<Double, Double>> = listOf(
            37.54456 to 127.05588,
            37.54438 to 127.05648,
            37.54420 to 127.05709,
            37.54400 to 127.05775,
            37.54363 to 127.05899,
            37.54414 to 127.05778,
            37.54428 to 127.05730,
            37.54490 to 127.05524,
            37.54523 to 127.05412,
            37.54588 to 127.05196,
            37.54635 to 127.05037,
            37.54686 to 127.04873,
            37.54739 to 127.04709,
            37.54754 to 127.04662,
            37.54771 to 127.04603,
            37.54812 to 127.04469,
            37.54719 to 127.04454,
            37.54651 to 127.04464,
            37.54615 to 127.04468,
            37.54512 to 127.04481,
            37.54458 to 127.04485,
            37.54354 to 127.04462,
            37.54216 to 127.04424,
            37.54182 to 127.04436,
            37.54133 to 127.04587,
            37.54109 to 127.04663,
            37.54064 to 127.04808,
            37.54040 to 127.04891,
            37.54023 to 127.04961,
            37.54001 to 127.05045,
            37.53992 to 127.05084,
            37.53972 to 127.05168,
            37.53941 to 127.05294,
            37.53923 to 127.05358,
            37.53885 to 127.05488,
            37.53875 to 127.05527,
            37.53858 to 127.05591,
            37.53848 to 127.05630,
            37.53837 to 127.05675,
            37.53824 to 127.05724,
            37.53804 to 127.05801,
            37.53782 to 127.05882,
            37.53759 to 127.05969,
            37.53728 to 127.06089,
            37.53716 to 127.06130,
            37.53696 to 127.06206,
            37.53677 to 127.06281,
            37.53665 to 127.06324,
            37.53650 to 127.06381,
            37.53638 to 127.06425,
            37.53628 to 127.06464,
            37.53609 to 127.06542,
            37.53597 to 127.06588,
            37.53582 to 127.06647,
            37.53556 to 127.06749,
            37.53542 to 127.06798,
            37.53531 to 127.06839,
            37.53403 to 127.06781,
            37.53332 to 127.06748,
            37.53249 to 127.06711,
            37.53209 to 127.06695,
            37.53177 to 127.06680,
            37.53124 to 127.06655,
            37.53160 to 127.06681,
        )

        /** Distance along [ROUTE] at each of its points, in metres. */
        private val ALONG: List<Double> = ROUTE.indices.runningFold(0.0) { acc, i ->
            if (i == 0) acc else acc + Tiles.distanceMeters(ROUTE[i - 1].first, ROUTE[i - 1].second, ROUTE[i].first, ROUTE[i].second)
        }.drop(1)

        /** Route points where the heading swings by more than 35°, as (distance along, turn). */
        private val TURNS: List<Pair<Double, TurnKind>> = (1 until ROUTE.size - 1).mapNotNull { i ->
            var delta = bearing(ROUTE[i], ROUTE[i + 1]) - bearing(ROUTE[i - 1], ROUTE[i])
            if (delta > 180) delta -= 360
            if (delta < -180) delta += 360
            val kind = when {
                kotlin.math.abs(delta) > 150 -> TurnKind.UTURN
                delta > 35 -> TurnKind.RIGHT
                delta < -35 -> TurnKind.LEFT
                else -> null
            }
            kind?.let { ALONG[i] to it }
        }

        private fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val dy = b.first - a.first
            val dx = (b.second - a.second) * kotlin.math.cos(Math.toRadians(a.first))
            return Math.toDegrees(kotlin.math.atan2(dx, dy))
        }
    }
}
