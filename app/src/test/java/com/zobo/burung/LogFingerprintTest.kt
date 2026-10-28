package com.zobo.burung

import com.zobo.burung.data.ExploreRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ExploreRepository.logFingerprint]. The repository needs a Room database to construct, so the
 * test allocates one without running its constructor and sets only the fields the fingerprint
 * reads (lock, order, orderSize; resetGeneration stays 0).
 */
class LogFingerprintTest {
    private fun repoWith(cells: LongArray): ExploreRepository {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val repo = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, ExploreRepository::class.java) as ExploreRepository
        fun set(name: String, value: Any) =
            ExploreRepository::class.java.getDeclaredField(name).apply { isAccessible = true }.set(repo, value)
        set("lock", Any())
        set("order", cells.copyOf())
        set("orderSize", cells.size)
        return repo
    }

    private val cells = longArrayOf(1L, 42L, (893_123L shl 32) or 406_456L, -7L, Long.MAX_VALUE, 0L)

    @Test
    fun sameCellsInAnyOrderMatch() {
        val a = repoWith(cells).logFingerprint(0, cells.size)!!
        val b = repoWith(cells.reversedArray()).logFingerprint(0, cells.size)!!
        val c = repoWith(longArrayOf(cells[3], cells[0], cells[5], cells[1], cells[4], cells[2])).logFingerprint(0, cells.size)!!
        assertArrayEquals(a, b)
        assertArrayEquals(a, c)
    }

    @Test
    fun differentSetsDiffer() {
        val a = repoWith(cells).logFingerprint(0, cells.size)!!
        val changed = cells.copyOf().also { it[2] = it[2] + 1 }
        assertFalse(a.contentEquals(repoWith(changed).logFingerprint(0, cells.size)!!))
        // A prefix of the log is a different set too.
        assertFalse(a.contentEquals(repoWith(cells).logFingerprint(0, cells.size - 1)!!))
    }

    @Test
    fun staleGenerationOrTooLongIsNull() {
        val repo = repoWith(cells)
        assertNull(repo.logFingerprint(1, 1))
        assertNull(repo.logFingerprint(0, cells.size + 1))
    }
}
