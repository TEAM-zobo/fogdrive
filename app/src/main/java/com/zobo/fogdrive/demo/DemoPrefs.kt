package com.zobo.fogdrive.demo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Demo course and speed picked in Settings; kept across restarts so a rehearsal stays set up. */
class DemoPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("fogdrive_demo", Context.MODE_PRIVATE)

    private val _courseId = MutableStateFlow(DemoCourses.byId(prefs.getString(KEY_COURSE, null)).id)
    val courseId: StateFlow<String> = _courseId.asStateFlow()

    private val _speedKmh = MutableStateFlow(prefs.getInt(KEY_SPEED, DemoDrive.DEFAULT_KMH))
    /** Read by the playing demo at every step, so changes apply mid-drive. */
    val speedKmh: StateFlow<Int> = _speedKmh.asStateFlow()

    private val _roundTrip = MutableStateFlow(prefs.getBoolean(KEY_ROUND_TRIP, false))
    /** Come back the same road after the drop-off (read on arrival, so it can change mid-drive). */
    val roundTrip: StateFlow<Boolean> = _roundTrip.asStateFlow()

    fun setRoundTrip(on: Boolean) {
        _roundTrip.value = on
        prefs.edit().putBoolean(KEY_ROUND_TRIP, on).apply()
    }

    fun setCourse(id: String) {
        _courseId.value = DemoCourses.byId(id).id
        prefs.edit().putString(KEY_COURSE, _courseId.value).apply()
    }

    fun setSpeed(kmh: Int) {
        val v = kmh.coerceIn(DemoDrive.MIN_KMH, DemoDrive.MAX_KMH)
        // The Settings slider calls this on every drag event; write only real changes.
        if (v == _speedKmh.value) return
        _speedKmh.value = v
        prefs.edit().putInt(KEY_SPEED, _speedKmh.value).apply()
    }

    private companion object {
        const val KEY_COURSE = "course"
        const val KEY_SPEED = "speed_kmh"
        const val KEY_ROUND_TRIP = "round_trip"
    }
}
