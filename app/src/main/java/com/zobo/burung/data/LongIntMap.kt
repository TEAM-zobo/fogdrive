package com.zobo.burung.data

/**
 * Cell id → count (revisits) in two parallel open-addressing arrays, like [LongHashSet]: 12 bytes a
 * slot instead of a boxed Long, a boxed Integer and a hash node per entry, and lookups that neither
 * box nor collide. A ConcurrentHashMap<Long, Int> hashes a Long as `x ^ y` of its halves, so packed
 * cells along a diagonal all share a bucket; the buckets turned into trees and the worn-road lookups
 * took about a quarter of the fog renderer's time.
 *
 * One writer at a time (callers hold their own lock); readers need no lock. Growing publishes a new,
 * fully built table at once. A reader may see a count from just before an update, or a new key whose
 * count is not visible yet (read as absent): harmless for drawing.
 */
class LongIntMap(expected: Int = 16) {
    @PublishedApi internal class Table(val keys: LongArray, val values: IntArray)

    @Volatile private var table = newTable(capacityFor(expected))
    @Volatile private var count = 0

    val size: Int get() = count

    fun isEmpty(): Boolean = count == 0

    /** The count for [key], or 0 if it has none. */
    operator fun get(key: Long): Int {
        val t = table
        val keys = t.keys
        val mask = keys.size - 1
        var i = slot(key, mask)
        while (true) {
            val k = keys[i]
            if (k == key) return t.values[i]
            if (k == EMPTY) return 0
            i = (i + 1) and mask
        }
    }

    operator fun set(key: Long, value: Int) {
        update(key) { value }
    }

    /** [key]'s count plus [by], or [initial] if it had none (Map.merge for counters). */
    fun add(key: Long, by: Int, initial: Int = by) {
        update(key) { old -> if (old == null) initial else old + by }
    }

    private inline fun update(key: Long, value: (Int?) -> Int) {
        require(key != EMPTY) { "reserved key" }
        if ((count + 1) * 5 > table.keys.size * 3) grow()
        val t = table
        val keys = t.keys
        val mask = keys.size - 1
        var i = slot(key, mask)
        while (true) {
            val k = keys[i]
            if (k == key) {
                t.values[i] = value(t.values[i])
                return
            }
            if (k == EMPTY) {
                t.values[i] = value(null)
                keys[i] = key  // after the value, so a reader that finds the key mostly finds its count
                count++
                return
            }
            i = (i + 1) and mask
        }
    }

    fun clear() {
        table = newTable(table.keys.size)
        count = 0
    }

    /** Every entry, without an iterator or boxing (keys and counts from one and the same table). */
    inline fun forEach(action: (key: Long, value: Int) -> Unit) {
        val t = current
        val keys = t.keys
        val values = t.values
        for (i in keys.indices) {
            val k = keys[i]
            if (k != EMPTY) action(k, values[i])
        }
    }

    @PublishedApi internal val current: Table get() = table

    private fun grow() {
        val old = table
        val bigger = newTable(old.keys.size * 2)
        val mask = bigger.keys.size - 1
        for (j in old.keys.indices) {
            val k = old.keys[j]
            if (k == EMPTY) continue
            var i = slot(k, mask)
            while (bigger.keys[i] != EMPTY) i = (i + 1) and mask
            bigger.keys[i] = k
            bigger.values[i] = old.values[j]
        }
        table = bigger
    }

    companion object {
        const val EMPTY = Long.MIN_VALUE

        /** Read-only stand-in where no counts are wired up yet (never written). */
        val NONE = LongIntMap()

        private fun newTable(capacity: Int) = Table(LongArray(capacity).also { it.fill(EMPTY) }, IntArray(capacity))

        // Same hashing and sizing as [LongHashSet].
        private fun slot(key: Long, mask: Int): Int = LongHashSet.slot(key, mask)

        private fun capacityFor(expected: Int): Int = LongHashSet.capacityFor(expected)
    }
}
