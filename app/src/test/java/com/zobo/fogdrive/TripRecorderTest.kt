package com.zobo.fogdrive

import com.zobo.fogdrive.car.Fix
import com.zobo.fogdrive.car.Gear
import com.zobo.fogdrive.car.VehicleState
import com.zobo.fogdrive.record.ExploreSink
import com.zobo.fogdrive.record.RecorderEvent
import com.zobo.fogdrive.record.StampCode
import com.zobo.fogdrive.record.TripRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRecorderTest {
    private class MemorySink : ExploreSink {
        val cells = HashSet<Long>()
        override fun reveal(newCells: Collection<Long>, source: Int): Int = newCells.count { cells.add(it) }
        override fun isExplored(cell: Long): Boolean = cell in cells
    }

    private val drive = VehicleState(gear = Gear.DRIVE)
    private fun fix(lat: Double, lng: Double, t: Long = 0) = Fix(lat, lng, t, 5f, "test")

    @Test
    fun parkedFixesDoNotReveal() {
        val sink = MemorySink()
        val rec = TripRecorder(sink)
        rec.onFix(fix(37.5, 127.0), VehicleState(gear = Gear.PARK))
        assertTrue(sink.cells.isEmpty())
        assertFalse(rec.tripActive)
    }

    @Test
    fun drivingStartsTripAndRevealsSwath() {
        val sink = MemorySink()
        val rec = TripRecorder(sink)
        val events = rec.onFix(fix(37.5, 127.0), drive)
        assertTrue(events.first() is RecorderEvent.TripStarted)
        assertEquals(9, sink.cells.size) // 3x3 around the first fix
        rec.onFix(fix(37.5, 127.005), drive)
        assertTrue(rec.stats.distanceMeters > 400)
        assertEquals(rec.stats.distanceMeters, rec.stats.newDistanceMeters, 1e-6)
    }

    @Test
    fun denseFixesOnFreshRoadCountAsNewRoad() {
        val rec = TripRecorder(MemorySink())
        var lng = 127.0
        repeat(40) {
            rec.onFix(fix(37.5, lng), drive)
            lng += 0.0001 // ~9 m steps, well inside the revealed swath
        }
        assertEquals(rec.stats.distanceMeters, rec.stats.newDistanceMeters, 1e-6)
    }

    @Test
    fun secondTripOverSameRoadIsNotNew() {
        val sink = MemorySink()
        val first = TripRecorder(sink)
        first.onFix(fix(37.5, 127.0), drive)
        first.onFix(fix(37.5, 127.003), drive)
        val second = TripRecorder(sink)
        second.onFix(fix(37.5, 127.0), drive)
        second.onFix(fix(37.5, 127.003), drive)
        assertEquals(0.0, second.stats.newDistanceMeters, 1e-6)
    }

    @Test
    fun drivingAgainOverKnownRoadIsNotNew() {
        val sink = MemorySink()
        val rec = TripRecorder(sink)
        rec.onFix(fix(37.5, 127.0), drive)
        rec.onFix(fix(37.5, 127.005), drive)
        val before = sink.cells.size
        rec.onFix(fix(37.5, 127.0), drive)
        assertEquals(before, sink.cells.size)
        assertTrue(rec.stats.newDistanceMeters < rec.stats.distanceMeters)
    }

    @Test
    fun rainOnNewGroundAwardsStampOncePerTrip() {
        val rec = TripRecorder(MemorySink())
        val wet = drive.copy(wiperOn = true)
        val first = rec.onFix(fix(37.5, 127.0), wet)
        val second = rec.onFix(fix(37.5, 127.003), wet)
        assertEquals(1, first.filterIsInstance<RecorderEvent.Stamp>().count { it.code == StampCode.RAIN })
        assertTrue(second.none { it is RecorderEvent.Stamp })
    }

    @Test
    fun parkThenDoorOpenIsLanding() {
        val sink = MemorySink()
        val rec = TripRecorder(sink)
        rec.onFix(fix(37.5, 127.0), drive)
        val parked = VehicleState(gear = Gear.PARK)
        assertTrue(rec.onVehicle(parked, 1_000).isEmpty())
        val events = rec.onVehicle(parked.copy(doorsOpen = setOf("ROW_1_LEFT")), 2_000)
        assertTrue(events.any { it is RecorderEvent.Landing })
        assertTrue(events.any { it is RecorderEvent.TripEnded })
        assertTrue(sink.cells.size >= 49)
    }

    @Test
    fun doorOpenWhileDrivingIsNotLanding() {
        val rec = TripRecorder(MemorySink())
        rec.onFix(fix(37.5, 127.0), drive)
        val events = rec.onVehicle(drive.copy(doorsOpen = setOf("ROW_1_LEFT")), 1_000)
        assertTrue(events.none { it is RecorderEvent.Landing })
    }

    @Test
    fun chargeBaselineFollowsTheLowestParkedValue() {
        // Parked at 100%, battery drained to 40% (e.g. HVAC), then charged to 43%: still a charge.
        val rec = TripRecorder(MemorySink())
        rec.onFix(fix(37.5, 127.0), drive)
        val parked = VehicleState(gear = Gear.PARK, evSocPercent = 100)
        rec.onVehicle(parked, 0)
        rec.onVehicle(parked.copy(evSocPercent = 40), 1_000)
        val events = rec.onVehicle(parked.copy(evSocPercent = 43), 2_000)
        assertTrue(events.any { it is RecorderEvent.Stamp && it.code == StampCode.CHARGE })
    }

    @Test
    fun chargingWhileParkedAwardsChargeStamp() {
        val rec = TripRecorder(MemorySink())
        rec.onFix(fix(37.5, 127.0), drive)
        val parked = VehicleState(gear = Gear.PARK, evSocPercent = 40)
        rec.onVehicle(parked, 0)
        val events = rec.onVehicle(parked.copy(evSocPercent = 43), 60_000)
        assertTrue(events.any { it is RecorderEvent.Stamp && it.code == StampCode.CHARGE })
    }
}
