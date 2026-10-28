package com.zobo.burung

import com.zobo.burung.explore.CellLog
import com.zobo.burung.explore.RegionTally
import com.zobo.burung.geo.District
import com.zobo.burung.geo.Dong
import com.zobo.burung.geo.Outline
import com.zobo.burung.geo.Province
import com.zobo.burung.geo.RegionAtlas
import com.zobo.burung.geo.Tiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RegionTallyTest {
    private fun square(x0: Int, y0: Int, side: Int) = intArrayOf(x0, y0, x0 + side, y0, x0 + side, y0 + side, x0, y0 + side)
    private fun outline(bbox: IntArray, vararg rings: IntArray) = Outline(bbox, bbox[0], bbox[1], rings.toList())

    // Province 0: district 0 has two 100x100 동 side by side. Province 1: one 동 with a 20x20 hole.
    private val atlas = RegionAtlas(
        provinces = listOf(Province("11", "가도"), Province("21", "나도")),
        districts = listOf(District("11010", "갑구", 0), District("21010", "병군", 1)),
        dongs = listOf(
            Dong("1101051000", "가동", 0, 10_000, outline(intArrayOf(1000, 1000, 1100, 1100), square(1000, 1000, 100))),
            Dong("1101052000", "나동", 0, 10_000, outline(intArrayOf(1100, 1000, 1200, 1100), square(1100, 1000, 100))),
            Dong(
                "2101025000", "다면", 1, 9_600,
                outline(intArrayOf(2000, 2000, 2100, 2100), square(2000, 2000, 100), square(2040, 2040, 20)),
            ),
        ),
    )

    @Test
    fun locatesCellsByCentre() {
        assertEquals(0, atlas.dongOf(Tiles.pack(1000, 1000)))
        assertEquals(0, atlas.dongOf(Tiles.pack(1099, 1050)))
        assertEquals(1, atlas.dongOf(Tiles.pack(1100, 1050)))
        assertEquals(2, atlas.dongOf(Tiles.pack(2010, 2010)))
        assertEquals(-1, atlas.dongOf(Tiles.pack(2050, 2050)))  // inside the hole
        assertEquals(-1, atlas.dongOf(Tiles.pack(500, 500)))
    }

    @Test
    fun unvisitedRegionsStayClosedAndRatesAreAreaShares() {
        val cells = (0 until 50).map { Tiles.pack(1000 + it, 1000) }.toSet()
        val result = RegionTally(atlas).tally(cells)
        val province = result[0]
        val district = province.districts[0]
        assertTrue(province.visited)
        assertEquals(50.0 / 20_000, province.rate, 1e-12)
        assertEquals(1, district.visitedDongs)
        assertEquals(50.0 / 10_000, district.dongs[0].rate, 1e-12)
        assertFalse(district.dongs[1].visited)
        assertFalse(result[1].visited)
        assertEquals(setOf(0), RegionTally(atlas).visitedDongs(cells))
        assertEquals(com.zobo.burung.geo.TileBox(1000, 1000, 1200, 1100), district.bounds)
    }

    @Test
    fun levelsAndFirstEnteredDongs() {
        val tally = RegionTally(atlas)
        val old = Tiles.pack(1010, 1010)
        val trip = listOf(Tiles.pack(1011, 1010), Tiles.pack(1150, 1050), Tiles.pack(2010, 2010))
        val counts = tally.levels((trip + old).toSet())
        assertEquals(listOf(2, 1, 1), counts.dong.toList())
        assertEquals(listOf(3, 1), counts.district.toList())
        assertEquals(listOf(3, 1), counts.province.toList())
        assertEquals(3.0 / 20_000, counts.districtRate(0), 1e-12)
        // 가동 already had a cell before the trip; 나동 and 다면 were first entered on it.
        assertEquals(2, tally.firstEntered(trip, counts))
    }

    /** A growing log like the repository's: appends, then a reset that bumps the generation. */
    private class FakeLog : CellLog {
        val cells = ArrayList<Long>()
        var generation = 0
        override fun readSince(generation: Int, from: Int): CellLog.Slice {
            val start = if (generation == this.generation) from else 0
            return CellLog.Slice(this.generation, cells.size, cells.subList(start, cells.size).toLongArray())
        }
    }

    @Test
    fun countsOnlyNewCellsAndRestartsAfterReset() {
        val log = FakeLog()
        val tally = RegionTally(atlas)
        log.cells += Tiles.pack(1010, 1010)
        val first = tally.levels(log)
        assertEquals(1, first.dong[0])
        log.cells += listOf(Tiles.pack(1011, 1010), Tiles.pack(1150, 1050))
        val second = tally.levels(log)
        assertEquals(listOf(2, 1, 0), second.dong.toList())
        assertEquals(1, first.dong[0])  // earlier snapshots never change
        log.cells.clear()
        log.generation++
        log.cells += Tiles.pack(2010, 2010)
        assertEquals(listOf(0, 0, 1), tally.levels(log).dong.toList())
    }

    @Test
    fun retallyAfterResetForgetsOldCells() {
        val tally = RegionTally(atlas)
        tally.tally(setOf(Tiles.pack(1010, 1010), Tiles.pack(2010, 2010)))
        assertTrue(tally.tally(emptySet()).none { it.visited })
    }

    @Test
    fun bundledAtlasCoversTheWholeCountry() {
        val atlas = File("src/main/assets/regions/kr_regions.bin").inputStream().use { RegionAtlas.read(it) }
        assertEquals(16, atlas.provinces.size)
        assertTrue(atlas.dongs.size > 3000)
        fun where(lat: Double, lng: Double): String {
            val dong = atlas.dongs[atlas.dongOf(Tiles.cellOf(lat, lng))]
            val district = atlas.districts[dong.district]
            return "${atlas.provinces[district.province].name} ${district.name} ${dong.name}"
        }
        assertTrue(where(37.5446, 127.0559).startsWith("서울특별시 성동구"))
        assertTrue(where(33.4996, 126.5312).startsWith("제주특별자치도 제주시"))
        assertTrue(where(35.1796, 129.0756).startsWith("부산광역시"))
    }
}
