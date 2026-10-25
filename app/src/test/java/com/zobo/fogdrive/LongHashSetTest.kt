package com.zobo.fogdrive

import com.zobo.fogdrive.data.LongHashSet
import com.zobo.fogdrive.data.forEachValue
import com.zobo.fogdrive.geo.Tiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** [LongHashSet] against a HashSet, through growth and clearing, and read while being written. */
class LongHashSetTest {
    @Test
    fun matchesAHashSet() {
        val rnd = Random(7)
        val set = LongHashSet()
        val ref = HashSet<Long>()
        repeat(50_000) {
            // Packed cells around Seoul, so neighbours (the hard case for a weak hash) are common.
            val c = Tiles.pack(894_000 + rnd.nextInt(400), 406_000 + rnd.nextInt(400))
            assertEquals(ref.add(c), set.add(c))
        }
        assertEquals(ref.size, set.size)
        for (c in ref) assertTrue(set.has(c))
        repeat(10_000) {
            val c = Tiles.pack(900_000 + rnd.nextInt(400), 406_000 + rnd.nextInt(400))
            assertEquals(c in ref, set.has(c))
        }
        assertEquals(ref, set.toSet())
        val seen = HashSet<Long>()
        set.forEachValue { seen.add(it) }
        assertEquals(ref, seen)
    }

    @Test
    fun clearEmptiesAndStaysUsable() {
        val set = LongHashSet(4)
        for (k in 0L until 1_000L) set.add(k)
        set.clear()
        assertEquals(0, set.size)
        assertFalse(set.has(5))
        assertFalse(set.iterator().hasNext())
        assertTrue(set.add(5))
        assertTrue(set.has(5))
    }

    @Test
    fun readersNeverMissWhatWasAddedBeforeThemOrCrashWhileItGrows() {
        val set = LongHashSet(16)
        val written = java.util.concurrent.atomic.AtomicLong(-1)
        val done = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val readers = List(3) {
            Thread {
                try {
                    val rnd = Random(it.toLong())
                    while (!done.get()) {
                        val upTo = written.get()
                        if (upTo < 0) continue
                        val k = (rnd.nextDouble() * (upTo + 1)).toLong()
                        check(set.has(k)) { "missing $k (written up to $upTo)" }
                        set.forEachValue { v -> check(v >= 0) }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }.apply { start() }
        }
        for (k in 0L until 200_000L) {
            set.add(k)
            written.set(k)
        }
        done.set(true)
        readers.forEach { it.join() }
        failure.get()?.let { throw it }
        assertEquals(200_000, set.size)
    }
}
