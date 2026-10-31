package com.zobo.burung.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Display and guidance options shared by the map, the driving view and Settings. */
class MapPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("fogdrive_map", Context.MODE_PRIVATE)

    private val _onboardingDone = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING, false))
    /** The first-run tutorial was finished or skipped; Settings can show it again. */
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    fun setOnboardingDone(done: Boolean) {
        _onboardingDone.value = done
        prefs.edit().putBoolean(KEY_ONBOARDING, done).apply()
    }

    private val _voice = MutableStateFlow(prefs.getBoolean(KEY_VOICE, true))
    /** Spoken announcements (new 동, stamps, arrivals, trip summary). */
    val voice: StateFlow<Boolean> = _voice.asStateFlow()

    fun setVoice(on: Boolean) {
        _voice.value = on
        prefs.edit().putBoolean(KEY_VOICE, on).apply()
    }

    private val _sound = MutableStateFlow(prefs.getBoolean(KEY_SOUND, true))
    /** Interface sounds (taps, screen changes, switches, driving start/stop). */
    val sound: StateFlow<Boolean> = _sound.asStateFlow()

    fun setSound(on: Boolean) {
        _sound.value = on
        prefs.edit().putBoolean(KEY_SOUND, on).apply()
    }

    private val _soundVolume = MutableStateFlow(prefs.getInt(KEY_SOUND_VOLUME, 100))
    /** Interface sound volume, 0–100 % (of the car's system-sound volume). */
    val soundVolume: StateFlow<Int> = _soundVolume.asStateFlow()

    fun setSoundVolume(percent: Int) {
        val v = percent.coerceIn(0, 100)
        // The slider reports every drag frame: write only when the level actually changes.
        if (v == _soundVolume.value) return
        _soundVolume.value = v
        prefs.edit().putInt(KEY_SOUND_VOLUME, v).apply()
    }

    private val _milestones = MutableStateFlow(prefs.getStringSet(KEY_MILESTONES, emptySet())!!.toSet())
    /** 읍·면·동 exploration steps reached, as "code:percent" (see [MILESTONES]). */
    val milestones: StateFlow<Set<String>> = _milestones.asStateFlow()

    /** Highest step reached in the 동 [code], or 0. */
    fun milestoneOf(code: String, reached: Set<String> = _milestones.value): Int =
        MILESTONES.lastOrNull { "$code:$it" in reached } ?: 0

    /** Records the steps [percents] of [code]; returns those that are new. */
    fun reachMilestones(code: String, percents: List<Int>): List<Int> {
        val fresh = percents.filter { "$code:$it" !in _milestones.value }
        if (fresh.isEmpty()) return fresh
        val next = _milestones.value + fresh.map { "$code:$it" }
        _milestones.value = next
        prefs.edit().putStringSet(KEY_MILESTONES, next).apply()
        return fresh
    }

    private val _stories = MutableStateFlow(prefs.getBoolean(KEY_STORIES, true))
    /** "동네 이야기": landmark lines for 동 from Wikipedia (sends the 동's centre point, not the car's). */
    val stories: StateFlow<Boolean> = _stories.asStateFlow()

    fun setStories(on: Boolean) {
        _stories.value = on
        prefs.edit().putBoolean(KEY_STORIES, on).apply()
    }

    private val _dexHome = MutableStateFlow(prefs.getString(KEY_DEX_HOME, "") ?: "")
    /** The dex slide shown first: a 시·도 code, or "" for the whole country (the default). */
    val dexHome: StateFlow<String> = _dexHome.asStateFlow()

    /** Shows [code] first from now on, in place of whichever was first before. */
    fun setDexHome(code: String) {
        _dexHome.value = code
        prefs.edit().putString(KEY_DEX_HOME, code).apply()
    }

    private val _teamName = MutableStateFlow(prefs.getString(KEY_TEAM, "") ?: "")
    /** The 탐험대 name, set in Settings only; blank shows [DEFAULT_TEAM]. */
    val teamName: StateFlow<String> = _teamName.asStateFlow()

    fun setTeamName(name: String) {
        val v = name.take(TEAM_MAX)
        _teamName.value = v
        prefs.edit().putString(KEY_TEAM, v).apply()
    }

    private val _kids = MutableStateFlow(prefs.getBoolean(KEY_KIDS, false))
    /** 키즈모드: screen wording and spoken lines for a child on board; nothing else changes. */
    val kids: StateFlow<Boolean> = _kids.asStateFlow()

    fun setKids(on: Boolean) {
        _kids.value = on
        prefs.edit().putBoolean(KEY_KIDS, on).apply()
    }

    private val _title = MutableStateFlow(prefs.getString(KEY_TITLE, "") ?: "")
    /** Id of the title picked to show (see Titles); "" shows the hardest one earned. */
    val title: StateFlow<String> = _title.asStateFlow()

    fun setTitle(id: String) {
        _title.value = id
        prefs.edit().putString(KEY_TITLE, id).apply()
    }

    /** This week's suggested stamps as (week key, stamp names), kept so they stay put until Monday. */
    fun weeklyPicks(): Pair<Long, List<String>> =
        prefs.getLong(KEY_PICKS_WEEK2, 0) to (prefs.getString(KEY_PICKS, "") ?: "").split(',').filter { it.isNotEmpty() }

    fun setWeeklyPicks(week: Long, names: List<String>) {
        prefs.edit().putLong(KEY_PICKS_WEEK2, week).putString(KEY_PICKS, names.joinToString(",")).apply()
    }

    /**
     * 주말 탐험가 picked for a week that ended without it: after two such weeks in a row it is not
     * suggested for [WEEKEND_REST_WEEKS] weeks (some drivers just don't drive at weekends).
     */
    fun noteWeekendPick(earned: Boolean, week: Long) {
        val misses = if (earned) 0 else prefs.getInt(KEY_WEEKEND_MISSES, 0) + 1
        val e = prefs.edit()
        if (misses >= 2) {
            e.putLong(KEY_WEEKEND_REST_UNTIL, week + WEEKEND_REST_WEEKS).putInt(KEY_WEEKEND_MISSES, 0)
        } else {
            e.putInt(KEY_WEEKEND_MISSES, misses)
        }
        e.apply()
    }

    fun weekendPaused(week: Long): Boolean = week < prefs.getLong(KEY_WEEKEND_REST_UNTIL, 0)

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
        private const val KEY_ONBOARDING = "onboarding_done"
        private const val KEY_VOICE = "voice_on"
        private const val KEY_STORIES = "stories_on"
        private const val KEY_SOUND = "sound_on"
        private const val KEY_MILESTONES = "dong_milestones"
        /** Exploration steps celebrated per 읍·면·동, in percent of its cells. */
        val MILESTONES = listOf(10, 25, 50, 75, 100)
        private const val KEY_SOUND_VOLUME = "sound_volume"
        private const val KEY_DEX_HOME = "dex_home"
        private const val KEY_TEAM = "team_name"
        private const val KEY_KIDS = "kids_mode"
        private const val KEY_TITLE = "title_id"
        /** Monday-based week number (the earlier int key held a different week code). */
        private const val KEY_PICKS_WEEK2 = "weekly_picks_week3"
        private const val KEY_WEEKEND_MISSES = "weekend_misses"
        private const val KEY_WEEKEND_REST_UNTIL = "weekend_rest_until"
        private const val WEEKEND_REST_WEEKS = 4
        private const val KEY_PICKS = "weekly_picks"
        const val DEFAULT_TEAM = "부릉 탐험대"
        const val TEAM_MAX = 15

        fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())
    }
}
