package com.zobo.burung.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToLong

/** What a backup holds, shown after export / import. */
data class BackupSummary(val cells: Int, val trips: Int, val landings: Int, val at: Long)

/** Every table of a backup, in memory. */
class BackupData(
    val cells: List<CellEntity>,
    val trips: List<TripEntity>,
    val landings: List<LandingEntity>,
    val stamps: List<StampEntity>,
    val track: List<TrackPointEntity>,
    val exportedAt: Long,
) {
    val summary: BackupSummary get() = BackupSummary(cells.size, trips.size, landings.size, exportedAt)
}

/**
 * The whole record (cells with visit counts, trips, drop-off points, stamps, routes) as one file.
 * Used by export / import in Settings and as the payload of [CloudSync].
 *
 * Binary and gzip-compressed (format 2). The two big tables are stored column by column, each value
 * as the difference from the one before, as zigzag varints: cells sorted by id differ by a few units
 * and track points by a few metres, so most values take one or two bytes and gzip packs the rest. A
 * real record of 61k cells and 42k track points was 4.8 MB as JSON (format 1) and 107 KB in this
 * format. Track positions are kept to 1e-7° (about 1 cm). Format 1 (JSON) files still import.
 */
object Backup {
    private const val FORMAT = "fogdrive-backup"
    private const val MAGIC = 0x4647424B  // "FGBK"
    private const val VERSION = 2
    /** Track positions in 1e-7 degree steps. */
    private const val COORD_SCALE = 1e7
    /** Row counts above this mean a corrupt file (and would ask for a huge allocation). */
    private const val MAX_ROWS = 50_000_000L

    suspend fun export(db: FogDb, file: File): BackupSummary {
        val dao = db.dao()
        val data = BackupData(
            dao.allCells(), dao.allTrips(), dao.allLandings(), dao.allStamps(), dao.allTrack(), System.currentTimeMillis(),
        )
        file.parentFile?.mkdirs()
        // Write next to the target and rename, so a crash mid-write never leaves half a backup.
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().buffered().use { write(data, it) }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        return data.summary
    }

    /** Replaces everything in [db] with the backup in [file]. Throws if the file is not a backup. */
    suspend fun import(db: FogDb, file: File): BackupSummary {
        val data = file.inputStream().buffered().use { read(it) }
        db.withTransaction {
            val dao = db.dao()
            dao.deleteCells()
            dao.deleteTrips()
            dao.deleteLandings()
            dao.deleteStamps()
            dao.deleteTrack()
            data.cells.chunked(500).forEach { dao.insertCells(it) }
            dao.putTrips(data.trips)
            dao.putLandings(data.landings)
            dao.putStamps(data.stamps)
            data.track.chunked(500).forEach { dao.putTrack(it) }
        }
        return data.summary
    }

    /** Writes [data] to [out] and closes it. */
    fun write(data: BackupData, out: OutputStream) {
        val o = DataOutputStream(GZIPOutputStream(out, 1 shl 16))
        o.writeInt(MAGIC)
        o.writeInt(VERSION)
        o.writeLong(data.exportedAt)

        val cells = data.cells.sortedBy { it.cell }
        o.varint(cells.size.toLong())
        var prev = 0L
        for (c in cells) { o.signed(c.cell - prev); prev = c.cell }
        prev = 0L
        for (c in cells) { o.signed(c.firstSeenAt - prev); prev = c.firstSeenAt }
        prev = 0L
        for (c in cells) { o.signed(c.tripId - prev); prev = c.tripId }
        for (c in cells) o.varint(c.source.toLong())
        for (c in cells) o.varint(c.visits.toLong())

        o.varint(data.trips.size.toLong())
        for (t in data.trips) {
            o.signed(t.id)
            o.writeLong(t.startedAt)
            // 0: still open; otherwise how long it ran, plus one.
            o.varint(t.endedAt?.let { (it - t.startedAt).coerceAtLeast(0) + 1 } ?: 0)
            o.varint(t.newCells.toLong())
            o.writeDouble(t.distanceMeters)
            o.writeDouble(t.newDistanceMeters)
        }
        o.varint(data.landings.size.toLong())
        for (l in data.landings) {
            o.signed(l.id); o.writeDouble(l.lat); o.writeDouble(l.lng); o.writeLong(l.at); o.signed(l.tripId)
        }
        o.varint(data.stamps.size.toLong())
        for (s in data.stamps) {
            o.signed(s.id); o.writeUTF(s.code); o.writeLong(s.at); o.signed(s.tripId); o.writeDouble(s.lat); o.writeDouble(s.lng)
        }

        // Route order (trip, then time) keeps neighbouring points close.
        val track = data.track.sortedWith(compareBy({ it.tripId }, { it.at }, { it.id }))
        o.varint(track.size.toLong())
        var id = 0L
        var trip = 0L
        var lat = 0L
        var lng = 0L
        var at = 0L
        for (p in track) {
            val la = (p.lat * COORD_SCALE).roundToLong()
            val ln = (p.lng * COORD_SCALE).roundToLong()
            o.signed(p.id - id); o.signed(p.tripId - trip); o.signed(la - lat); o.signed(ln - lng); o.signed(p.at - at)
            id = p.id; trip = p.tripId; lat = la; lng = ln; at = p.at
        }
        // Closing (not just finishing) frees the Deflater's native memory now; it also closes [out].
        o.close()
    }

