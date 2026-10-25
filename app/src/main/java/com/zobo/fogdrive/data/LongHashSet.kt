package com.zobo.fogdrive.data

/**
 * A set of longs (cell ids) in one open-addressing LongArray: 8 bytes a slot at a 0.6 load factor,
 * instead of a boxed Long plus a hash node (~56 bytes) per entry in a HashSet / concurrent set, and
 * no allocation per lookup ([has] takes a primitive; `in` on a Set<Long> boxes the key).
 *
 * One writer at a time (callers hold their own lock around [add] / [clear]); readers need no lock.
 * A slot only ever goes from [EMPTY] to a value (until [clear]), and growing publishes a new, fully
 * built table through a volatile field, so a reader sees either a slot before or after a write,
 * never a torn table: like a concurrent set's iterator, it may just miss a value added meanwhile.
 * [Long.MIN_VALUE] is reserved as the empty marker (cell ids are never negative).
 */
class LongHashSet(expected: Int = 16) : AbstractSet<Long>() {
    @Volatile private var table = LongArray(capacityFor(expected)).also { it.fill(EMPTY) }
    @Volatile private var count = 0

    override val size: Int get() = count

    /** The raw slots, [EMPTY] where unused, for allocation-free scans ([forEachValue]). Read only. */
    val slots: LongArray get() = table

    fun has(value: Long): Boolean {
        val t = table
        val mask = t.size - 1
        var i = slot(value, mask)
        while (true) {
            val v = t[i]
            if (v == value) return true
            if (v == EMPTY) return false
            i = (i + 1) and mask
        }
    }

    override fun contains(element: Long): Boolean = has(element)

    /** Adds [value]; false if it was already there. Callers serialise writes. */
    fun add(value: Long): Boolean {
        require(value != EMPTY) { "reserved value" }
        if ((count + 1) * 5 > table.size * 3) grow()
        val t = table
        val mask = t.size - 1
        var i = slot(value, mask)
        while (true) {
            val v = t[i]
            if (v == value) return false
            if (v == EMPTY) {
                t[i] = value
                count++  // volatile write after the slot: a reader that sees the count sees the value
                return true
            }
            i = (i + 1) and mask
        }
    }

    /** Callers serialise writes. Readers then see an empty table, not a half-cleared one. */
    fun clear() {
        table = LongArray(table.size).also { it.fill(EMPTY) }
        count = 0
    }

    override fun iterator(): Iterator<Long> = object : Iterator<Long> {
        private val t = table
        private var i = advance(0)

        private fun advance(from: Int): Int {
            var k = from
            while (k < t.size && t[k] == EMPTY) k++
            return k
        }

        override fun hasNext() = i < t.size

        override fun next(): Long {
            if (i >= t.size) throw NoSuchElementException()
            val v = t[i]
            i = advance(i + 1)
            return v
        }
    }

    private fun grow() {
        val old = table
        val bigger = LongArray(old.size * 2).also { it.fill(EMPTY) }
        val mask = bigger.size - 1
        for (v in old) {
            if (v == EMPTY) continue
            var i = slot(v, mask)
            while (bigger[i] != EMPTY) i = (i + 1) and mask
            bigger[i] = v
        }
        table = bigger
    }

    companion object {
        const val EMPTY = Long.MIN_VALUE

        /** Fibonacci hashing: neighbouring cells (packed x / y) land far apart. */
        private fun slot(value: Long, mask: Int): Int = ((value * -0x61c8864680b583ebL) ushr 32).toInt() and mask

        private fun capacityFor(expected: Int): Int {
            var c = 16
            while (c * 3 < expected * 5) c = c shl 1
            return c
        }
    }
}

/** Every value of [this], without an iterator or boxing. */
inline fun LongHashSet.forEachValue(action: (Long) -> Unit) {
    for (v in slots) if (v != LongHashSet.EMPTY) action(v)
}
