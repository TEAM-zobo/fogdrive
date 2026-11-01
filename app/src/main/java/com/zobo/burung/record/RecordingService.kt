package com.zobo.burung.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zobo.burung.FogRuntime
import com.zobo.burung.NewDong
import com.zobo.burung.TripSummary
import com.zobo.burung.R
import com.zobo.burung.car.Arrival
import com.zobo.burung.car.ArrivalKind
import com.zobo.burung.car.Fix
import com.zobo.burung.car.LocationFeed
import com.zobo.burung.car.Place
import com.zobo.burung.car.VehicleState
import com.zobo.burung.data.CellSource
import com.zobo.burung.data.TripEntity
import com.zobo.burung.demo.DemoCourses
import com.zobo.burung.demo.DemoDrive
import com.zobo.burung.explore.Milestones
import com.zobo.burung.explore.StampBook
import com.zobo.burung.explore.Titles
import com.zobo.burung.explore.Progress
import com.zobo.burung.geo.Area
import com.zobo.burung.geo.Tiles
import com.zobo.burung.ui.MainActivity
import com.zobo.burung.voice.Sfx
import com.zobo.burung.voice.Sound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that records while the car runs. No UI is shown while driving;
 * it only listens to location + vehicle signals and updates the explored map.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val repo get() = FogRuntime.repo
    private val recorder by lazy { TripRecorder(repo) }
    private lateinit var locationFeed: LocationFeed
    private var demo: DemoDrive? = null
    /** True from 시연 시작 until 시연 중지, so the finished demo stays on screen for filming. */
    private var demoSession = false
    private var tripRow: TripEntity? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        locationFeed = LocationFeed(this) { fix -> scope.launch { handleFix(fix) } }
        FogRuntime.vehicle.start()
        FogRuntime.navi.start()
        val source = locationFeed.start()
        FogRuntime.live.update { it.copy(recording = true, locationSource = source) }
        FogRuntime.announcer.warmUp()

        scope.launch {
            FogRuntime.vehicle.state.collect { v -> handleVehicle(v) }
        }
        scope.launch { seedFromNavigation() }
        scope.launch {
            FogRuntime.navi.arrivals.collect { handleArrival(it) }
        }
        scope.launch {
            // 기록 전체 삭제: the active trip row is gone, so close it and start fresh on the next fix.
            repo.resets.drop(1).collect {
                mutex.withLock {
                    recorder.endTrip(System.currentTimeMillis())
                    tripRow = null
                    repo.currentTripId = 0L
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DEMO_START -> startDemo()
            ACTION_DEMO_STOP -> stopDemo()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopDemo(endTrip = false)
        locationFeed.stop()
        // Cancel in-flight fix/vehicle handling first so it releases the mutex, then close the trip
        // under the same mutex so tripRow is never written from two places at once.
        scope.cancel()
        val now = System.currentTimeMillis()
        FogRuntime.scope.launch {
            mutex.withLock { recorder.endTrip(now).forEach { apply(it) } }
        }
        FogRuntime.vehicle.stop()
        FogRuntime.navi.stop()
        FogRuntime.live.update { it.copy(recording = false, demo = false, tripActive = false) }
        super.onDestroy()
    }

    private suspend fun handleFix(fix: Fix) {
        // Off the main thread and outside the lock: a slow navigation binder must not hold up fixes,
        // vehicle events or the UI. Skipped while the demo plays: its scripted guidance would be
        // overwritten by the real navigation's (empty) answer.
        if (!demoSession && fix.source != LocationFeed.SOURCE_DEMO) {
            scope.launch(Dispatchers.IO) { FogRuntime.navi.requestTurnInfo() }
        }
        mutex.withLock { recordFix(fix) }
    }

    private suspend fun recordFix(fix: Fix) {
        // Until the explored set is loaded every cell looks new (inflated counts, bogus stamps).
        repo.loaded.first { it }
        // Nor before the gear is known: an unknown gear counts as driving and would start a trip in P.
        FogRuntime.vehicle.settled.first { it }
        // Real fixes are ignored while the scripted demo drive is playing.
        if (demoSession && fix.source != LocationFeed.SOURCE_DEMO) return
        val vehicle = FogRuntime.vehicle.state.value
        // Create the trip row first so the first swath is saved under the new trip, not the last one.
        recorder.startTripIfNeeded(fix, vehicle)?.let { apply(it) }
        val events = recorder.onFix(fix, vehicle)
        events.forEach { apply(it) }
        lastFix = fix
        // The origin for 멀리 떠난 날 when no home is set: the trip's first usable fix, atlas or not.
        if (recorder.tripActive && tripOrigin == null && (fix.accuracyM ?: 0f) <= TripRecorder.MAX_ACCURACY_M) {
            tripOrigin = fix.lat to fix.lng
        }
        recordTrack(fix)
        stampRegions(fix)
        if (recorder.tripActive && FogRuntime.vehicle.state.value.hasPassenger) tripPassenger = true
        FogRuntime.live.update {
            it.copy(location = fix, tripActive = recorder.tripActive, trip = recorder.stats)
        }
        persistTripStats()
    }

    private var lastFix: Fix? = null
    /** Regions explored before this trip and what the trip has crossed, for the region stamps. */
    private class TripRegions(
        val knownDongs: MutableSet<Int>,
        val knownDistricts: MutableSet<Int>,
        val knownProvinces: MutableSet<Int>,
    ) {
        var newDongs = 0
        var startProvince = -1
    }
    private var tripRegions: TripRegions? = null

    /**
     * Region stamps, from the 동 under the car: "새 동네 첫발" (a 시·군·구 never explored), "새 땅 밟기"
     * (a 시·도 never explored), "동네 순례" (5 new 동 in one trip), "경계 넘어" (left the start 시·도).
     * Each is awarded once per trip; the recorder's per-trip set already drops repeats of a code.
     */
    private suspend fun stampRegions(fix: Fix) {
        val t = tripRegions ?: return
        if (!recorder.tripActive) return
        // Same accuracy gate as the recorder: a stray fix must not cross a border it never reached.
        if ((fix.accuracyM ?: 0f) > TripRecorder.MAX_ACCURACY_M) return
        val atlas = FogRuntime.regions?.atlas ?: return
        val dong = atlas.dongOf(Tiles.cellOf(fix.lat, fix.lng)).takeIf { it >= 0 } ?: return
        val district = atlas.dongs[dong].district
        val province = atlas.districts[district].province
        val earned = ArrayList<Pair<StampCode, String>>(0)
        fun stamp(code: StampCode, message: String) {
            // Codes outside the stamp book are dropped by apply(): no notice for a stamp never given.
            if (code in StampBook.ACTIVE && awardedRegionStamps.add(code)) earned += code to message
        }
        // Where the trip starts is not a "new" 시·군·구 or 시·도 (on a first drive nothing is known yet).
        if (t.startProvince < 0) {
            t.startProvince = province
            t.knownDistricts.add(district)
            t.knownProvinces.add(province)
        }
        if (t.knownDongs.add(dong)) {
            t.newDongs++
            onNewDong(atlas.dongs[dong], atlas.districts[district].name)
            if (t.newDongs >= DONG_HOPPER_COUNT) stamp(StampCode.DONG_HOPPER, "동네 순례: 이번 주행에서 새 동 ${t.newDongs}곳")
        }
        if (t.knownDistricts.add(district)) stamp(StampCode.NEW_DISTRICT, "${StampCode.NEW_DISTRICT.title}: ${atlas.districts[district].name}")
        if (t.knownProvinces.add(province)) stamp(StampCode.NEW_PROVINCE, "새 땅 밟기: ${atlas.provinces[province].name}")
        if (province != t.startProvince) stamp(StampCode.PROVINCE_CROSS, "경계 넘어: ${atlas.provinces[province].name}")
        for ((code, message) in earned) {
            apply(RecorderEvent.Stamp(code, fix.lat, fix.lng, fix.timeMs))
            note(message)  // after the generic "스탬프 획득" note, so the region name stays on screen
        }
    }
    private val awardedRegionStamps = HashSet<StampCode>()

    /** Where this trip's first placed fix was (the origin of 멀리 떠난 날 without a home). */
    private var tripOrigin: Pair<Double, Double>? = null

    /**
     * Milestone stamps at the end of a trip (see Milestones): a week of 3 days, 4 weeks in a row,
     * 1,000 km, 10 and 100 읍·면·동, 3 시·도, and 멀리 떠난 날 (got off 50 km+ from home, straight line:
     * how far, not how long, so no reward for driving without a break), and 함께 탐험 (a drive
     * with someone aboard; nothing to do for it, the seats tell). Each is earned once.
     */
    private suspend fun awardMilestones(at: Long) {
        val have = repo.stampCodes()
        val atlas = withContext(Dispatchers.Default) { FogRuntime.regions?.atlas }
        val reached = Milestones.reached(repo.stampProgress(atlas, at)).toMutableList()
        val end = lastFix
        val origin = FogRuntime.navi.home.value?.let { it.lat to it.lng } ?: tripOrigin
        if (end != null && origin != null &&
            Tiles.distanceMeters(origin.first, origin.second, end.lat, end.lng) >= Milestones.FAR_M
        ) reached += StampCode.FAR_DAY
        if (tripPassenger && recorder.stats.distanceMeters >= MIN_SUMMARY_M) reached += StampCode.CREW_MISSION
        for (code in reached) {
            if (code.name in have) continue
            apply(RecorderEvent.Stamp(code, end?.lat ?: 0.0, end?.lng ?: 0.0, at))
        }
    }

    // This trip, for the summary card: 동 entered for the first time and stamps earned, in order.
    private val tripNewDongs = ArrayList<NewDong>()
    private val tripStamps = ArrayList<StampCode>()
    /** Someone rode along (front or rear seat) at some point of this trip. */
    private var tripPassenger = false
    /** Stamp codes already collected when this trip started; null when the start wasn't seen. */
    private var stampsAtStart: Set<String>? = null
    /** Level when this trip started, for the arrival card's level-up; null when the start wasn't seen. */
    private var levelAtStart: Int? = null

    /** A 동 entered for the first time: say so, and keep it for the summary card. */
    private fun onNewDong(d: com.zobo.burung.geo.Dong, district: String) {
        val lat = Tiles.yToLat(d.outline.labelY + 0.5)
        val lng = Tiles.xToLng(d.outline.labelX + 0.5)
        tripNewDongs += NewDong(d.code, d.name, district, lat, lng)
        FogRuntime.announcer.say(
            if (FogRuntime.mapPrefs.kids.value) "짜잔! 처음 와 보는 ${d.name}${copula(d.name)}!" else "처음 와 본 ${d.name}${copula(d.name)}",
        )
    }

    private var trackTrip = 0L
    private var trackLat = 0.0
    private var trackLng = 0.0

    /** Keeps the route of the active trip for the record screen: a point every [TRACK_STEP_M]. */
    private fun recordTrack(fix: Fix) {
        val trip = repo.currentTripId
        if (!recorder.tripActive || trip == 0L) return
        if (trip == trackTrip && Tiles.distanceMeters(trackLat, trackLng, fix.lat, fix.lng) < TRACK_STEP_M) return
        trackTrip = trip
        trackLat = fix.lat
        trackLng = fix.lng
        repo.addTrackPoint(trip, fix.lat, fix.lng, fix.timeMs)
    }

    private suspend fun handleVehicle(v: VehicleState) = mutex.withLock {
        repo.loaded.first { it }
        val events = recorder.onVehicle(v, System.currentTimeMillis())
        events.forEach { apply(it) }
        if (events.isNotEmpty()) {
            FogRuntime.live.update { it.copy(tripActive = recorder.tripActive, trip = recorder.stats) }
        }
    }

    private suspend fun handleArrival(arrival: Arrival) = mutex.withLock {
        repo.loaded.first { it }
        // Like fixes: while the demo plays, only its scripted arrival counts.
        if (demoSession && !arrival.demo) return@withLock
        val events = recorder.onArrival(arrival.kind, arrival.name, System.currentTimeMillis())
        events.forEach { apply(it) }
        if (events.isNotEmpty()) {
            FogRuntime.live.update { it.copy(trip = recorder.stats) }
            persistTripStats()
        }
    }

    private suspend fun apply(event: RecorderEvent) {
        when (event) {
            is RecorderEvent.TripStarted -> {
                val id = repo.startTrip(event.at)
                repo.currentTripId = id
                tripRow = TripEntity(id = id, startedAt = event.at)
                // Regions explored before this trip, for the region stamps (off the main thread: the
                // first call may load the region atlas).
                tripRegions = withContext(Dispatchers.Default) {
                    FogRuntime.regions?.levels(repo)?.let { n ->
                        fun visited(a: IntArray) = a.indices.filterTo(HashSet()) { a[it] > 0 }
                        TripRegions(visited(n.dong), visited(n.district), visited(n.province))
                    }
                }
                awardedRegionStamps.clear()
                tripOrigin = null
                tripNewDongs.clear()
                tripStamps.clear()
                levelAtStart = Progress.level(repo.xp())
                tripPassenger = FogRuntime.vehicle.state.value.hasPassenger
                stampsAtStart = repo.stampCodes().toSet()
                FogRuntime.live.update { it.copy(lastTrip = null) }
                note("새 주행 기록을 시작했어요")
            }
            is RecorderEvent.TripEnded -> {
                // Close the route where the car stopped (points are otherwise only every 20 m).
                lastFix?.let { f ->
                    if (trackTrip == repo.currentTripId && Tiles.distanceMeters(trackLat, trackLng, f.lat, f.lng) > 1.0) {
                        repo.addTrackPoint(trackTrip, f.lat, f.lng, f.timeMs)
                    }
                }
                persistTripStats(endedAt = event.at)
                tripRow = null
                awardMilestones(event.at)
                showSummary(event.at)
                // Each finished drive is synced when the driver left auto sync on.
                if (FogRuntime.cloud.state.value.autoSync) FogRuntime.scope.launch { FogRuntime.cloud.syncNow() }
            }
            is RecorderEvent.Landing -> {
                repo.addLanding(event.lat, event.lng, event.at, repo.currentTripId)
                note("하차 지점을 기록했어요 (+${Area.formatCells(event.newCells)})")
            }
            is RecorderEvent.Arrival -> {
                val what = when (event.kind) {
                    ArrivalKind.DESTINATION -> "목적지 도착"
                    ArrivalKind.WAYPOINT -> "경유지 도착"
                    ArrivalKind.DETOUR -> "새 동네 원정 성공"
                }
                note("$what: ${event.name} 주변을 탐험했어요 (+${Area.formatCells(event.newCells)})")
            }
            is RecorderEvent.Stamp -> {
                // Only the stamps of the stamp book are kept; the recorder's other codes are dropped.
                if (event.code !in StampBook.ACTIVE) return
                repo.addStamp(event.code.name, event.lat, event.lng, event.at, repo.currentTripId)
                note(if (FogRuntime.mapPrefs.kids.value) "${event.code.title} 스탬프를 받았어요!" else "스탬프 획득: ${event.code.title}")
                tripStamps += event.code
            }
        }
    }

    /** Summary card + a spoken wrap-up for a trip that went somewhere. */
    private suspend fun showSummary(endedAt: Long) {
        val s = recorder.stats
        val withPassenger = tripPassenger
        tripPassenger = false
        val stampsBefore = stampsAtStart
        stampsAtStart = null
        val have = repo.stampCodes().toSet()
        fun codes(names: Set<String>) = StampBook.ACTIVE.filterTo(HashSet()) { it.name in names }
        // Only what this trip added: a stamp held already (주말 탐험가 again) is not news.
        val newStamps = tripStamps.distinct().filter { stampsBefore == null || it.name !in stampsBefore }
        val newTitles = if (stampsBefore == null) emptyList() else {
            val was = codes(stampsBefore)
            val now = codes(have)
            Titles.ALL.filter { it.earned(now) && !it.earned(was) }.map { it.id }
        }
        val before = levelAtStart ?: 0
        levelAtStart = null
        if (s.distanceMeters < MIN_SUMMARY_M && s.newCells == 0) return
        // After persistTripStats, so this trip's distance is in the total.
        val after = Progress.level(repo.xp())
        val summary = TripSummary(
            endedAt = endedAt,
            distanceMeters = s.distanceMeters,
            newCells = s.newCells,
            newDistanceMeters = s.newDistanceMeters,
            newDongs = tripNewDongs.toList(),
            // As for the 함께 탐험 stamp: a drive that went somewhere, not a few metres in the car park.
            withPassenger = withPassenger && s.distanceMeters >= MIN_SUMMARY_M,
            newStamps = newStamps,
            newTitles = newTitles,
            levelBefore = before,
            levelAfter = after,
        )
        FogRuntime.live.update { it.copy(lastTrip = summary) }
        if (summary.leveledUp) Sfx.play(Sound.LEVEL_UP, byControl = false)
        val parts = buildList {
            if (s.newCells > 0) add("새로운 곳 ${spokenArea(Area.ofCells(s.newCells))}를 탐험했어요")
            else add("${"%.1f".format(s.distanceMeters / 1000)}킬로미터를 달렸어요")
            if (summary.newDongs.isNotEmpty()) add("처음 가 본 동네는 ${summary.newDongs.size}곳")
        }
        val kids = FogRuntime.mapPrefs.kids.value
        val line = if (kids) {
            // Kids mode: the same facts, in words a child follows (no km², no "개척률").
            val found = summary.newDongs.size
            (if (found > 0) "도착! 오늘은 새 동네를 ${found}곳이나 찾았어요. 최고예요!" else "도착! 오늘도 멋진 모험이었어요.") +
                if (summary.leveledUp) " 우와, 탐험대가 ${after}레벨이 되었어요!" else ""
        } else {
            "주행을 마쳤어요. 이번 주행에서 " + parts.joinToString(", ") + "." + if (summary.leveledUp) " 레벨 업! ${after}레벨이 되었어요." else ""
        }
        FogRuntime.announcer.say(line, important = true)
    }

    private suspend fun persistTripStats(endedAt: Long? = null) {
        val row = tripRow ?: return
        val s = recorder.stats
        val updated = row.copy(
            endedAt = endedAt ?: row.endedAt,
            newCells = s.newCells,
            distanceMeters = s.distanceMeters,
            newDistanceMeters = s.newDistanceMeters,
        )
        if (updated == row) return
        tripRow = updated
        repo.saveTrip(updated)
    }

    /** First run: clear the fog around recent destinations and bookmarks so the map is not empty. */
    private suspend fun seedFromNavigation() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        repo.loaded.first { it }
        FogRuntime.navi.connected.first { it }
        // Each list answers through its own callback, and the navigation may never answer one: the
        // emulator's sends no recent destinations at all, and waiting for them held back the seed
        // around its 집/회사 bookmarks too (no first-run fog cleared, no message).
        // Both lists are awaited together, so a silent one costs one wait, not two.
        val (recent, bookmarks) = coroutineScope {
            val r = async { answerOf(FogRuntime.navi.recent) }
            val b = async { answerOf(FogRuntime.navi.bookmarks) }
            r.await() to b.await()
        }
        // An unset bookmark comes through as (0, 0).
        // A place that is both a recent destination and a bookmark (집) counts once in the message.
        val places = (recent + bookmarks).filter { it.lat != 0.0 || it.lng != 0.0 }.distinctBy { it.lat to it.lng }
        if (places.isEmpty()) return
        var added = 0
        for (p in places) {
            added += repo.reveal(Tiles.neighborhood(Tiles.cellOf(p.lat, p.lng), SEED_RADIUS), CellSource.SEED)
        }
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        note("내비 최근 목적지·즐겨찾기 ${places.size}곳 주변을 지도에 열어 뒀어요 (+${Area.formatCells(added)})")
    }

    private suspend fun answerOf(list: StateFlow<List<Place>?>): List<Place> =
        list.value ?: withTimeoutOrNull(NAVI_ANSWER_WAIT_MS) { list.filterNotNull().first() } ?: emptyList()

    private fun startDemo() {
        if (demo?.running == true) return
        if (demoSession) {
            // Another run after a finished one: close its trip, since a repeat of the same course
            // lands within the landing cooldown and would otherwise never end its trip.
            scope.launch {
                mutex.withLock { recorder.endTrip(System.currentTimeMillis()).forEach { apply(it) } }
            }
        }
        demoSession = true
        val course = DemoCourses.byId(FogRuntime.demoPrefs.courseId.value)
        FogRuntime.navi.setDemoDestination(course.destination)
        val d = DemoDrive(
            scope = scope,
            onFix = { fix -> scope.launch { handleFix(fix) } },
            onVehicle = { v -> FogRuntime.vehicle.setDemoOverride(v) },
            onGuidance = { g -> FogRuntime.navi.setDemoGuidance(g) },
            onArrival = { a -> FogRuntime.navi.emitDemoArrival(a) },
            onFinished = {
                FogRuntime.live.update {
                    it.copy(demoPlaying = false, lastEvent = "시연 주행 완료 · 다른 코스를 바로 시작하거나, 시연 중지로 실제 위치로 돌아가요", lastEventId = System.nanoTime())
                }
            },
        )
        demo = d
        d.start(
            course,
            speedKmh = { FogRuntime.demoPrefs.speedKmh.value },
            roundTrip = { FogRuntime.demoPrefs.roundTrip.value },
            onReturn = { note("${course.destination.name}에서 같은 길로 출발지까지 돌아가요") },
        )
        FogRuntime.live.update { it.copy(demo = true, demoPlaying = true, locationSource = LocationFeed.SOURCE_DEMO) }
    }

    private fun stopDemo(endTrip: Boolean = true) {
        val wasDemo = demoSession
        demo?.stop()
        demo = null
        demoSession = false
        if (wasDemo && endTrip) {
            // Close the demo trip and forget the demo's last fix so real driving starts a clean trip.
            scope.launch {
                mutex.withLock { recorder.endTrip(System.currentTimeMillis()).forEach { apply(it) } }
            }
        }
        FogRuntime.navi.setDemoDestination(null)
        FogRuntime.navi.setDemoGuidance(null)
        FogRuntime.vehicle.setDemoOverride(null)
        FogRuntime.live.update { it.copy(demo = false, demoPlaying = false, locationSource = locationFeed.sourceName) }
    }

    private fun note(message: String) {
        FogRuntime.live.update { it.copy(lastEvent = message, lastEventId = System.nanoTime()) }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    companion object {
        const val ACTION_DEMO_START = "com.zobo.burung.DEMO_START"
        const val ACTION_DEMO_STOP = "com.zobo.burung.DEMO_STOP"
        const val ACTION_STOP = "com.zobo.burung.STOP"
        const val PREFS = "fogdrive"
        const val KEY_SEEDED = "seeded_from_navi"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 19
        private const val DONG_HOPPER_COUNT = 5
        /** Route sampling step: dense enough to follow streets, ~50 points per km at most. */
        private const val TRACK_STEP_M = 20.0
        private const val SEED_RADIUS = 4
        private const val NAVI_ANSWER_WAIT_MS = 5_000L
        /** Shorter drives (moving the car in a car park) get no summary card. */
        private const val MIN_SUMMARY_M = 300.0

        /** Shown with a story on the summary card (TripSummaryCard shows this many 동 in full). */

        /** "이에요" after a final consonant (신당동이에요), "예요" after a vowel (종로1가예요). */
        fun copula(word: String): String {
            val c = word.lastOrNull() ?: return "이에요"
            if (c !in '가'..'힣') return "이에요"
            return if ((c - '가') % 28 == 0) "예요" else "이에요"
        }

        /** "0.12제곱킬로미터" reads naturally; tiny areas in square metres. */
        fun spokenArea(m2: Double): String =
            if (m2 >= 10_000) "%.2f제곱킬로미터".format(m2 / 1_000_000).replace(".00", "")
            else "${(m2 / 100).toInt() * 100}제곱미터"

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