    /** Reads format 2 (binary) or format 1 (JSON). Throws if the stream is neither. */
    fun read(input: InputStream): BackupData {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        buffered.mark(2)
        val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        return if (gzip) DataInputStream(GZIPInputStream(buffered, 1 shl 16)).use { readBinary(it) } else readJson(buffered)
    }

    private fun readBinary(i: DataInputStream): BackupData {
        require(i.readInt() == MAGIC) { "not a FogDrive backup" }
        require(i.readInt() <= VERSION) { "backup from a newer app version" }
        val exportedAt = i.readLong()

        val n = i.count()
        val ids = LongArray(n)
        var prev = 0L
        for (k in 0 until n) { prev += i.signed(); ids[k] = prev }
        val seen = LongArray(n)
        prev = 0L
        for (k in 0 until n) { prev += i.signed(); seen[k] = prev }
        val trips = LongArray(n)
        prev = 0L
        for (k in 0 until n) { prev += i.signed(); trips[k] = prev }
        val sources = IntArray(n) { i.varint().toInt() }
        val cells = List(n) { k -> CellEntity(ids[k], seen[k], trips[k], sources[k], i.varint().toInt()) }

        val tripRows = List(i.count()) {
            val id = i.signed()
            val startedAt = i.readLong()
            val ran = i.varint()
            TripEntity(id, startedAt, if (ran == 0L) null else startedAt + ran - 1, i.varint().toInt(), i.readDouble(), i.readDouble())
        }
        val landings = List(i.count()) { LandingEntity(i.signed(), i.readDouble(), i.readDouble(), i.readLong(), i.signed()) }
        val stamps = List(i.count()) { StampEntity(i.signed(), i.readUTF(), i.readLong(), i.signed(), i.readDouble(), i.readDouble()) }

        var id = 0L
        var trip = 0L
        var lat = 0L
        var lng = 0L
        var at = 0L
        val track = List(i.count()) {
            id += i.signed(); trip += i.signed(); lat += i.signed(); lng += i.signed(); at += i.signed()
            TrackPointEntity(id, trip, lat / COORD_SCALE, lng / COORD_SCALE, at)
        }
        return BackupData(cells, tripRows, landings, stamps, track, exportedAt)
    }

    /** Format 1: one JSON object with a row array per table. */
    private fun readJson(input: InputStream): BackupData {
        val json = JSONObject(input.bufferedReader().readText())
        require(json.optString("format") == FORMAT) { "not a FogDrive backup" }
        require(json.optInt("version") <= 1) { "backup from a newer app version" }
        fun rows(key: String) = json.optJSONArray(key) ?: JSONArray()
        fun <T> JSONArray.rows(block: (JSONArray) -> T): List<T> = List(length()) { block(getJSONArray(it)) }
        return BackupData(
            cells = rows("cells").rows { CellEntity(it.getLong(0), it.getLong(1), it.getLong(2), it.getInt(3), it.optInt(4, 1)) },
            trips = rows("trips").rows {
                TripEntity(it.getLong(0), it.getLong(1), if (it.isNull(2)) null else it.getLong(2), it.getInt(3), it.getDouble(4), it.getDouble(5))
            },
            landings = rows("landings").rows { LandingEntity(it.getLong(0), it.getDouble(1), it.getDouble(2), it.getLong(3), it.getLong(4)) },
            stamps = rows("stamps").rows { StampEntity(it.getLong(0), it.getString(1), it.getLong(2), it.getLong(3), it.getDouble(4), it.getDouble(5)) },
            track = rows("track").rows { TrackPointEntity(it.getLong(0), it.getLong(1), it.getDouble(2), it.getDouble(3), it.getLong(4)) },
            exportedAt = json.optLong("exportedAt"),
        )
    }

    // Varints: 7 bits a byte, low bits first, the high bit set while more follow. Signed values are
    // zigzag-mapped first (0, -1, 1, -2 … → 0, 1, 2, 3 …) so small negatives stay small too.
    private fun DataOutputStream.varint(value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            writeByte(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        writeByte(v.toInt())
    }

    private fun DataOutputStream.signed(value: Long) = varint((value shl 1) xor (value shr 63))

    private fun DataInputStream.varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = read()
            if (b < 0) throw EOFException("backup cut short")
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "corrupt backup" }
        }
    }

    private fun DataInputStream.signed(): Long {
        val v = varint()
        return (v ushr 1) xor -(v and 1)
    }

    private fun DataInputStream.count(): Int {
        val n = varint()
        require(n in 0..MAX_ROWS) { "corrupt backup" }
        return n.toInt()
    }
}
