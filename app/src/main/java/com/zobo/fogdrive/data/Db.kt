package com.zobo.fogdrive.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

object CellSource {
    const val DRIVE = 0
    const val LANDING = 1
    const val SEED = 2
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

@Dao
interface FogDao {
    @Query("SELECT cell FROM cells")
    suspend fun allCellIds(): List<Long>

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
}

@Database(
    entities = [CellEntity::class, TripEntity::class, LandingEntity::class, StampEntity::class],
    version = 1,
)
abstract class FogDb : RoomDatabase() {
    abstract fun dao(): FogDao

    companion object {
        fun create(context: Context): FogDb =
            Room.databaseBuilder(context, FogDb::class.java, "fogdrive.db").build()
    }
}
