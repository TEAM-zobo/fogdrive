package com.zobo.burung

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

/** [RegionAtlas.dongOf] (sorted bucket keys + binary search) against a brute-force scan of every 동. */
class RegionIndexTest {
    private fun outline(vararg rings: IntArray): Outline {
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var y1 = Int.MIN_VALUE
        for (r in rings) for (i in r.indices step 2) {
            x0 = minOf(x0, r[i]); x1 = maxOf(x1, r[i]); y0 = minOf(y0, r[i + 1]); y1 = maxOf(y1, r[i + 1])
        }
        return Outline(intArrayOf(x0, y0, x1, y1), x0, y0, rings.toList())
    }

    private fun rect(x0: Int, y0: Int, x1: Int, y1: Int) = intArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)

    // Base far from the origin so bucket keys use high bits too (zoom-13 buckets are 128 tiles wide).
    private val bx = 893_000
    private val by = 406_000

    private val dongs = listOf(
        // 0: spans 5x3 buckets.
        Dong("1", "큰동", 0, 0, outline(rect(bx + 10, by + 20, bx + 500, by + 300))),
        // 1: wholly inside 큰동, so index order means it never wins (shadowed).
        Dong("2", "그림자동", 0, 0, outline(rect(bx + 100, by + 100, bx + 200, by + 200))),
        // 2: L-shape overlapping 큰동, its bbox's concave corner empty.
        Dong(
            "3", "기역동", 0, 0,
            outline(intArrayOf(bx + 450, by + 250, bx + 900, by + 250, bx + 900, by + 700, bx + 800, by + 700, bx + 800, by + 350, bx + 450, by + 350)),
        ),
        // 3: triangle crossing bucket edges, bbox overlapping 기역동's.
        Dong("4", "세모동", 0, 0, outline(intArrayOf(bx + 0, by + 400, bx + 700, by + 450, bx + 120, by + 900))),
        // 4: square with a hole, 5: an island filling part of that hole.
        Dong("5", "구멍동", 0, 0, outline(rect(bx + 1000, by + 1000, bx + 1400, by + 1400), rect(bx + 1100, by + 1100, bx + 1300, by + 1300))),
        Dong("6", "섬동", 0, 0, outline(rect(bx + 1150, by + 1150, bx + 1250, by + 1250))),
        // 6: lone 동 in its own bucket, far away.
        Dong("7", "외딴동", 0, 0, outline(rect(bx + 5000, by + 5000, bx + 5010, by + 5010))),
    )

    private val atlas = RegionAtlas(listOf(Province("11", "도")), listOf(District("11010", "구", 0)), dongs)

    private fun bruteForce(cell: Long): Int {
        val cx = Tiles.x(cell) + 0.5
        val cy = Tiles.y(cell) + 0.5
        return dongs.indexOfFirst { it.outline.contains(cx, cy) }
    }

    @Test
    fun dongOfMatchesBruteForce() {
        val seen = HashSet<Int>()
        for (x in bx - 200 until bx + 1600 step 3) {
            for (y in by - 200 until by + 1600 step 3) {
                val cell = Tiles.pack(x, y)
                val expected = bruteForce(cell)
                assertEquals("cell ($x, $y)", expected, atlas.dongOf(cell))
                seen += expected
            }
        }
        // The sample hit every 동 but the shadowed and far ones, and cells outside every 동.
        assertTrue(seen.toString(), seen.containsAll(listOf(-1, 0, 2, 3, 4, 5)))
        assertFalse(1 in seen)
    }

    @Test
    fun loneAndEmptyBuckets() {
        assertEquals(6, atlas.dongOf(Tiles.pack(bx + 5005, by + 5005)))
        // Same bucket as 외딴동, outside it.
        assertEquals(-1, atlas.dongOf(Tiles.pack(bx + 5020, by + 5020)))
        // Buckets with no 동 at all: before, between and after the indexed keys.
        assertEquals(-1, atlas.dongOf(Tiles.pack(0, 0)))
        assertEquals(-1, atlas.dongOf(Tiles.pack(bx + 3000, by + 3000)))
        assertEquals(-1, atlas.dongOf(Tiles.pack((1 shl 20) - 1, (1 shl 20) - 1)))
        // Edge cells of 큰동 (outline [bx+10, bx+500] x [by+20, by+300]).
        assertEquals(0, atlas.dongOf(Tiles.pack(bx + 10, by + 20)))
        assertEquals(0, atlas.dongOf(Tiles.pack(bx + 449, by + 299)))
        assertEquals(-1, atlas.dongOf(Tiles.pack(bx + 9, by + 20)))
        // Hole of 구멍동 outside 섬동, and 섬동 itself.
        assertEquals(-1, atlas.dongOf(Tiles.pack(bx + 1120, by + 1120)))
        assertEquals(5, atlas.dongOf(Tiles.pack(bx + 1200, by + 1200)))
        assertEquals(4, atlas.dongOf(Tiles.pack(bx + 1050, by + 1050)))
    }

    @Test
    fun emptyAtlas() {
        val empty = RegionAtlas(emptyList(), emptyList(), emptyList())
        assertEquals(-1, empty.dongOf(Tiles.pack(bx, by)))
    }
}
