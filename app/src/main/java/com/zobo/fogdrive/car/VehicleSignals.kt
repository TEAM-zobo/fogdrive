package com.zobo.fogdrive.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Vehicle signals from two sources:
 * - Pleos Vehicle SDK: doors, seat occupancy, wiper, low beam, odometer, EV state of charge;
 * - AAOS standard APIs: gear (CarPropertyManager) and driving UX restrictions.
 * Every call is guarded: an unsupported signal simply stays at its default.
 * Demo mode can override the whole state to replay a scripted drive.
 */
class VehicleSignals(private val context: Context) {
    private val real = MutableStateFlow(VehicleState())
    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    private var demoOverride: VehicleState? = null
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private val releasers = mutableListOf<() -> Unit>()

    fun start() {
        if (vehicle != null || car != null) return
        startPleos()
        startAaos()
    }

    fun stop() {
        releasers.forEach { runCatching(it) }
        releasers.clear()
        runCatching { vehicle?.release() }
        vehicle = null
        runCatching { uxManager?.unregisterListener() }
        runCatching { car?.disconnect() }
        car = null
        propertyManager = null
        uxManager = null
        real.value = VehicleState()
        publish()
    }

    fun setDemoOverride(state: VehicleState?) {
        demoOverride = state
        publish()
    }

    private fun publish() {
        _state.value = demoOverride ?: real.value
    }

    private fun edit(transform: (VehicleState) -> VehicleState) {
        real.update(transform)
        publish()
    }

    private fun startPleos() {
        val v = try {
            Vehicle(context.applicationContext).also { it.initialize() }
        } catch (e: Throwable) {
            Log.w(TAG, "Pleos Vehicle SDK unavailable", e)
            return
        }
        vehicle = v
        edit { it.copy(pleosVehicleConnected = true) }

        guard("door") {
            val door = v.getDoor()
            for (value in listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)) {
                val area = DoorArea(value)
                val name = value.name
                val listener = object : DoorPositionListener {
                    override fun onPositionUpdated(pos: Int?) = setDoor(name, (pos ?: 0) > 0)
                    override fun onFailed(e: Exception) = logFail("door $name", e)
                }
                door.registerDoorPosition(area, listener)
                releasers += { door.unregisterDoorPosition(area, listener) }
                door.getCurrentDoorPosition(area, { pos -> setDoor(name, (pos ?: 0) > 0) }, { logFail("door $name", it) })
            }
        }
        guard("seat") {
            val seat = v.getSeat()
            // Rear outboard seats only: child seats (ISOFIX) sit there, and the center seat cannot be
            // driven from the emulator's Virtual Console.
            for (value in listOf(SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)) {
                val area = SeatArea(value)
                val name = value.name
                val listener = object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(occupied: Boolean?) = setSeat(name, occupied == true)
                    override fun onFailed(e: Exception) = logFail("seat $name", e)
                }
                seat.registerSeatOccupancy(area, listener)
                releasers += { seat.unregisterSeatOccupancy(area, listener) }
                seat.getCurrentSeatOccupancy(area, { setSeat(name, it == true) }, { logFail("seat $name", it) })
            }
        }
        guard("wiper") {
            val wiper = v.getWiper()
            val area = WindowArea(WindowValue.FRONT_WINDSHIELD)
            val listener = object : WiperStateListener {
                override fun onWiperStateUpdated(state: WiperState?) = edit { it.copy(wiperOn = state == WiperState.ON) }
                override fun onFailed(e: Exception) = logFail("wiper", e)
            }
            wiper.registerWiperState(area, listener)
            releasers += { wiper.unregisterWiperState(area, listener) }
            wiper.getCurrentWiperState(area, { s -> edit { it.copy(wiperOn = s == WiperState.ON) } }, { logFail("wiper", it) })
        }
        guard("light") {
            val light = v.getLight()
            val listener = object : LightStateListener {
                override fun onLightStateUpdated(state: LightState?) = edit { it.copy(lowBeamOn = state == LightState.ON) }
                override fun onFailed(e: Exception) = logFail("low beam", e)
            }
            light.registerLowBeamLightState(listener)
            releasers += { light.unregisterLowBeamLightState(listener) }
            light.getCurrentLowBeamLightState({ s -> edit { it.copy(lowBeamOn = s == LightState.ON) } }, { logFail("low beam", it) })
        }
        guard("odometer") {
            val odometer = v.getOdometer()
            val listener = object : DistanceDrivenListener {
                override fun onDistanceDrivenUpdated(km: Float?) = edit { it.copy(odometerKm = km) }
                override fun onFailed(e: Exception) = logFail("odometer", e)
            }
            odometer.registerDistanceDriven(listener)
            releasers += { odometer.unregisterDistanceDriven(listener) }
            odometer.getCurrentDistanceDriven({ km -> edit { it.copy(odometerKm = km) } }, { logFail("odometer", it) })
        }
        guard("ev") {
            val ev = v.getEvBattery()
            val listener = object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(soc: Int?) = edit { it.copy(evSocPercent = soc) }
                override fun onFailed(e: Exception) = logFail("ev soc", e)
            }
            ev.registerEvBatteryStateOfCharge(listener)
            releasers += { ev.unregisterEvBatteryStateOfCharge(listener) }
            ev.getEvBatteryStateOfCharge({ soc -> edit { it.copy(evSocPercent = soc) } }, { logFail("ev soc", it) })
        }
    }

    private fun startAaos() {
        val c = try {
            Car.createCar(context.applicationContext)
        } catch (e: Throwable) {
            Log.w(TAG, "android.car unavailable", e)
            null
        } ?: return
        car = c
        edit { it.copy(aaosConnected = true) }
        guard("gear") {
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            propertyManager = pm
            val callback = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    val gear = (value.value as? Int)?.let(::mapGear) ?: Gear.UNKNOWN
                    edit { it.copy(gear = gear) }
                }

                override fun onErrorEvent(propertyId: Int, zone: Int) {
                    Log.w(TAG, "gear property error $propertyId")
                }
            }
            pm.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            releasers += { pm.unregisterCallback(callback) }
        }
        guard("ux") {
            val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxManager = ux
            ux.registerListener { r -> edit { it.copy(drivingRestricted = r.isRequiresDistractionOptimization) } }
            ux.currentCarUxRestrictions?.let { r -> edit { it.copy(drivingRestricted = r.isRequiresDistractionOptimization) } }
        }
    }

    private fun setDoor(name: String, open: Boolean) =
        edit { it.copy(doorsOpen = if (open) it.doorsOpen + name else it.doorsOpen - name) }

    private fun setSeat(name: String, occupied: Boolean) {
        Log.d(TAG, "seat $name occupied=$occupied")
        edit { it.copy(occupiedSeats = if (occupied) it.occupiedSeats + name else it.occupiedSeats - name) }
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Log.w(TAG, "$what signal unavailable", e)
        }
    }

    private fun logFail(what: String, e: Exception) {
        Log.w(TAG, "$what failed: ${e.message}")
    }

    private fun mapGear(value: Int): Gear = when (value) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE
    }

    private companion object {
        const val TAG = "FogVehicle"
    }
}
