package com.zobo.burung.ui

import kotlinx.coroutines.flow.filterNotNull
import com.zobo.burung.voice.Sound
import com.zobo.burung.voice.Sfx
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zobo.burung.FogRuntime
import com.zobo.burung.data.LandingEntity
import com.zobo.burung.data.StampEntity
import com.zobo.burung.data.TripEntity
import com.zobo.burung.explore.DetourPlanner
import com.zobo.burung.explore.Progress
import com.zobo.burung.explore.ProvinceProgress
import com.zobo.burung.explore.RegionCounts
import com.zobo.burung.explore.RegionStat
import com.zobo.burung.explore.WeeklyPicks
import com.zobo.burung.explore.ChallengePeriod
import com.zobo.burung.explore.PeriodStats
import com.zobo.burung.geo.Area
import com.zobo.burung.geo.TileBox
import com.zobo.burung.geo.Tiles
import com.zobo.burung.record.StampCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/** A suggested stamp of the week, whether it was collected since, and where to go for it. */
data class WeeklyPick(val code: StampCode, val done: Boolean, val place: DexNext?)

/** A dex slide's story cards: where it was explored last, and where to go next. */
data class DexStory(val recent: DexRecent?, val next: DexNext?)

/** The 동 explored most recently in a slide's area, when, and (on a 시·도 slide) its 시·군·구. */
data class DexRecent(val name: String, val at: Long, val district: String? = null)

/** The nearest region not entered yet, [km] from home ([fromHome]) or else the car. */
data class DexNext(val region: RegionStat, val km: Double, val fromHome: Boolean = true)

/** A past trip shown on the map: the recorded route, or for trips before routes were kept, its cells. */
data class TripRoute(val trip: TripEntity, val points: List<Pair<Double, Double>>, val isTrack: Boolean)

/** Where the car is in the region dex, with each level's achievement rate. */
data class RegionHere(
    val province: NamedRate,
    val district: NamedRate,
    val dong: NamedRate,
    /** The 동's code and label point, for its "동네 이야기". */
    val dongCode: String,
    val dongLat: Double,
    val dongLng: Double,
)

data class NamedRate(val name: String, val rate: Double)

