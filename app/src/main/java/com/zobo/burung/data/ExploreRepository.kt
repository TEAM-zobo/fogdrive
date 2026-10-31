package com.zobo.burung.data

import com.zobo.burung.explore.CellLog
import com.zobo.burung.explore.Milestones
import com.zobo.burung.explore.StampProgress
import com.zobo.burung.geo.RegionAtlas
import com.zobo.burung.explore.Progress
import com.zobo.burung.geo.Tiles
import com.zobo.burung.record.ExploreSink
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

/**
 * Explored cells live in memory (fast lookups for the fog overlay and detour planner)
 * and are persisted to Room on a background dispatcher. Everything stays on the device.
 */
class ExploreRepository(private val db: FogDb, private val scope: CoroutineScope) : ExploreSink, CellLog {
    private val lock = Any()
    /**
     * Readable without a lock, so the fog overlay can use the live set at full zoom (see
     * [cellsAtLevel]) without a copy; writes go through [lock] together with the trip cache and [order].
     */
    private val cells = LongHashSet(INITIAL_CELLS)
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
    private val levelCache = HashMap<Int, Pair<Int, LongHashSet>>()
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
        // Revisit counts are read alongside the cells; the map draws the veil as soon as the cells are
        // in (start-up showed the loading screen for all of it).
        val generation = synchronized(lock) { resetGeneration }
        val revisited = scope.async(Dispatchers.IO) { db.dao().revisited() }
        val all = withContext(Dispatchers.IO) { db.dao().allCellIds() }
        synchronized(lock) { for (c in all) if (cells.add(c)) append(c) }
        hasTrips = withContext(Dispatchers.IO) { db.dao().tripCount() } > 0
        beforeShown()
        bump()
        _loaded.value = true
        // Routes after the UI is told (the map opens the veil along them as they come in): with a long
        // history they are the bulk of the stored rows.
        val points = withContext(Dispatchers.IO) { db.dao().trackPoints() }
        val spotCells = withContext(Dispatchers.IO) { db.dao().spotCells() }
        synchronized(lock) {
            // Wiped meanwhile (기록 전체 삭제 right after launch): the rows read belong to deleted data.
            if (resetGeneration == generation) tracks.replaceAll(points, spotCells)
        }
        val stored = revisited.await()
        synchronized(visitLock) {
            // Wiped meanwhile (기록 전체 삭제 right after launch): the visits read belong to deleted data.
            if (synchronized(lock) { resetGeneration } == generation) {
                stored.forEach { visitCounts[it.cell] = it.visits }
                // Repeats recorded while loading go on top: a cell stored with none was driven once.
                pendingVisits.forEach { (c, k) -> visitCounts.add(c, by = k, initial = 1 + k) }
                // Now that the stored counts are read, store the repeats (once per repeat).
                val byRepeats = pendingVisits.entries.groupBy({ it.value }, { it.key })
                for ((k, list) in byRepeats) repeat(k) { storeVisits(list) }
            }
            pendingVisits.clear()
            visitsLoaded = true
        }
    }

    fun count(): Int = synchronized(lock) { cells.size }

    override fun isExplored(cell: Long): Boolean = cells.has(cell)  // lock-free read

    @Volatile private var hasTrips = true

    override fun noTripsYet(): Boolean = !hasTrips

    /** Cells driven by more than one trip, with their trip count. */
    private val visitCounts = LongIntMap(INITIAL_CELLS / 4)
    /** Guards the switch from [pendingVisits] to [visitCounts] once the stored counts are loaded. */
    private val visitLock = Any()
    @Volatile private var visitsLoaded = false
    /** Repeats recorded before the stored counts were read (see [revisit] and [load]). */
    private val pendingVisits = HashMap<Long, Int>()

    override fun revisit(cells: Collection<Long>) {
        // No version bump: that would rebuild the coarse fog levels on every fix over a known road.
        val loaded = synchronized(visitLock) {
            if (visitsLoaded) {
                // A cell missing from the map was driven once before (only repeats are stored).
                for (c in cells) visitCounts.add(c, by = 1, initial = 2)
            } else {
                // The stored counts aren't in yet: count the repeats, added on top once they are.
                // Stored only then too, or the read in [load] could already include them (counted twice).
                for (c in cells) pendingVisits.merge(c, 1, Int::plus)
            }
            visitsLoaded
        }
        if (loaded) storeVisits(cells.toList())
    }

    private fun storeVisits(list: List<Long>) {
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
            // Driven cells show through the route's band; the others (drop-off, arrival, seeding) as spots.
            if (source != CellSource.DRIVE) tracks.addSpots(added)
            bump()
            val now = System.currentTimeMillis()
            scope.launch(Dispatchers.IO) {
                db.dao().insertCells(added.map { CellEntity(it, now, trip, source) })
            }
        }
        return added.size
    }

    /**
     * Explored set aggregated [dz] levels up (any explored child marks the parent). Cached per
     * version. Read only: level 0 is the live set itself (no 100k-entry copy per change).
     */
    fun cellsAtLevel(dz: Int): LongHashSet {
        if (dz == 0) return cells
        return synchronized(lock) {
            val v = _version.value
            levelCache[dz]?.takeIf { it.first == v }?.second ?: run {
                // About a quarter as many parents per level up: start near that size.
                val set = LongHashSet(cells.size shr (dz * 2).coerceAtMost(8))
                cells.forEachValue { set.add(Tiles.parent(it, dz)) }
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

    /** Every trip's route, kept in memory: the map opens the ground along it. */
    val tracks = Tracks()

    /** Appends one route point to trip [tripId] (fire and forget, like cells). */
    fun addTrackPoint(tripId: Long, lat: Double, lng: Double, at: Long) {
        tracks.add(tripId, lat, lng)
        scope.launch(Dispatchers.IO) { db.dao().insertTrackPoint(TrackPointEntity(tripId = tripId, lat = lat, lng = lng, at = at)) }
    }

    suspend fun trackOf(tripId: Long): List<TrackPointEntity> = withContext(Dispatchers.IO) { db.dao().trackOf(tripId) }

    suspend fun clearedSince(since: Long): List<ClearedCell> = withContext(Dispatchers.IO) { db.dao().clearedSince(since) }

    suspend fun newestCleared(n: Int): List<ClearedCell> = withContext(Dispatchers.IO) { db.dao().newestCleared(n) }

    suspend fun tripTotalsSince(since: Long): TripTotals = withContext(Dispatchers.IO) { db.dao().tripTotalsSince(since) }

    /** Codes of the stamps ever earned. */
    suspend fun stampCodes(): Set<String> = withContext(Dispatchers.IO) { db.dao().stampCodes().toSet() }

    /**
     * Where the driver stands on the milestone stamps. 읍·면·동 and 시·도 count only places reached by
     * driving or getting off: the first-run seed around the navigation's places doesn't count.
     */
    suspend fun stampProgress(atlas: RegionAtlas?, now: Long = System.currentTimeMillis()): StampProgress =
        withContext(Dispatchers.IO) {
            val dao = db.dao()
            val (days, streak) = Milestones.weeks(dao.tripStarts(), now)
            val km = (dao.tripTotalsSince(0).distanceMeters / 1000).toInt()
            var dongs = 0
            var provinces = 0
            if (atlas != null) {
                val d = HashSet<Int>()
                for (c in dao.drivenCells()) atlas.dongOf(c).takeIf { it >= 0 }?.let(d::add)
                dongs = d.size
                provinces = d.mapTo(HashSet()) { atlas.districts[atlas.dongs[it].district].province }.size
            }
            StampProgress(days, streak, km, dongs, provinces)
        }

    /** Total experience (see [Progress.xp]): every cell, every trip's distance, drop-off and stamp. */
    suspend fun xp(): Int {
        val cells = count()
        return withContext(Dispatchers.IO) {
            val dao = db.dao()
            Progress.xp(cells, dao.landingCount(), dao.stampCount(), dao.stampKindCount(), dao.tripTotalsSince(0).distanceMeters)
        }
    }

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
            synchronized(visitLock) {
                visitCounts.clear()
                pendingVisits.clear()
            }
            orderSize = 0
            levelCache.clear()
            tripCells.clear()
            tripCellsOf = 0L
            tripCellsLoaded = false
        }
        tracks.clear()
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
