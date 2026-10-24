package com.zobo.fogdrive.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

object CellSource {
    const val DRIVE = 0
    const val LANDING = 1
    const val SEED = 2
    const val ARRIVAL = 3
}

@Entity(tableName = "cells")
data class CellEntity(
    @PrimaryKey val cell: Long,
    val firstSeenAt: Long,
    val tripId: Long,
    val source: Int,
)

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val newCells: Int = 0,
    val distanceMeters: Double = 0.0,
    val newDistanceMeters: Double = 0.0,
)

@Entity(tableName = "landings")
data class LandingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val at: Long,
    val tripId: Long,
)

@Entity(tableName = "stamps")
data class StampEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val at: Long,
    val tripId: Long,
    val lat: Double,
    val lng: Double,
)

/** The driven route, sampled every ~20 m while a trip is active (from schema v3). */
@Entity(tableName = "track", indices = [Index("tripId")])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val lat: Double,
    val lng: Double,
    val at: Long,
)

@Dao
interface FogDao {
    @Query("SELECT cell FROM cells")
    suspend fun allCellIds(): List<Long>

    @Query("SELECT cell FROM cells WHERE tripId = :tripId")
    suspend fun cellsOfTrip(tripId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCells(cells: List<CellEntity>)

    @Query("SELECT COUNT(*) FROM cells WHERE firstSeenAt >= :since AND source != 2")
    fun countCellsSince(since: Long): Flow<Int>

    @Insert
    suspend fun insertTrip(trip: TripEntity): Long

    @Update
    suspend fun updateTrip(trip: TripEntity)

    @Query("SELECT * FROM trips ORDER BY startedAt DESC LIMIT 100")
    fun trips(): Flow<List<TripEntity>>

    @Insert
    suspend fun insertLanding(landing: LandingEntity): Long

    @Query("SELECT * FROM landings ORDER BY at DESC")
    fun landings(): Flow<List<LandingEntity>>

    @Insert
    suspend fun insertStamp(stamp: StampEntity): Long

    @Query("SELECT * FROM stamps ORDER BY at DESC")
    fun stamps(): Flow<List<StampEntity>>

    @Insert
    suspend fun insertTrackPoint(point: TrackPointEntity)

    @Query("SELECT * FROM track WHERE tripId = :tripId ORDER BY at, id")
    suspend fun trackOf(tripId: Long): List<TrackPointEntity>
}

@Database(
    entities = [CellEntity::class, TripEntity::class, LandingEntity::class, StampEntity::class, TrackPointEntity::class],
    version = 3,
)
abstract class FogDb : RoomDatabase() {
    abstract fun dao(): FogDao

    companion object {
        fun create(context: Context): FogDb =
            Room.databaseBuilder(context, FogDb::class.java, "fogdrive.db")
                .addMigrations(MIGRATION_2_3)
                // v2 moved the grid from zoom 19 to 20; v1 pre-release data is simply discarded.
                .fallbackToDestructiveMigrationFrom(1)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()

        /** v3 adds the route table; everything recorded so far is kept. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `track` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`tripId` INTEGER NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, `at` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_tripId` ON `track` (`tripId`)")
            }
        }
    }
}
