package com.zobo.fogdrive

import com.zobo.fogdrive.data.Backup
import com.zobo.fogdrive.data.BackupData
import com.zobo.fogdrive.data.CellEntity
import com.zobo.fogdrive.data.LandingEntity
import com.zobo.fogdrive.data.StampEntity
import com.zobo.fogdrive.data.TrackPointEntity
import com.zobo.fogdrive.data.TripEntity
import com.zobo.fogdrive.geo.Tiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** [Backup] format 2: every table survives a write and read, and the file stays small. */
class BackupTest {
    private fun sample(): BackupData {
        val cells = (0 until 5_000).map { k ->
            CellEntity(Tiles.pack(894_000 + k % 100, 406_000 + k / 100), 1_760_000_000_000L + k * 1_000L, 1L + k / 1_000, k % 4, 1 + k % 7)
        }.shuffled(java.util.Random(1))
        val trips = listOf(
            TripEntity(1, 1_760_000_000_000L, 1_760_000_600_000L, 1200, 12_345.6, 4_321.0),
            TripEntity(2, 1_760_001_000_000L, null, 0, 0.0, 0.0),
        )
        val landings = listOf(LandingEntity(1, 37.5465123, 127.0451987, 1_760_000_600_000L, 1))
        val stamps = listOf(StampEntity(1, "NIGHT", 1_760_000_300_000L, 1, 37.55, 127.04))
        val track = (0 until 3_000).map { k ->
            TrackPointEntity(k + 1L, 1L + k / 1_500, 37.54 + k * 0.00011, 127.04 - k * 0.00007, 1_760_000_000_000L + k * 1_000L)
        }
        return BackupData(cells, trips, landings, stamps, track, 1_760_002_000_000L)
    }

    private fun roundTrip(data: BackupData): Pair<BackupData, Int> {
        val bytes = ByteArrayOutputStream().also { Backup.write(data, it) }.toByteArray()
        return Backup.read(ByteArrayInputStream(bytes)) to bytes.size
    }

    @Test
    fun everyTableSurvivesTheRoundTrip() {
        val data = sample()
        val (back, _) = roundTrip(data)
        assertEquals(data.exportedAt, back.exportedAt)
        assertEquals(data.cells.sortedBy { it.cell }, back.cells)
        assertEquals(data.trips, back.trips)
        assertEquals(data.landings, back.landings)
        assertEquals(data.stamps, back.stamps)
        assertEquals(data.track.size, back.track.size)
        data.track.zip(back.track).forEach { (a, b) ->
            assertEquals(a.copy(lat = 0.0, lng = 0.0), b.copy(lat = 0.0, lng = 0.0))
            // Positions are kept to 1e-7 degrees.
            assertTrue(abs(a.lat - b.lat) <= 0.6e-7 && abs(a.lng - b.lng) <= 0.6e-7)
        }
    }

    @Test
    fun anEmptyRecordRoundTrips() {
        val (back, _) = roundTrip(BackupData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0L))
        assertTrue(back.cells.isEmpty() && back.trips.isEmpty() && back.track.isEmpty())
    }

    @Test
    fun cellsAndTrackTakeAFewBytesEach() {
        val data = sample()
        val (_, size) = roundTrip(data)
        // 8 000 rows: JSON took ~40 bytes a row; deltas, varints and gzip leave well under 4.
        assertTrue("backup is $size bytes", size < (data.cells.size + data.track.size) * 4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aForeignGzipFileIsRejected() {
        val bytes = ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write("hello".toByteArray()) }
        }.toByteArray()
        Backup.read(ByteArrayInputStream(bytes))
    }
}
