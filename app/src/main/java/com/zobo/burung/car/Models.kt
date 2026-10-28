package com.zobo.burung.car

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class DriveMode { NORMAL, ECO, SPORT, UNKNOWN }

/** Snapshot of the vehicle signals the game cares about. Unknown values stay null/false. */
data class VehicleState(
    val gear: Gear = Gear.UNKNOWN,
    /** True when AAOS UX restrictions say the car is moving (distraction optimization required). */
    val drivingRestricted: Boolean? = null,
    val doorsOpen: Set<String> = emptySet(),
    val occupiedSeats: Set<String> = emptySet(),
    val wiperOn: Boolean = false,
    val lowBeamOn: Boolean = false,
    val odometerKm: Float? = null,
    val evSocPercent: Int? = null,
    /** Side windows opened more than a crack (WindowValue names), and whether the sunroof is open. */
    val windowsOpen: Set<String> = emptySet(),
    val sunroofOpen: Boolean = false,
    val drivingMode: DriveMode = DriveMode.UNKNOWN,
    /** Distance the car can still drive on its battery or fuel, km. */
    val rangeKm: Float? = null,
    val pleosVehicleConnected: Boolean = false,
    val aaosConnected: Boolean = false,
) {
    val anyDoorOpen: Boolean get() = doorsOpen.isNotEmpty()
    val rearOccupied: Boolean get() = occupiedSeats.any { it.startsWith("ROW_2") || it.startsWith("ROW_3") }
    /** Anyone besides the driver (front passenger or rear). */
    val hasPassenger: Boolean get() = occupiedSeats.any { it != com.zobo.burung.record.TripRecorder.DRIVER_SEAT }

    /** Parked if the gear says P; without gear data, fall back to UX restrictions. */
    val parked: Boolean
        get() = when (gear) {
            Gear.PARK -> true
            Gear.UNKNOWN -> drivingRestricted == false
            else -> false
        }
}

data class Fix(
    val lat: Double,
    val lng: Double,
    val timeMs: Long,
    val accuracyM: Float? = null,
    val source: String,
)

data class Place(
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
)

enum class TurnKind { STRAIGHT, LEFT, RIGHT, KEEP_LEFT, KEEP_RIGHT, UTURN, ROUNDABOUT, ARRIVE }

data class TurnHint(val kind: TurnKind, val distanceM: Int)

/** What the built-in navigation is guiding right now; shown on the driving screen. */
data class Guidance(
    val routing: Boolean = false,
    val speedLimitKmh: Int? = null,
    val nextTurn: TurnHint? = null,
)

enum class ArrivalKind { DESTINATION, WAYPOINT, DETOUR }

/** [demo]: scripted by the demo drive (real arrivals are ignored while it plays, like real fixes). */
data class Arrival(val kind: ArrivalKind, val name: String, val demo: Boolean = false)
