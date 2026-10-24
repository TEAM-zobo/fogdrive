package com.zobo.fogdrive.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Map display options shared by the map, the driving view and Settings. */
class MapPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("fogdrive_map", Context.MODE_PRIVATE)

    private val _showWornRoads = MutableStateFlow(prefs.getBoolean(KEY_WORN, true))
    /** Tint roads driven on several trips ("자주 다닌 길"). */
    val showWornRoads: StateFlow<Boolean> = _showWornRoads.asStateFlow()

    fun setShowWornRoads(on: Boolean) {
        _showWornRoads.value = on
        prefs.edit().putBoolean(KEY_WORN, on).apply()
    }

    private companion object {
        const val KEY_WORN = "show_worn_roads"
    }
}
