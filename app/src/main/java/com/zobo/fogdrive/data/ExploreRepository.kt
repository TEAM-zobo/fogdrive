package com.zobo.fogdrive.data

import com.zobo.fogdrive.explore.CellLog
import com.zobo.fogdrive.geo.Tiles
import com.zobo.fogdrive.record.ExploreSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    private val cells: MutableSet<Long> = ConcurrentHashMap.newKeySet(INITIAL_CELLS)
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

    @Volatile
    var currentTripId: Long = 0L

    /** (generation, size) of the cell log right now. */
    fun logPosition(): Pair<Int, Int> = synchronized(lock) { resetGeneration to orderSize }

    /**
     * Order-independent fingerprint (sum and xor of mixed ids) of the first [n] logged cells, or null
     * if the log was reset since [generation]. Two sets with equal size and fingerprint are taken to
     * be the same set.
     */
    fun logFingerprint(generation: Int, n: Int): LongArray? = synchronized(lock) {
        if (generation != resetGeneration || n > orderSize) return null
        var sum = 0L
        var xor = 0L
        for (i in 0 until n) {
            val h = mix(order[i])
            sum += h
            xor = xor xor h
        }
        longArrayOf(sum, xor)
    }

    private fun mix(c: Long): Long {
        var z = c + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** [beforeShown] runs once the cells are in, before the UI is told (start-up work that needs them). */
    suspend fun load(beforeShown: () -> Unit = {}) {
        // Revisit counts only tint worn roads: read them alongside the cells, and let the map draw the
        // fog as soon as the cells are in (start-up showed the loading screen for all of it).
        val generation = synchronized(lock) { resetGeneration }
        val revisited = scope.async(Dispatchers.IO) { db.dao().revisited() }
        val all = withContext(Dispatchers.IO) { db.dao().allCellIds() }
        synchronized(lock) { for (c in all) if (cells.add(c)) append(c) }
        hasTrips = withContext(Dispatchers.IO) { db.dao().tripCount() } > 0
        beforeShown()
        bump()
        _loaded.value = true
        val stored = revisited.await()
        // Wiped meanwhile (기록 전체 삭제 right after launch): the visits read belong to deleted data.
        if (synchronized(lock) { resetGeneration } != generation) return
        // A revisit recorded meanwhile already counted the stored visits plus one.
        stored.forEach { visitCounts.merge(it.cell, it.visits) { now, old -> maxOf(now, old) } }
        revisitCounter.incrementAndGet()
    }

    fun count(): Int = synchronized(lock) { cells.size }

    override fun isExplored(cell: Long): Boolean = cell in cells  // concurrent set: no lock needed

    @Volatile private var hasTrips = true

    override fun noTripsYet(): Boolean = !hasTrips

    /** Cells driven by more than one trip, with their trip count (read live by the map). */
    private val visitCounts = ConcurrentHashMap<Long, Int>(INITIAL_CELLS / 4)
    val revisits: Map<Long, Int> get() = visitCounts
    /** Bumped when [revisits] changes (no [version] bump for that, see [revisit]): the fog redraws on it. */
    private val revisitCounter = AtomicInteger()
    val revisitVersion: Int get() = revisitCounter.get()

    override fun revisit(cells: Collection<Long>) {
        // No version bump: that would rebuild the coarse fog levels on every fix over a known road.
        // [revisitVersion] tells the map to redraw the worn roads instead.
        for (c in cells) visitCounts.merge(c, 2) { old, _ -> old + 1 }
        revisitCounter.incrementAndGet()
        val list = cells.toList()
        scope.launch(Dispatchers.IO) { list.chunked(SQL_IN_LIMIT).forEach { db.dao().addVisit(it) } }
    }

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
    }.also { hasTrips = true }

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

    suspend fun clearedSince(since: Long): List<ClearedCell> = withContext(Dispatchers.IO) { db.dao().clearedSince(since) }

    suspend fun tripTotalsSince(since: Long): TripTotals = withContext(Dispatchers.IO) { db.dao().tripTotalsSince(since) }

    /** Stored cells of a past trip, without touching the current-trip cache behind [cellsOfTrip]. */
    suspend fun storedCellsOfTrip(tripId: Long): List<Long> = withContext(Dispatchers.IO) { db.dao().cellsOfTrip(tripId) }

    suspend fun reset() {
        withContext(Dispatchers.IO) { db.clearAllTables() }
        forgetAll()
    }

    suspend fun exportTo(file: File): BackupSummary = withContext(Dispatchers.IO) { Backup.export(db, file) }

    /** Replaces all records with a backup, then reloads them (the recorder drops its trip, as on reset). */
    suspend fun importFrom(file: File): BackupSummary {
        val summary = withContext(Dispatchers.IO) { Backup.import(db, file) }
        forgetAll()
        load()
        return summary
    }

    private fun forgetAll() {
        hasTrips = false
        synchronized(lock) {
            resetGeneration++
            cells.clear()
            visitCounts.clear()
            revisitCounter.incrementAndGet()
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

    private companion object {
        /** SQLite's default bound-parameter limit is 999. */
        const val SQL_IN_LIMIT = 900
        /** Room for a long history up front: growing a concurrent set step by step while loading
         *  tens of thousands of cells took over a second at start-up. */
        const val INITIAL_CELLS = 1 shl 17
    }
}
