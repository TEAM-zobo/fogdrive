package com.zobo.fogdrive.ui

import kotlinx.coroutines.flow.filterNotNull
import com.zobo.fogdrive.voice.Sound
import com.zobo.fogdrive.voice.Sfx
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zobo.fogdrive.FogRuntime
import com.zobo.fogdrive.data.LandingEntity
import com.zobo.fogdrive.data.StampEntity
import com.zobo.fogdrive.data.TripEntity
import com.zobo.fogdrive.explore.DetourPlanner
import com.zobo.fogdrive.explore.Progress
import com.zobo.fogdrive.explore.ProvinceProgress
import com.zobo.fogdrive.explore.RegionCounts
import com.zobo.fogdrive.explore.ChallengeGroup
import com.zobo.fogdrive.explore.ChallengePeriod
import com.zobo.fogdrive.explore.Challenges
import com.zobo.fogdrive.explore.PeriodStats
import com.zobo.fogdrive.geo.Area
import com.zobo.fogdrive.geo.TileBox
import com.zobo.fogdrive.geo.Tiles
import com.zobo.fogdrive.explore.Story
import com.zobo.fogdrive.record.StampCode
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
import kotlinx.coroutines.flow.mapLatest
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

    val summary: StateFlow<Summary> = combine(version, landings, stamps, trips, live) { _, l, s, t, lv ->
        val cells = repo.count()
        // Cell size only depends on latitude; 0.1° steps keep the summary from changing on every fix.
        val lat = lv.location?.lat?.let { Math.round(it * 10) / 10.0 } ?: 37.5
        val cellKm = Tiles.cellSizeMeters(lat) / 1000.0
        val xp = Progress.xp(cells, l.size, s.size, s.map { it.code }.distinct().size)
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

    /**
     * Today / this week / this month / this season, each with its goals. Computed only while the dex
     * shows them; the cells cleared since the earliest window start are read once and split per window.
     */
    val challenges: StateFlow<List<ChallengeGroup>> = combine(version, trips, landings, stamps) { _, _, l, s ->
        val now = System.currentTimeMillis()
        val tally = FogRuntime.regions
        val counts = tally?.levels(repo)
        val starts = ChallengePeriod.entries.associateWith { it.start(now) }
        // A week can start before the month or season does, so read from the earliest start.
        val allCleared = repo.clearedSince(starts.values.min())
        ChallengePeriod.entries.map { period ->
            val since = starts.getValue(period)
            val cleared = allCleared.mapNotNull { c -> c.cell.takeIf { c.firstSeenAt >= since } }
            val (newDongs, newDistricts, newProvinces) =
                if (tally != null && counts != null) tally.firstEnteredLevels(cleared, counts) else Triple(0, 0, 0)
            val tripTotals = repo.tripTotalsSince(since)
            val stats = PeriodStats(
                areaM2 = Area.ofCells(cleared.size).toInt(),
                newDongs = newDongs,
                newDistricts = newDistricts,
                newProvinces = newProvinces,
                trips = tripTotals.count,
                distanceM = tripTotals.distanceMeters.toInt(),
                landings = l.count { it.at >= since },
                stampKinds = s.filter { it.at >= since }.map { it.code }.distinct().size,
            )
            ChallengeGroup(period, period.label(now), Challenges.goals(period, stats))
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
                    if (top >= MILESTONE_VOICE) FogRuntime.announcer.say("${h.dong.name} ${top}퍼센트 개척")
                }
        }
    }

    /** Landmark line for the 동 under the car (only for 동 already entered, and while stories are on). */
    val hereStory: StateFlow<Story?> = combine(
        here.map { h -> h?.takeIf { it.dong.rate > 0 } }.distinctUntilChangedBy { it?.dongCode },
        FogRuntime.mapPrefs.stories,
    ) { h, on -> if (h != null && on) h else null }
        .mapLatest { h -> h?.let { FogRuntime.stories.storyFor(it.dongCode, it.dongLat, it.dongLng) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    fun showTrip(trip: TripEntity) {
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
                mapFocus.value = TileBox(xs.min().toInt(), ys.min().toInt(), xs.max().toInt() + 1, ys.max().toInt() + 1)
            }
        }
    }

    /** Dex drill-down, kept here so it survives the jump to the map tab and back. */
    val dexProvince = MutableStateFlow<String?>(null)
    val dexDistrict = MutableStateFlow<String?>(null)

    /**
     * Detour toward fog on the way to the destination. With the car's remaining range known, a
     * detour the range can't comfortably cover (round trip home included) is not offered.
     */
    val detour: StateFlow<DetourPlanner.Suggestion?> = combine(
        live.map { it.location?.let { f -> f.lat to f.lng } }.distinctUntilChanged(),
        navi.destination,
        version,
        vehicle.map { it.rangeKm?.let { km -> (km / 10).toInt() * 10 } }.distinctUntilChanged(),
    ) { origin, dest, _, rangeKm ->
        if (origin == null || dest == null) return@combine null
        val s = DetourPlanner.plan(origin.first, origin.second, dest.lat, dest.lng, repo::isExplored) ?: return@combine null
        val direct = Tiles.distanceMeters(origin.first, origin.second, dest.lat, dest.lng)
        s.takeIf { rangeKm == null || DetourPlanner.fitsRange(rangeKm.toDouble(), direct, s.extraMeters) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun stampCounts(): Map<StampCode, Int> =
        StampCode.entries.associateWith { code -> stamps.value.count { it.code == code.name } }

    fun acceptDetour(s: DetourPlanner.Suggestion): Boolean =
        navi.addDetourWaypoint(s.lat, s.lng)

    val cloud = FogRuntime.cloud.state

    /** Demo session on (real fixes ignored) / its drive still playing. */
    val demoRunning: StateFlow<Boolean> = live.map { it.demo }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val demoPlaying: StateFlow<Boolean> = live.map { it.demoPlaying }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val showWornRoads = FogRuntime.mapPrefs.showWornRoads

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

    /** The last "내 안개지도" card made on the record screen (shown until closed), or why it failed. */
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

    fun setShowWornRoads(on: Boolean) = FogRuntime.mapPrefs.setShowWornRoads(on)

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
            // The newer of the two, so a format-1 file copied in after an export isn't passed over.
            val file = listOf(FogRuntime.backupFile, FogRuntime.legacyBackupFile)
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
