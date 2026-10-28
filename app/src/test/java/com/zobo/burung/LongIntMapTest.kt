package com.zobo.burung

import com.zobo.burung.data.LongIntMap
import com.zobo.burung.geo.Tiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** [LongIntMap] against a HashMap: counters (add), plain sets, growth, clearing and forEach. */
class LongIntMapTest {
    @Test
    fun countsLikeAHashMap() {
        val rnd = Random(3)
        val map = LongIntMap()
        val ref = HashMap<Long, Int>()
        repeat(60_000) {
            // Cells along diagonals: the keys a Long hash (x ^ y) piles into one bucket.
            val d = rnd.nextInt(300)
            val c = Tiles.pack(894_000 + d, 406_000 + d + rnd.nextInt(3))
            if (rnd.nextInt(10) == 0) {
                map[c] = 5
                ref[c] = 5
            } else {
                map.add(c, by = 1, initial = 2)
                ref.merge(c, 2) { old, _ -> old + 1 }
            }
        }
        assertEquals(ref.size, map.size)
        for ((c, n) in ref) assertEquals(n, map[c])
        assertEquals(0, map[Tiles.pack(1, 1)])
        val seen = HashMap<Long, Int>()
        map.forEach { k, v -> seen[k] = v }
        assertEquals(ref, seen)
    }

    @Test
    fun clearEmptiesAndStaysUsable() {
        val map = LongIntMap(4)
        for (k in 0L until 500L) map.add(k, by = 1, initial = 1)
        map.clear()
        assertTrue(map.isEmpty())
        assertEquals(0, map[7])
        map.add(7, by = 3, initial = 4)
        map.add(7, by = 3, initial = 4)
        assertEquals(7, map[7])
    }
}
