package com.zobo.fogdrive

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import com.zobo.fogdrive.car.Fix
import com.zobo.fogdrive.car.NaviBridge
import com.zobo.fogdrive.car.VehicleSignals
import com.zobo.fogdrive.data.CloudSync
import com.zobo.fogdrive.data.ExploreRepository
import com.zobo.fogdrive.data.FogDb
import com.zobo.fogdrive.explore.RegionTally
import com.zobo.fogdrive.geo.RegionAtlas
import com.zobo.fogdrive.record.TripStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** What the recording service is doing right now, for the UI. */
data class LiveStatus(
    val recording: Boolean = false,
    val demo: Boolean = false,
    val location: Fix? = null,
    val locationSource: String = "-",
    val tripActive: Boolean = false,
    val trip: TripStats = TripStats(),
    val lastEvent: String? = null,
)

/** Process-wide singletons shared by the recording service and the UI. */
object FogRuntime {
    lateinit var repo: ExploreRepository
        private set
    lateinit var vehicle: VehicleSignals
        private set
    lateinit var navi: NaviBridge
        private set
    lateinit var cloud: CloudSync
        private set

    /** 시·도 / 시·군·구 / 읍·면·동 boundaries, parsed off the main thread on first use (the map's
     *  two-strength fog, then the dex tab) rather than in [init]. */
    val regions: RegionTally? by lazy {
        // The file ships with the app, but a broken install must not take the app down with it:
        // without it the map falls back to one-strength fog and the dex says it is unavailable.
        val start = SystemClock.elapsedRealtime()
        runCatching { RegionTally(RegionAtlas.load(appContext)) }
            .onSuccess { Log.i("FogRuntime", "region atlas loaded in ${SystemClock.elapsedRealtime() - start} ms") }
            .onFailure { Log.e("FogRuntime", "region atlas unavailable", it) }
            .getOrNull()
    }
    private lateinit var appContext: Context

    /** Export / import file: app-specific external storage, so it can be copied off with adb or USB. */
    val backupFile: File
        get() = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "fogdrive-backup.json")

    val live = MutableStateFlow(LiveStatus())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        appContext = app
        repo = ExploreRepository(FogDb.create(app), scope)
        vehicle = VehicleSignals(app)
        navi = NaviBridge(app)
        cloud = CloudSync(app, repo)
        scope.launch { repo.load() }
    }
}
