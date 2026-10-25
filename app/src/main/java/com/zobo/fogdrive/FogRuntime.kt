package com.zobo.fogdrive

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.zobo.fogdrive.car.Fix
import com.zobo.fogdrive.car.NaviBridge
import com.zobo.fogdrive.car.VehicleSignals
import com.zobo.fogdrive.data.CloudSync
import com.zobo.fogdrive.data.ExploreRepository
import com.zobo.fogdrive.data.FogDb
import com.zobo.fogdrive.demo.DemoPrefs
import com.zobo.fogdrive.explore.CrewMission
import com.zobo.fogdrive.explore.LocalStories
import com.zobo.fogdrive.explore.RegionTally
import com.zobo.fogdrive.explore.Story
import com.zobo.fogdrive.geo.RegionAtlas
import com.zobo.fogdrive.record.StampCode
import com.zobo.fogdrive.record.TripStats
import com.zobo.fogdrive.ui.MapPrefs
import com.zobo.fogdrive.voice.Announcer
import com.zobo.fogdrive.voice.Sfx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File

/** What the recording service is doing right now, for the UI. */
data class LiveStatus(
    val recording: Boolean = false,
    val demo: Boolean = false,
    /** The scripted drive is still moving (false once it parked at the end; [demo] stays on). */
    val demoPlaying: Boolean = false,
    val location: Fix? = null,
    val locationSource: String = "-",
    val tripActive: Boolean = false,
    val trip: TripStats = TripStats(),
    val lastEvent: String? = null,
    /** Distinct per notice, so the same text arriving again shows again (see EventToast). */
    val lastEventId: Long = 0,
    /** Mission for the passengers on this trip, and its progress in the mission's unit. */
    val mission: CrewMission? = null,
    val missionProgress: Int = 0,
    /** The trip that just ended, shown as a summary card until dismissed or the next trip starts. */
    val lastTrip: TripSummary? = null,
)

/** A 읍·면·동 first entered on a trip, with its landmark line once looked up. */
data class NewDong(val code: String, val name: String, val district: String, val lat: Double, val lng: Double, val story: Story? = null)

/** What one finished trip achieved, for the summary card and the spoken wrap-up. */
data class TripSummary(
    val endedAt: Long,
    val distanceMeters: Double,
    val newCells: Int,
    val newDistanceMeters: Double,
    val newDongs: List<NewDong>,
    val stamps: List<StampCode>,
    val mission: CrewMission?,
    val missionDone: Boolean,
) {
    val newRoadRatio: Double get() = if (distanceMeters > 0) newDistanceMeters / distanceMeters else 0.0
}

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
    lateinit var demoPrefs: DemoPrefs
        private set
    lateinit var mapPrefs: MapPrefs
        private set
    lateinit var announcer: Announcer
        private set
    lateinit var stories: LocalStories
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
            .also { regionsLoaded.value = true }
    }
    private lateinit var appContext: Context

    /** Export / import file: app-specific external storage, so it can be copied off with adb or USB. */
    val backupFile: File
        get() = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "fogdrive-backup.json")

    val live = MutableStateFlow(LiveStatus())
    /** The region atlas has been read (or failed to): the first step of the launch loading screen. */
    val regionsLoaded = MutableStateFlow(false)

    /** The map's fog has been drawn with the region data once: the launch loading screen can go. */
    val fogReady = MutableStateFlow(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var initialized = false

    private val regionCountsFile: File get() = File(appContext.filesDir, "region_counts.bin")

    /**
     * Seeds the region counts from [saveRegionCounts]'s file when it was saved for exactly the cells
     * just loaded (same count and fingerprint, same atlas); otherwise counts them now and saves.
     */
    private fun seedRegionCounts() {
        val tally = regions ?: return
        val (generation, size) = repo.logPosition()
        val fingerprint = repo.logFingerprint(generation, size) ?: return
        val atlasId = atlasId(tally)
        val seeded = runCatching {
            java.io.DataInputStream(regionCountsFile.inputStream().buffered()).use { inp ->
                if (inp.readInt() != REGION_COUNTS_MAGIC || inp.readLong() != atlasId) return@use false
                if (inp.readInt() != size || inp.readLong() != fingerprint[0] || inp.readLong() != fingerprint[1]) return@use false
                val counts = IntArray(inp.readInt()) { inp.readInt() }
                tally.seed(counts, generation, size)
                true
            }
        }.getOrDefault(false)
        if (seeded) {
            Log.i("FogRuntime", "region counts restored for $size cells")
        } else {
            tally.levels(repo)
            saveRegionCounts()
        }
    }

    private fun atlasId(tally: RegionTally): Long =
        tally.atlas.dongs.size.toLong() shl 32 or (tally.atlas.source.hashCode().toLong() and 0xffffffffL)

    /** Serialises [saveRegionCounts]: start-up, the debounced saver and onStop all write the same files. */
    private val regionCountsLock = Any()

    /** Saves the region counts with the fingerprint of the cells they cover (called when the app stops). */
    fun saveRegionCounts(): Unit = synchronized(regionCountsLock) {
        if (!initialized) return
        val tally = regions ?: return
        tally.levels(repo)  // catch up with the cells added since the last count
        val (generation, seen, counts) = tally.progress()
        val fingerprint = repo.logFingerprint(generation, seen) ?: return
        val atlasId = atlasId(tally)
        runCatching {
            val tmp = File(appContext.filesDir, "region_counts.tmp")
            java.io.DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(REGION_COUNTS_MAGIC)
                out.writeLong(atlasId)
                out.writeInt(seen)
                out.writeLong(fingerprint[0])
                out.writeLong(fingerprint[1])
                out.writeInt(counts.size)
                counts.forEach { out.writeInt(it) }
            }
            if (!tmp.renameTo(regionCountsFile)) tmp.delete()
        }.onFailure { Log.w("FogRuntime", "region counts not saved", it) }
    }

    private const val REGION_COUNTS_MAGIC = 0x46475243  // "FGRC"
    private const val REGION_COUNTS_SAVE_MS = 10_000L

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        appContext = app
        repo = ExploreRepository(FogDb.create(app), scope)
        vehicle = VehicleSignals(app)
        navi = NaviBridge(app)
        cloud = CloudSync(app, repo)
        demoPrefs = DemoPrefs(app)
        mapPrefs = MapPrefs(app)
        announcer = Announcer(app) { mapPrefs.voice.value }
        // Quiet while a voice line plays: a tick over speech only gets in the way.
        Sfx.init(enabled = { mapPrefs.sound.value && !announcer.speaking }, volume = { mapPrefs.soundVolume.value / 100f })
        stories = LocalStories(app)
        scope.launch { repo.load(beforeShown = ::seedRegionCounts) }
        // Keep the saved counts current while exploring, so a later start can reuse them.
        scope.launch { repo.version.drop(1).debounce(REGION_COUNTS_SAVE_MS).collect { saveRegionCounts() } }
        // Start parsing the atlas now, in parallel with the database load, so it is ready (or nearly)
        // when the map first asks for it; the launch loading screen waits for both.
        scope.launch { regions }
    }
}
