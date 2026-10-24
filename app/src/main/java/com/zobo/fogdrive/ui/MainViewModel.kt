package com.zobo.fogdrive.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zobo.fogdrive.FogRuntime
import com.zobo.fogdrive.data.LandingEntity
import com.zobo.fogdrive.data.StampEntity
import com.zobo.fogdrive.data.TripEntity
import com.zobo.fogdrive.explore.DetourPlanner
import com.zobo.fogdrive.explore.DistrictProgress
import com.zobo.fogdrive.explore.DongProgress
import com.zobo.fogdrive.explore.Progress
import com.zobo.fogdrive.explore.ProvinceProgress
import com.zobo.fogdrive.explore.SeasonGoal
import com.zobo.fogdrive.geo.Area
import com.zobo.fogdrive.geo.TileBox
import com.zobo.fogdrive.geo.Tiles
import com.zobo.fogdrive.record.StampCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

data class Summary(
    val totalCells: Int = 0,
    val areaKm2: Double = 0.0,
    val xp: Int = 0,
    val level: Int = 1,
    val levelProgress: Float = 0f,
    val landings: Int = 0,
    val stampKinds: Int = 0,
    val newRoadRatio: Double = 0.0,
)

/** Where the car is in the region dex, with each level's achievement rate. */
data class RegionHere(val province: ProvinceProgress, val district: DistrictProgress, val dong: DongProgress)

class MainViewModel : ViewModel() {
    private val repo = FogRuntime.repo
    val live = FogRuntime.live
    val vehicle = FogRuntime.vehicle.state
    val navi = FogRuntime.navi
    val version: StateFlow<Int> = repo.version

    val trips: StateFlow<List<TripEntity>> = repo.trips.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val landings: StateFlow<List<LandingEntity>> = repo.landings.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val stamps: StateFlow<List<StampEntity>> = repo.stamps.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val monthCells: StateFlow<Int> = repo.cellsSince(monthStart()).stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val summary: StateFlow<Summary> = combine(version, landings, stamps, trips, live) { _, l, s, t, lv ->
        val cells = repo.count()
        val lat = lv.location?.lat ?: 37.5
        val cellKm = Tiles.cellSizeMeters(lat) / 1000.0
        val xp = Progress.xp(cells, l.size, s.size)
        val dist = t.sumOf { it.distanceMeters }
        val newDist = t.sumOf { it.newDistanceMeters }
        Summary(
            totalCells = cells,
            areaKm2 = cells * cellKm * cellKm,
            xp = xp,
            level = Progress.level(xp),
            levelProgress = Progress.levelProgress(xp),
            landings = l.size,
            stampKinds = s.map { it.code }.distinct().size,
            newRoadRatio = if (dist > 0) newDist / dist else 0.0,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, Summary())

    val season: StateFlow<List<SeasonGoal>> = combine(monthCells, landings, stamps) { cells, l, s ->
        val since = monthStart()
        listOf(
            SeasonGoal("이번 달 0.3 km² 밝히기", Area.ofCells(cells).toInt(), 300_000, Area::formatInt),
            SeasonGoal("이번 달 상륙 지점 3곳", l.count { it.at >= since }, 3),
            SeasonGoal("이번 달 스탬프 2종 모으기", s.filter { it.at >= since }.map { it.code }.distinct().size, 2),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Region dex; computed only while a screen shows it, null until the atlas is loaded. */
    val regions: StateFlow<List<ProvinceProgress>?> = version
        .map { FogRuntime.regions.tally(repo.cellsAtLevel(0)) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The 시·도 / 시·군·구 / 읍·면·동 under the car (null off the map of Korea or before the first fix). */
    val here: StateFlow<RegionHere?> = combine(
        regions,
        live.map { it.location?.let { f -> Tiles.cellOf(f.lat, f.lng) } }.distinctUntilChanged(),
    ) { r, cell ->
        if (r == null || cell == null) return@combine null
        val atlas = FogRuntime.regions.atlas
        val i = atlas.dongOf(cell).takeIf { it >= 0 } ?: return@combine null
        val dong = atlas.dongs[i]
        val district = atlas.districts[dong.district]
        val province = r.first { it.code == atlas.provinces[district.province].code }
        val d = province.districts.first { it.code == district.code }
        RegionHere(province, d, d.dongs.first { it.code == dong.code })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 읍·면·동 entered for the first time on the current trip (all their cleared cells are from it). */
    val tripNewDongs: StateFlow<Int> = combine(version, live.map { it.tripActive }.distinctUntilChanged()) { _, active ->
        val trip = repo.currentTripId
        if (!active || trip == 0L) return@combine 0
        val tally = FogRuntime.regions
        tally.firstEntered(repo.cellsOfTrip(trip), tally.levels(repo.cellsAtLevel(0)))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Region the map frames next (picked in the dex); the map clears it once shown. */
    val mapFocus = MutableStateFlow<TileBox?>(null)

    /** Dex drill-down, kept here so it survives the jump to the map tab and back. */
    val dexProvince = MutableStateFlow<String?>(null)
    val dexDistrict = MutableStateFlow<String?>(null)

    val detour: StateFlow<DetourPlanner.Suggestion?> = combine(
        live.map { it.location?.let { f -> f.lat to f.lng } }.distinctUntilChanged(),
        navi.destination,
        version,
    ) { origin, dest, _ ->
        if (origin == null || dest == null) null
        else DetourPlanner.plan(origin.first, origin.second, dest.lat, dest.lng, repo::isExplored)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun stampCounts(): Map<StampCode, Int> =
        StampCode.entries.associateWith { code -> stamps.value.count { it.code == code.name } }

    fun acceptDetour(s: DetourPlanner.Suggestion): Boolean =
        navi.addDetourWaypoint(s.lat, s.lng)

    fun resetAll() {
        viewModelScope.launch { repo.reset() }
    }

    companion object {
        fun monthStart(): Long = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
