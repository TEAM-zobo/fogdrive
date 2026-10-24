package com.zobo.fogdrive.data

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

/**
 * Explored cells live in memory (fast lookups for the fog overlay and detour planner)
 * and are persisted to Room on a background dispatcher. Everything stays on the device.
 */
class ExploreRepository(private val db: FogDb, private val scope: CoroutineScope) : ExploreSink {
    private val lock = Any()
    private val cells = HashSet<Long>()
    private val levelCache = HashMap<Int, Pair<Int, Set<Long>>>()
    /** Cells first cleared on trip [tripCellsOf]: the stored ones (once [tripCellsLoaded]) plus every
     *  later reveal, so a lookup never misses cells whose async insert hasn't landed yet. */
    private val tripCells = HashSet<Long>()
    private var tripCellsOf = 0L
    private var tripCellsLoaded = false

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
        synchronized(lock) { cells.addAll(all) }
        bump()
        _loaded.value = true
    }

    fun count(): Int = synchronized(lock) { cells.size }

    override fun isExplored(cell: Long): Boolean = synchronized(lock) { cell in cells }

    override fun reveal(newCells: Collection<Long>, source: Int): Int {
        val added = ArrayList<Long>()
        val trip = currentTripId
        synchronized(lock) {
            for (c in newCells) if (cells.add(c)) added.add(c)
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
    fun cellsAtLevel(dz: Int): Set<Long> = synchronized(lock) {
        val v = _version.value
        levelCache[dz]?.takeIf { it.first == v }?.second ?: run {
            val set: Set<Long> = if (dz == 0) HashSet(cells) else cells.mapTo(HashSet()) { Tiles.parent(it, dz) }
            levelCache[dz] = v to set
            set
        }
    }

    /** Cells first cleared on trip [tripId]. The table is read once per trip; later reveals come from memory. */
    suspend fun cellsOfTrip(tripId: Long): Set<Long> {
        synchronized(lock) { if (tripCellsLoaded && tripCellsOf == tripId) return HashSet(tripCells) }
        val stored = withContext(Dispatchers.IO) { db.dao().cellsOfTrip(tripId) }
        synchronized(lock) {
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

    suspend fun reset() {
        withContext(Dispatchers.IO) { db.clearAllTables() }
        synchronized(lock) {
            cells.clear()
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
