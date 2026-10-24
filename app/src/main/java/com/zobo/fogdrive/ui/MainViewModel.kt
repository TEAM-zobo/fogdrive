package com.zobo.fogdrive.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zobo.fogdrive.FogRuntime
import com.zobo.fogdrive.data.LandingEntity
import com.zobo.fogdrive.data.StampEntity
import com.zobo.fogdrive.data.TripEntity
import com.zobo.fogdrive.explore.DetourPlanner
import com.zobo.fogdrive.explore.Progress
import com.zobo.fogdrive.explore.SeasonGoal
import com.zobo.fogdrive.geo.Tiles
import com.zobo.fogdrive.record.StampCode
import kotlinx.coroutines.Dispatchers
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
    val zoneRate: Double? = null,
)

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
        val zone = lv.location?.let { fix ->
            val parent = Tiles.parent(Tiles.cellOf(fix.lat, fix.lng), ZONE_DZ)
            repo.exploredIn(parent, ZONE_DZ) * 100.0 / ((1 shl ZONE_DZ) * (1 shl ZONE_DZ))
        }
        Summary(
            totalCells = cells,
            areaKm2 = cells * cellKm * cellKm,
            xp = xp,
            level = Progress.level(xp),
            levelProgress = Progress.levelProgress(xp),
            landings = l.size,
            stampKinds = s.map { it.code }.distinct().size,
            newRoadRatio = if (dist > 0) newDist / dist else 0.0,
            zoneRate = zone,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, Summary())

    val season: StateFlow<List<SeasonGoal>> = combine(monthCells, landings, stamps) { cells, l, s ->
        val since = monthStart()
        listOf(
            SeasonGoal("이번 달 새 칸 300개 개척", cells, 300),
            SeasonGoal("이번 달 상륙 지점 3곳", l.count { it.at >= since }, 3),
            SeasonGoal("이번 달 스탬프 2종 모으기", s.filter { it.at >= since }.map { it.code }.distinct().size, 2),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        navi.addDetourWaypoint(s.lat, s.lng, "안개 구역 탐험")

    fun resetAll() {
        viewModelScope.launch { repo.reset() }
    }

    companion object {
        /** A "zone" for the live conquest rate = parent tile 6 levels up (~2 km square). */
        const val ZONE_DZ = 6

        fun monthStart(): Long = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
