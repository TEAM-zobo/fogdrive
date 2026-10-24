package com.zobo.fogdrive.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** One suggestion card on the map ("탐험 우회 경로", "안 가본 동네 가보기"): off in Settings, or hidden for today. */
    inner class CardPref(private val key: String) {
        private val _enabled = MutableStateFlow(prefs.getBoolean("${key}_on", true))
        val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
        private val _hiddenOn = MutableStateFlow(prefs.getString("${key}_hidden_on", null))
        /** Day (yyyyMMdd) the card was dismissed with "오늘 안 보기"; it comes back the next day. */
        val hiddenOn: StateFlow<String?> = _hiddenOn.asStateFlow()

        fun setEnabled(on: Boolean) {
            _enabled.value = on
            prefs.edit().putBoolean("${key}_on", on).apply()
        }

        fun hideToday() {
            _hiddenOn.value = today()
            prefs.edit().putString("${key}_hidden_on", _hiddenOn.value).apply()
        }
    }

    val detourCard = CardPref("detour_card")
    val suggestionCard = CardPref("suggestion_card")

    companion object {
        private const val KEY_WORN = "show_worn_roads"

        fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())
    }
}
