package com.zobo.fogdrive.record

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
import com.zobo.fogdrive.FogRuntime
import com.zobo.fogdrive.R
import com.zobo.fogdrive.car.Arrival
import com.zobo.fogdrive.car.ArrivalKind
import com.zobo.fogdrive.car.Fix
import com.zobo.fogdrive.car.LocationFeed
import com.zobo.fogdrive.car.VehicleState
import com.zobo.fogdrive.data.CellSource
import com.zobo.fogdrive.data.TripEntity
import com.zobo.fogdrive.demo.DemoDrive
import com.zobo.fogdrive.geo.Area
import com.zobo.fogdrive.geo.Tiles
import com.zobo.fogdrive.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        // Real fixes are ignored while the scripted demo drive is playing.
        if (demoSession && fix.source != LocationFeed.SOURCE_DEMO) return
        val vehicle = FogRuntime.vehicle.state.value
        // Create the trip row first so the first swath is saved under the new trip, not the last one.
        recorder.startTripIfNeeded(fix, vehicle)?.let { apply(it) }
        val events = recorder.onFix(fix, vehicle)
        events.forEach { apply(it) }
        lastFix = fix
        recordTrack(fix)
        FogRuntime.live.update {
            it.copy(location = fix, tripActive = recorder.tripActive, trip = recorder.stats)
        }
        persistTripStats()
    }

    private var lastFix: Fix? = null
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
                    ArrivalKind.DETOUR -> "안개 구역 원정 성공"
                }
                note("$what: ${event.name} 주변을 밝혔어요 (+${Area.formatCells(event.newCells)})")
            }
            is RecorderEvent.Stamp -> {
                repo.addStamp(event.code.name, event.lat, event.lng, event.at, repo.currentTripId)
                note("스탬프 획득: ${event.code.title}")
            }
        }
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
        val recent = FogRuntime.navi.recent.filterNotNull().first()
        // Bookmarks answer through their own callback, which may land after the recent list.
        val bookmarks = FogRuntime.navi.bookmarks.value
            ?: withTimeoutOrNull(BOOKMARK_WAIT_MS) { FogRuntime.navi.bookmarks.filterNotNull().first() }
            ?: emptyList()
        val places = recent + bookmarks
        if (places.isEmpty()) return
        var added = 0
        for (p in places) {
            added += repo.reveal(Tiles.neighborhood(Tiles.cellOf(p.lat, p.lng), SEED_RADIUS), CellSource.SEED)
        }
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        note("내비 최근 목적지·즐겨찾기 ${places.size}곳 주변을 밝혔어요 (+${Area.formatCells(added)})")
    }

    private fun startDemo() {
        if (demo?.running == true) return
        demoSession = true
        FogRuntime.navi.setDemoDestination(DemoDrive.DESTINATION)
        val d = DemoDrive(
            scope = scope,
            onFix = { fix -> scope.launch { handleFix(fix) } },
            onVehicle = { v -> FogRuntime.vehicle.setDemoOverride(v) },
            onGuidance = { g -> FogRuntime.navi.setDemoGuidance(g) },
            onArrival = { a -> FogRuntime.navi.emitDemoArrival(a) },
            onFinished = { FogRuntime.live.update { it.copy(lastEvent = "시연 주행 완료 · 설정에서 시연 중지를 누르면 실제 위치로 돌아가요") } },
        )
        demo = d
        d.start()
        FogRuntime.live.update { it.copy(demo = true, locationSource = LocationFeed.SOURCE_DEMO) }
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
        FogRuntime.live.update { it.copy(demo = false, locationSource = locationFeed.sourceName) }
    }

    private fun note(message: String) {
        FogRuntime.live.update { it.copy(lastEvent = message) }
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
        const val ACTION_DEMO_START = "com.zobo.fogdrive.DEMO_START"
        const val ACTION_DEMO_STOP = "com.zobo.fogdrive.DEMO_STOP"
        const val ACTION_STOP = "com.zobo.fogdrive.STOP"
        const val PREFS = "fogdrive"
        const val KEY_SEEDED = "seeded_from_navi"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 19
        /** Route sampling step: dense enough to follow streets, ~50 points per km at most. */
        private const val TRACK_STEP_M = 20.0
        private const val SEED_RADIUS = 4
        private const val BOOKMARK_WAIT_MS = 5_000L

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
