package com.zobo.fogdrive.data

import com.zobo.fogdrive.explore.CellLog
import com.zobo.fogdrive.geo.Tiles
import com.zobo.fogdrive.record.ExploreSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Explored cells live in memory (fast lookups for the fog overlay and detour planner)
 * and are persisted to Room on a background dispatcher. Everything stays on the device.
 */
class ExploreRepository(private val db: FogDb, private val scope: CoroutineScope) : ExploreSink, CellLog {
    private val lock = Any()
    /**
     * Concurrent so the fog overlay can read the live set at full zoom (see [cellsAtLevel]) without
     * a copy; writes still go through [lock] together with the trip cache and [order].
     */
    private val cells: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val liveCells: Set<Long> = Collections.unmodifiableSet(cells)
    /** [cells] in the order they were added, so region counts read only what is new ([readSince]). */
    private var order = LongArray(4096)
    private var orderSize = 0

    private fun append(c: Long) {
        if (orderSize == order.size) order = order.copyOf(order.size * 2)
        order[orderSize++] = c
    }

    override fun readSince(generation: Int, from: Int): CellLog.Slice = synchronized(lock) {
        val start = if (generation == resetGeneration) from.coerceIn(0, orderSize) else 0
        CellLog.Slice(resetGeneration, orderSize, order.copyOfRange(start, orderSize))
    }
    private val levelCache = HashMap<Int, Pair<Int, Set<Long>>>()
    /** Cells first cleared on trip [tripCellsOf]: the stored ones (once [tripCellsLoaded]) plus every
     *  later reveal, so a lookup never misses cells whose async insert hasn't landed yet. */
    private val tripCells = HashSet<Long>()
    private var tripCellsOf = 0L
    private var tripCellsLoaded = false
    /** Bumped by [reset], so reads that started before it are discarded. */
    private var resetGeneration = 0

    private val _version = MutableStateFlow(0)
    /** Bumped whenever the explored set changes; UI redraws the fog on change. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _resets = MutableStateFlow(0)
    /** Bumped after [reset] wipes every record, so the recorder can drop the deleted trip. */
    val resets: StateFlow<Int> = _resets.asStateFlow()

    val trips: Flow<List<TripEntity>> = db.dao().trips()
    val landings: Flow<List<LandingEntity>> = db.dao().landings()
    val stamps: Flow<List<StampEntity>> = db.dao().stamps()
    fun cellsSince(since: Long): Flow<Int> = db.dao().countCellsSince(since)

    @Volatile
    var currentTripId: Long = 0L

    suspend fun load() {
        val all = withContext(Dispatchers.IO) { db.dao().allCellIds() }
        synchronized(lock) { for (c in all) if (cells.add(c)) append(c) }
        bump()
        _loaded.value = true
    }

    fun count(): Int = synchronized(lock) { cells.size }

    override fun isExplored(cell: Long): Boolean = cell in cells  // concurrent set: no lock needed

    override fun reveal(newCells: Collection<Long>, source: Int): Int {
        val added = ArrayList<Long>()
        val trip = currentTripId
        synchronized(lock) {
            for (c in newCells) if (cells.add(c)) {
                added.add(c)
                append(c)
            }
            if (added.isNotEmpty()) {
                if (trip != tripCellsOf) {
                    tripCells.clear()
                    tripCellsOf = trip
                    tripCellsLoaded = false
                }
                tripCells.addAll(added)
            }
        }
        if (added.isNotEmpty()) {
            bump()
            val now = System.currentTimeMillis()
            scope.launch(Dispatchers.IO) {
                db.dao().insertCells(added.map { CellEntity(it, now, trip, source) })
            }
        }
        return added.size
    }

    /** Explored set aggregated [dz] levels up (any explored child marks the parent). Cached per version. */
    fun cellsAtLevel(dz: Int): Set<Long> {
        // Level 0 is the live set itself (no 100k-entry copy per change); coarser levels are derived.
        if (dz == 0) return liveCells
        return synchronized(lock) {
            val v = _version.value
            levelCache[dz]?.takeIf { it.first == v }?.second ?: run {
                val set: Set<Long> = cells.mapTo(HashSet()) { Tiles.parent(it, dz) }
                levelCache[dz] = v to set
                set
            }
        }
    }

    /** Cells first cleared on trip [tripId]. The table is read once per trip; later reveals come from memory. */
    suspend fun cellsOfTrip(tripId: Long): Set<Long> {
        val generation = synchronized(lock) {
            if (tripCellsLoaded && tripCellsOf == tripId) return HashSet(tripCells)
            resetGeneration
        }
        val stored = withContext(Dispatchers.IO) { db.dao().cellsOfTrip(tripId) }
        synchronized(lock) {
            // Records were wiped while the table was read: what we read no longer exists.
            if (generation != resetGeneration) return emptySet()
            if (tripCellsOf != tripId) {
                // A newer trip started while the table was read: don't drop its in-memory cells.
                if (tripId != currentTripId) return HashSet(stored)
                tripCells.clear()
                tripCellsOf = tripId
            }
            tripCells.addAll(stored)
            tripCellsLoaded = true
            return HashSet(tripCells)
        }
    }

    suspend fun startTrip(at: Long): Long = withContext(Dispatchers.IO) {
        db.dao().insertTrip(TripEntity(startedAt = at))
    }

    suspend fun saveTrip(trip: TripEntity) = withContext(Dispatchers.IO) { db.dao().updateTrip(trip) }

    suspend fun addLanding(lat: Double, lng: Double, at: Long, tripId: Long) = withContext(Dispatchers.IO) {
        db.dao().insertLanding(LandingEntity(lat = lat, lng = lng, at = at, tripId = tripId))
    }

    suspend fun addStamp(code: String, lat: Double, lng: Double, at: Long, tripId: Long) = withContext(Dispatchers.IO) {
        db.dao().insertStamp(StampEntity(code = code, at = at, tripId = tripId, lat = lat, lng = lng))
    }

    /** Appends one route point to trip [tripId] (fire and forget, like cells). */
    fun addTrackPoint(tripId: Long, lat: Double, lng: Double, at: Long) {
        scope.launch(Dispatchers.IO) { db.dao().insertTrackPoint(TrackPointEntity(tripId = tripId, lat = lat, lng = lng, at = at)) }
    }

    suspend fun trackOf(tripId: Long): List<TrackPointEntity> = withContext(Dispatchers.IO) { db.dao().trackOf(tripId) }

    /** Stored cells of a past trip, without touching the current-trip cache behind [cellsOfTrip]. */
    suspend fun storedCellsOfTrip(tripId: Long): List<Long> = withContext(Dispatchers.IO) { db.dao().cellsOfTrip(tripId) }

    suspend fun reset() {
        withContext(Dispatchers.IO) { db.clearAllTables() }
        synchronized(lock) {
            resetGeneration++
            cells.clear()
            orderSize = 0
            levelCache.clear()
            tripCells.clear()
            tripCellsOf = 0L
            tripCellsLoaded = false
        }
        bump()
        _resets.update { it + 1 }
    }

    private fun bump() {
        _version.update { it + 1 }
    }
}
