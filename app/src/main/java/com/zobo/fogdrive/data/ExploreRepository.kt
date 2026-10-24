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
        synchronized(lock) { for (c in newCells) if (cells.add(c)) added.add(c) }
        if (added.isNotEmpty()) {
            bump()
            val now = System.currentTimeMillis()
            val trip = currentTripId
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

    /** Explored child cells inside one parent tile [dz] levels up. */
    fun exploredIn(parent: Long, dz: Int): Int = synchronized(lock) {
        val side = 1 shl dz
        val x0 = Tiles.x(parent) shl dz
        val y0 = Tiles.y(parent) shl dz
        var n = 0
        for (dx in 0 until side) for (dy in 0 until side) if (Tiles.pack(x0 + dx, y0 + dy) in cells) n++
        n
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
        }
        bump()
        _resets.update { it + 1 }
    }

    private fun bump() {
        _version.update { it + 1 }
    }
}
