package com.zobo.fogdrive.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What a backup holds, shown after export / import. */
data class BackupSummary(val cells: Int, val trips: Int, val landings: Int, val at: Long)

/**
 * The whole record (cells with visit counts, trips, drop-off points, stamps, routes) as one JSON
 * file. Used by export / import in Settings and as the payload of [CloudSync].
 */
object Backup {
    private const val FORMAT = "fogdrive-backup"
    private const val VERSION = 1

    suspend fun export(db: FogDb, file: File): BackupSummary {
        val dao = db.dao()
        val cells = dao.allCells()
        val trips = dao.allTrips()
        val landings = dao.allLandings()
        val now = System.currentTimeMillis()
        val json = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", now)
            .put("cells", JSONArray().apply { cells.forEach { put(JSONArray(listOf(it.cell, it.firstSeenAt, it.tripId, it.source, it.visits))) } })
            .put("trips", JSONArray().apply {
                trips.forEach { put(JSONArray(listOf(it.id, it.startedAt, it.endedAt ?: JSONObject.NULL, it.newCells, it.distanceMeters, it.newDistanceMeters))) }
            })
            .put("landings", JSONArray().apply { landings.forEach { put(JSONArray(listOf(it.id, it.lat, it.lng, it.at, it.tripId))) } })
            .put("stamps", JSONArray().apply { dao.allStamps().forEach { put(JSONArray(listOf(it.id, it.code, it.at, it.tripId, it.lat, it.lng))) } })
            .put("track", JSONArray().apply { dao.allTrack().forEach { put(JSONArray(listOf(it.id, it.tripId, it.lat, it.lng, it.at))) } })
        file.parentFile?.mkdirs()
        // Write next to the target and rename, so a crash mid-write never leaves half a backup.
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        return BackupSummary(cells.size, trips.size, landings.size, now)
    }

    /** Replaces everything in [db] with the backup in [file]. Throws if the file is not a backup. */
    suspend fun import(db: FogDb, file: File): BackupSummary {
        val json = JSONObject(file.readText())
        require(json.optString("format") == FORMAT) { "not a FogDrive backup" }
        require(json.optInt("version") <= VERSION) { "backup from a newer app version" }
        fun rows(key: String) = json.optJSONArray(key) ?: JSONArray()
        fun JSONArray.each(block: (JSONArray) -> Unit) { for (i in 0 until length()) block(getJSONArray(i)) }
        val cells = ArrayList<CellEntity>()
        rows("cells").each { cells += CellEntity(it.getLong(0), it.getLong(1), it.getLong(2), it.getInt(3), it.optInt(4, 1)) }
        val trips = ArrayList<TripEntity>()
        rows("trips").each {
            trips += TripEntity(it.getLong(0), it.getLong(1), if (it.isNull(2)) null else it.getLong(2), it.getInt(3), it.getDouble(4), it.getDouble(5))
        }
        val landings = ArrayList<LandingEntity>()
        rows("landings").each { landings += LandingEntity(it.getLong(0), it.getDouble(1), it.getDouble(2), it.getLong(3), it.getLong(4)) }
        val stamps = ArrayList<StampEntity>()
        rows("stamps").each { stamps += StampEntity(it.getLong(0), it.getString(1), it.getLong(2), it.getLong(3), it.getDouble(4), it.getDouble(5)) }
        val track = ArrayList<TrackPointEntity>()
        rows("track").each { track += TrackPointEntity(it.getLong(0), it.getLong(1), it.getDouble(2), it.getDouble(3), it.getLong(4)) }
        db.withTransaction {
            val dao = db.dao()
            dao.deleteCells()
            dao.deleteTrips()
            dao.deleteLandings()
            dao.deleteStamps()
            dao.deleteTrack()
            cells.chunked(500).forEach { dao.insertCells(it) }
            dao.putTrips(trips)
            dao.putLandings(landings)
            dao.putStamps(stamps)
            track.chunked(500).forEach { dao.putTrack(it) }
        }
        return BackupSummary(cells.size, trips.size, landings.size, json.optLong("exportedAt"))
    }
}