/** An unexplored 읍·면·동 near the car, suggested on the map. */
data class ExploreSuggestion(
    val name: String,
    val district: String,
    val lat: Double,
    val lng: Double,
    val distanceM: Double,
    val bounds: TileBox,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel : ViewModel() {
    private val repo = FogRuntime.repo
    val live = FogRuntime.live
    val vehicle = FogRuntime.vehicle.state
    val navi = FogRuntime.navi
    val version: StateFlow<Int> = repo.version

    val trips: StateFlow<List<TripEntity>> = repo.trips.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val landings: StateFlow<List<LandingEntity>> = repo.landings.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val stamps: StateFlow<List<StampEntity>> = repo.stamps.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val summary: StateFlow<Summary> = combine(
        version, landings, stamps, trips,
        // Cell size only depends on latitude; 0.1° steps keep the summary from being recomputed on
        // every fix (the whole live status changes several times a second while driving).
        live.map { it.location?.lat?.let { lat -> Math.round(lat * 10) / 10.0 } ?: 37.5 }.distinctUntilChanged(),
    ) { _, l, s, t, lat ->
        val cells = repo.count()
        val cellKm = Tiles.cellSizeMeters(lat) / 1000.0
        // Distance from the database, as the arrival card's level-up ([ExploreRepository.xp]) reads it:
        // [t] stops at the last 100 trips. Landings and stamps are all in [l] and [s] already.
        val kinds = s.map { it.code }.distinct().size
        val xp = Progress.xp(cells, l.size, s.size, kinds, repo.tripTotalsSince(0).distanceMeters)
        val dist = t.sumOf { it.distanceMeters }
        val newDist = t.sumOf { it.newDistanceMeters }
        Summary(
            totalCells = cells,
            areaKm2 = cells * cellKm * cellKm,
            xp = xp,
            level = Progress.level(xp),
            levelProgress = Progress.levelProgress(xp),
            landings = l.size,
            stampKinds = kinds,
            newRoadRatio = if (dist > 0) newDist / dist else 0.0,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, Summary())

    /**
     * This week and this month next to the one before (record screen): area cleared, 읍·면·동 first
     * entered, distance and trips. Read once per change from the start of the earlier window.
     */
    val reports: StateFlow<List<PeriodReport>> = combine(version, trips) { _, _ ->
        val now = System.currentTimeMillis()
        val tally = FogRuntime.regions
        val counts = tally?.levels(repo)
        val periods = listOf(ChallengePeriod.WEEK, ChallengePeriod.MONTH)
        val starts = periods.associateWith { it.start(now) }
        val before = periods.associateWith { it.start(starts.getValue(it) - 1) }
        val cleared = repo.clearedSince(before.values.min())
        suspend fun window(from: Long): PeriodStats {
            val cells = cleared.mapNotNull { c -> c.cell.takeIf { c.firstSeenAt >= from } }
            // Every 읍·면·동 first entered since [from], counted against today's totals.
            val dongs = if (tally != null && counts != null) tally.firstEnteredLevels(cells, counts).first else 0
            val totals = repo.tripTotalsSince(from)
            return PeriodStats(areaM2 = Area.ofCells(cells.size).toInt(), newDongs = dongs, trips = totals.count, distanceM = totals.distanceMeters.toInt())
        }
        periods.map { period ->
            val current = window(starts.getValue(period))
            val since = window(before.getValue(period))
            // The earlier window is everything since its start minus this window.
            val previous = PeriodStats(
                areaM2 = since.areaM2 - current.areaM2,
                newDongs = since.newDongs - current.newDongs,
                trips = since.trips - current.trips,
                distanceM = since.distanceM - current.distanceM,
            )
            PeriodReport(period, current, previous)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Region dex; computed only while a screen shows it. Null until loaded, empty if the atlas failed. */
    val regions: StateFlow<List<ProvinceProgress>?> = combine(version, landings) { _, l ->
        FogRuntime.regions?.tally(repo, l.map { Tiles.cellOf(it.lat, it.lng) }) ?: emptyList()
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The dex slides' two story cards, per slide ("" = the whole country, else a 시·도 code): the 동
     * explored most recently there, and the nearest 시·도 / 시·군·구 not entered yet, measured from the
     * navigation's home (else the car).
     */
    val dexStories: StateFlow<Map<String, DexStory>> = combine(
        regions,
        navi.home,
        live.map { it.location?.let { f -> Math.round(f.lat * 100) / 100.0 to Math.round(f.lng * 100) / 100.0 } }.distinctUntilChanged(),
    ) { r, home, here ->
        val atlas = FogRuntime.regions?.atlas
        if (r.isNullOrEmpty() || atlas == null) return@combine emptyMap()
        val recent = HashMap<String, DexRecent>()
        for (c in repo.newestCleared(RECENT_CELLS)) {
            val d = atlas.dongOf(c.cell).takeIf { it >= 0 } ?: continue
            val dong = atlas.dongs[d]
            val district = atlas.districts[dong.district]
            val province = atlas.provinces[district.province]
            recent.putIfAbsent("", DexRecent("${district.name} ${dong.name}", c.firstSeenAt))
            recent.putIfAbsent(province.code, DexRecent(dong.name, c.firstSeenAt, district.name))
        }
        val origin = home?.let { it.lat to it.lng } ?: here
        fun nearest(candidates: List<Pair<RegionStat, com.zobo.burung.geo.Outline?>>): DexNext? {
            if (origin == null) return null
            return candidates.mapNotNull { (stat, o) ->
                o ?: return@mapNotNull null
                val km = Tiles.distanceMeters(origin.first, origin.second, Tiles.yToLat(o.labelY + 0.5), Tiles.xToLng(o.labelX + 0.5)) / 1000
                DexNext(stat, km, fromHome = home != null)
            }.minByOrNull { it.km }
        }
        buildMap {
            put("", DexStory(recent[""], nearest(r.filter { !it.visited }.map { p -> p to atlas.provinces.firstOrNull { it.code == p.code }?.outline })))
            val outlines = atlas.districts.associateBy({ it.code }, { it.outline })
            r.forEach { p ->
                put(p.code, DexStory(recent[p.code], nearest(p.districts.filter { !it.visited }.map { it to outlines[it.code] })))
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Explored cells per region level as int arrays: cheap enough for the map and driving headers to
     * follow every fix, unlike the dex's full [regions] tree.
     */
    private val regionCounts: StateFlow<RegionCounts?> = version
        .map { FogRuntime.regions?.levels(repo) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The 시·도 / 시·군·구 / 읍·면·동 under the car (null off the map of Korea or before the first fix). */
    val here: StateFlow<RegionHere?> = combine(
        regionCounts,
        live.map { it.location?.let { f -> Tiles.cellOf(f.lat, f.lng) } }.distinctUntilChanged(),
    ) { n, cell ->
        if (n == null || cell == null) return@combine null
        val atlas = n.atlas
        val i = atlas.dongOf(cell).takeIf { it >= 0 } ?: return@combine null
        val dong = atlas.dongs[i]
        val d = dong.district
        val p = atlas.districts[d].province
        RegionHere(
            NamedRate(atlas.provinces[p].name, n.provinceRate(p)),
            NamedRate(atlas.districts[d].name, n.districtRate(d)),
            NamedRate(dong.name, n.dongRate(i)),
            dong.code,
            Tiles.yToLat(dong.outline.labelY + 0.5),
            Tiles.xToLng(dong.outline.labelX + 0.5),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 읍·면·동 exploration steps reached (dex badges). */
    val milestones = FogRuntime.mapPrefs.milestones

    /** 동 met this session: steps they had already reached when first seen are recorded silently. */
    private val milestoneBaseline = HashSet<String>()

    init {
        // A 동 passing 10 / 25 / 50 / 75 / 100 % while driving through it: a notice, a sparkle and a
        // spoken line. Only steps crossed now count, not those reached before this session.
        viewModelScope.launch {
            here.filterNotNull()
                .distinctUntilChanged { a, b -> a.dongCode == b.dongCode && a.dong.rate == b.dong.rate }
                .collect { h ->
                    // Marked on first sight even below the first step, so a 동 entered at 0 % that then
                    // crosses 10 % is celebrated instead of being taken for the baseline.
                    val firstSight = milestoneBaseline.add(h.dongCode)
                    val percent = h.dong.rate * 100
                    val reached = MapPrefs.MILESTONES.filter { percent >= it }
                    if (reached.isEmpty()) return@collect
                    val fresh = FogRuntime.mapPrefs.reachMilestones(h.dongCode, reached)
                    if (firstSight) return@collect
                    // 10 % is passed in almost every new 동 (every few seconds in a demo): recorded for
                    // the dex badge only. From 25 % a notice and a sparkle, and only from 50 % a spoken
                    // line, queued like any other so it doesn't crowd out first visits and stamps.
                    val top = fresh.maxOrNull()?.takeIf { it >= MILESTONE_NOTICE } ?: return@collect
                    FogRuntime.live.update { it.copy(lastEvent = "${h.dong.name} ${top}% 개척!", lastEventId = System.nanoTime()) }
                    Sfx.play(Sound.ACHIEVE, byControl = false)
                    if (top >= MILESTONE_VOICE) {
                        FogRuntime.announcer.say(if (FogRuntime.mapPrefs.kids.value) "${h.dong.name} 탐험 ${top}퍼센트 성공!" else "${h.dong.name} ${top}퍼센트 개척")
                    }
                }
        }
    }

    private val suggestionSkip = MutableStateFlow(0)

    /**
     * The nearest 읍·면·동 never entered (cycled by [nextSuggestion]), for the map's "go explore"
     * card. Follows the car in ~1 km steps so the card doesn't change on every fix.
     */
    val suggestion: StateFlow<ExploreSuggestion?> = combine(
        // Only a newly entered 동 changes the candidates, not every revealed cell.
        regionCounts.distinctUntilChangedBy { it?.visitedDongs },
        live.map { it.location?.let { f -> Tiles.parent(Tiles.cellOf(f.lat, f.lng), 5) } }.distinctUntilChanged(),
        suggestionSkip,
    ) { n, area, skip ->
        if (n == null || area == null) return@combine null
        val atlas = n.atlas
        val lat = Tiles.yToLat(Tiles.y(area) * 32.0 + 16)
        val lng = Tiles.xToLng(Tiles.x(area) * 32.0 + 16)
        val near = atlas.dongs.indices.asSequence()
            .filter { n.dong[it] == 0 }
            .map { i ->
                val o = atlas.dongs[i].outline
                val p = Tiles.yToLat(o.labelY + 0.5) to Tiles.xToLng(o.labelX + 0.5)
                Triple(i, p, Tiles.distanceMeters(lat, lng, p.first, p.second))
            }
            .filter { it.third < SUGGEST_MAX_M }
            .sortedBy { it.third }
            .take(SUGGEST_CHOICES)
            .toList()
        if (near.isEmpty()) return@combine null
        val (i, p, d) = near[skip % near.size]
        val dong = atlas.dongs[i]
        ExploreSuggestion(dong.name, atlas.districts[dong.district].name, p.first, p.second, d, dong.outline.box)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun nextSuggestion() {
        suggestionSkip.value++
    }

    fun routeToSuggestion(s: ExploreSuggestion): Boolean = navi.routeTo(s.lat, s.lng, "${s.district} ${s.name}")

    /** 읍·면·동 entered for the first time on the current trip (all their cleared cells are from it). */
    val tripNewDongs: StateFlow<Int> = combine(version, live.map { it.tripActive }.distinctUntilChanged()) { _, active ->
        val trip = repo.currentTripId
        if (!active || trip == 0L) return@combine 0
        val tally = FogRuntime.regions ?: return@combine 0
        tally.firstEntered(repo.cellsOfTrip(trip), tally.levels(repo))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Region the map frames next (picked in the dex); the map clears it once shown. */
    val mapFocus = MutableStateFlow<TileBox?>(null)

    /** Trip picked on the record screen, drawn on the map until closed. */
    val shownTrip = MutableStateFlow<TripRoute?>(null)

    private var showTripJob: Job? = null

    /**
     * This week's three suggested stamps (see WeeklyPicks): picked on the first look of the week and
     * kept until Monday, each marked once collected. A place to go is attached where one fits: the
     * nearest 시·도 not entered yet for 새 땅 밟기, the nearest 시·군·구 for 새 동네 첫발.
     */
    val weeklyPicks: StateFlow<List<WeeklyPick>> = combine(stamps, dexStories) { s, stories ->
        val collected = s.mapNotNullTo(HashSet()) { st -> StampCode.entries.firstOrNull { it.name == st.code } }
        val week = WeeklyPicks.weekKey(System.currentTimeMillis())
        val prefs = FogRuntime.mapPrefs
        val (savedWeek, saved) = prefs.weeklyPicks()
        val codes = saved.mapNotNull { n -> StampCode.entries.firstOrNull { it.name == n } }
            .takeIf { savedWeek == week && it.isNotEmpty() }
            ?: WeeklyPicks.pick(collected, week, listOf(StampCode.NEW_DISTRICT, StampCode.WEEKEND))
                .also { prefs.setWeeklyPicks(week, it.map { c -> c.name }) }
        val province = stories[""]?.next
        val district = stories.filterKeys { it.isNotEmpty() }.values.mapNotNull { it.next }.minByOrNull { it.km }
        codes.map { c ->
            WeeklyPick(c, c in collected, when (c) {
                StampCode.NEW_PROVINCE -> province
                StampCode.NEW_DISTRICT -> district
                else -> null
            })
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * "집 → 구산동" per trip: where its route starts and ends, as 집 within [HOME_NEAR_M] of the
     * navigation's home, else the 읍·면·동 there. Trips without a route have no name.
     */
    val tripNames: StateFlow<Map<Long, String>> = combine(trips, navi.home, FogRuntime.regionsLoaded, version) { t, home, _, _ ->
        val atlas = FogRuntime.regions?.atlas
        fun place(x: Double, y: Double): String? {
            val lat = Tiles.yToLat(y)
            val lng = Tiles.xToLng(x)
            if (home != null && Tiles.distanceMeters(lat, lng, home.lat, home.lng) < HOME_NEAR_M) return "집"
            return atlas?.dongOf(Tiles.cellOf(lat, lng))?.takeIf { it >= 0 }?.let { atlas.dongs[it].name }
        }
        t.mapNotNull { trip ->
            val e = repo.tracks.ends(trip.id) ?: return@mapNotNull null
            val from = place(e[0], e[1]) ?: return@mapNotNull null
            val to = place(e[2], e[3]) ?: return@mapNotNull null
            trip.id to if (from == to) "$from 주변" else "$from → $to"
        }.toMap()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Stamps earned on each trip, by trip id, in the order earned. */
    val tripStamps: StateFlow<Map<Long, List<StampCode>>> = stamps.map { s ->
        s.sortedBy { it.at }.groupBy({ it.tripId }, { st -> StampCode.entries.firstOrNull { it.name == st.code } })
            .mapValues { (_, v) -> v.filterNotNull().distinct() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Shows [trip]'s route on the map. [leftInset]: share of the map's width covered on the left
     * (the trips panel), so the route is framed in the part still visible.
     */
    fun showTrip(trip: TripEntity, leftInset: Double = 0.0) {
        // A later pick wins even if an earlier one's query finishes after it.
        showTripJob?.cancel()
        showTripJob = viewModelScope.launch {
            val track = repo.trackOf(trip.id)
            val route = if (track.isNotEmpty()) {
                TripRoute(trip, track.map { it.lat to it.lng }, isTrack = true)
            } else {
                val cells = repo.storedCellsOfTrip(trip.id)
                TripRoute(trip, cells.map { Tiles.centerLat(it) to Tiles.centerLng(it) }, isTrack = false)
            }
            shownTrip.value = route
            if (route.points.isNotEmpty()) {
                val xs = route.points.map { Tiles.lngToX(it.second) }
                val ys = route.points.map { Tiles.latToY(it.first) }
                val x0 = xs.min()
                val x1 = xs.max() + 1
                // Widen to the left by what the panel hides, so the route lands right of it.
                val grow = if (leftInset in 0.0..0.8) (x1 - x0) * leftInset / (1 - leftInset) else 0.0
                mapFocus.value = TileBox((x0 - grow).toInt(), ys.min().toInt(), x1.toInt(), ys.max().toInt() + 1)
            }
        }
    }

    /** Dex drill-down, kept here so it survives the jump to the map tab and back. */
    val dexProvince = MutableStateFlow<String?>(null)
    val dexDistrict = MutableStateFlow<String?>(null)

    /**
     * Detour toward fog on the way to the destination. With the car's remaining range known, a
     * detour the range can't comfortably cover (round trip home included) is not offered. Planned
     * only while parked, where the card shows: while driving every fix re-planned it for nothing
     * (about a quarter of the background CPU), and the location stops changing once parked.
     */
    val detour: StateFlow<DetourPlanner.Suggestion?> = combine(
        live.map { it.location?.let { f -> f.lat to f.lng } }.distinctUntilChanged(),
        navi.destination,
        version,
        vehicle.map { it.rangeKm?.let { km -> (km / 10).toInt() * 10 } }.distinctUntilChanged(),
        vehicle.map { it.parked }.distinctUntilChanged(),
    ) { origin, dest, _, rangeKm, parked ->
        if (!parked || origin == null || dest == null) return@combine null
        val s = DetourPlanner.plan(origin.first, origin.second, dest.lat, dest.lng, repo::isExplored) ?: return@combine null
        val direct = Tiles.distanceMeters(origin.first, origin.second, dest.lat, dest.lng)
        s.takeIf { rangeKm == null || DetourPlanner.fitsRange(rangeKm.toDouble(), direct, s.extraMeters) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun acceptDetour(s: DetourPlanner.Suggestion): Boolean =
        navi.addDetourWaypoint(s.lat, s.lng)

    val cloud = FogRuntime.cloud.state

    /** Demo session on (real fixes ignored) / its drive still playing. */
    val demoRunning: StateFlow<Boolean> = live.map { it.demo }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val demoPlaying: StateFlow<Boolean> = live.map { it.demoPlaying }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val onboardingDone = FogRuntime.mapPrefs.onboardingDone

    fun setOnboardingDone(done: Boolean) = FogRuntime.mapPrefs.setOnboardingDone(done)

    val voice = FogRuntime.mapPrefs.voice
    val sound = FogRuntime.mapPrefs.sound

    fun setSound(on: Boolean) {
        FogRuntime.mapPrefs.setSound(on)
    }
    val soundVolume = FogRuntime.mapPrefs.soundVolume

    fun setSoundVolume(percent: Int) {
        FogRuntime.mapPrefs.setSoundVolume(percent)
    }

    /** The event notice [id] has been shown: clear it unless a newer one replaced it meanwhile. */
    fun eventShown(id: Long) {
        FogRuntime.live.update { if (it.lastEventId == id) it.copy(lastEvent = null) else it }
    }

    fun setVoice(on: Boolean) {
        FogRuntime.mapPrefs.setVoice(on)
        if (on) FogRuntime.announcer.warmUp()
    }

    val stories = FogRuntime.mapPrefs.stories

    fun setStories(on: Boolean) = FogRuntime.mapPrefs.setStories(on)

    fun dismissTripSummary() = FogRuntime.live.update { it.copy(lastTrip = null) }

    /** The 개척 timelapse being shown (record screen), null when closed. */
    val timelapse = MutableStateFlow<Timelapse?>(null)
    private var timelapseJob: Job? = null

    fun openTimelapse() {
        // A second tap while the history is still loading doesn't load it again.
        if (timelapseJob?.isActive == true) return
        timelapseJob = viewModelScope.launch(Dispatchers.Default) {
            timelapse.value = Timelapse.of(repo.clearedSince(0)) ?: return@launch run { shareCardMessage.value = "아직 걷어낸 안개가 없어요." }
        }
    }

    /** The last "내 탐험 지도" card made on the record screen (shown until closed), or why it failed. */
    val shareCard = MutableStateFlow<java.io.File?>(null)
    val shareCardMessage = MutableStateFlow<String?>(null)

    fun makeShareCard(context: android.content.Context) {
        val app = context.applicationContext
        viewModelScope.launch(Dispatchers.Default) {
            val s = summary.value
            val counts = FogRuntime.regions?.levels(repo)
            val top = counts?.let { n ->
                n.district.indices.filter { n.district[it] > 0 }.maxByOrNull { n.districtRate(it) }
                    ?.let { n.atlas.districts[it].name to n.districtRate(it) }
            }
            val stats = ShareCard.Stats(
                level = s.level,
                areaM2 = s.areaKm2 * 1_000_000,
                dongs = counts?.visitedDongs?.size ?: 0,
                stampKinds = s.stampKinds,
                landings = s.landings,
                topDistrict = top?.first,
                topRate = top?.second ?: 0.0,
            )
            runCatching { ShareCard.create(app, stats, landings.value.map { it.lat to it.lng }) }
                .onSuccess {
                    // QA reads this line: the card folder isn't visible to adb under the driver profile.
                    android.util.Log.i("FogShare", "card saved ${it.path} (${it.length()} bytes)")
                    shareCard.value = it
                    shareCardMessage.value = null
                }
                .onFailure { shareCardMessage.value = "카드를 만들지 못했어요: ${it.message}" }
        }
    }

    val detourCard = FogRuntime.mapPrefs.detourCard
    val suggestionCard = FogRuntime.mapPrefs.suggestionCard

    val demoCourse = FogRuntime.demoPrefs.courseId
    val demoSpeed = FogRuntime.demoPrefs.speedKmh

    fun setDemoCourse(id: String) = FogRuntime.demoPrefs.setCourse(id)

    fun setDemoSpeed(kmh: Int) = FogRuntime.demoPrefs.setSpeed(kmh)

    val demoRoundTrip = FogRuntime.demoPrefs.roundTrip

    fun setDemoRoundTrip(on: Boolean) = FogRuntime.demoPrefs.setRoundTrip(on)

    /** Result line under the export / import buttons. */
    val backupMessage = MutableStateFlow<String?>(null)

    fun syncNow() {
        viewModelScope.launch { FogRuntime.cloud.syncNow() }
    }

    fun setAutoSync(on: Boolean) = FogRuntime.cloud.setAutoSync(on)

    fun exportData() {
        viewModelScope.launch {
            backupMessage.value = runCatching { repo.exportTo(FogRuntime.backupFile) }.fold(
                { "내보냈어요: 칸 ${it.cells}개 · 주행 ${it.trips}건 · 하차 ${it.landings}곳\n${FogRuntime.backupFile.path}" },
                { "내보내지 못했어요: ${it.message}" },
            )
        }
    }

    fun importData() {
        viewModelScope.launch {
            // The newest candidate, so a file copied in after an export isn't passed over.
            val file = FogRuntime.importCandidates
                .filter { it.exists() }.maxByOrNull { it.lastModified() } ?: FogRuntime.backupFile
            backupMessage.value = if (!file.exists()) {
                "불러올 파일이 없어요. ${FogRuntime.backupFile.path} 에 백업 파일을 넣어 주세요."
            } else {
                runCatching { repo.importFrom(file) }.fold(
                    { "불러왔어요: 칸 ${it.cells}개 · 주행 ${it.trips}건 · 하차 ${it.landings}곳" },
                    { "불러오지 못했어요: ${it.message}" },
                )
            }
            shownTrip.value = null
        }
    }

    /** Wipes every record and puts the screens back to a first-run state (e.g. before a demo). */
    fun resetAll() {
        showTripJob?.cancel()
        shownTrip.value = null
        mapFocus.value = null
        dexProvince.value = null
        dexDistrict.value = null
        backupMessage.value = null
        suggestionSkip.value = 0
        viewModelScope.launch {
            repo.reset()
            FogRuntime.live.update { it.copy(lastEvent = "기록을 모두 지웠어요. 처음 상태에서 다시 시작해요", lastEventId = System.nanoTime()) }
        }
    }

    companion object {
        /** Unexplored 동 farther than this are not suggested; the nearest few are offered in turn. */
        const val SUGGEST_MAX_M = 30_000.0
        const val SUGGEST_CHOICES = 8
        /** Newest cleared cells read for the dex's "최근 개척": enough to reach back past the last few trips. */
        private const val RECENT_CELLS = 4_000
        /** A trip starting or ending this close to the navigation's home is named "집". */
        private const val HOME_NEAR_M = 400.0
        /** 읍·면·동 exploration steps shown as a notice with a sparkle / also spoken. */
        const val MILESTONE_NOTICE = 25
        const val MILESTONE_VOICE = 50
    }
}

/** A week or month of exploring ([current]) beside the one before it ([previous]). */
data class PeriodReport(val period: ChallengePeriod, val current: PeriodStats, val previous: PeriodStats) {
    val title: String get() = if (period == ChallengePeriod.WEEK) "이번 주" else "이번 달"
    val previousTitle: String get() = if (period == ChallengePeriod.WEEK) "지난주" else "지난달"
}
