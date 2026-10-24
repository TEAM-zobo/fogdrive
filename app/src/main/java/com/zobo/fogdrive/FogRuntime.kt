package com.zobo.fogdrive

import android.content.Context
import com.zobo.fogdrive.car.Fix
import com.zobo.fogdrive.car.NaviBridge
import com.zobo.fogdrive.car.VehicleSignals
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

    /** 시·도 / 시·군·구 / 읍·면·동 boundaries, parsed off the main thread on first use (the map's
     *  two-strength fog, then the dex tab) rather than in [init]. */
    val regions: RegionTally by lazy { RegionTally(RegionAtlas.load(appContext)) }
    private lateinit var appContext: Context

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
        scope.launch { repo.load() }
    }
}
