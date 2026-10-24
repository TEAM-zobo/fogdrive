package com.zobo.fogdrive.geo

import android.content.Context
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Axis-aligned box in zoom-20 tile units (y grows southwards). */
data class TileBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    fun union(o: TileBox) = TileBox(min(x0, o.x0), min(y0, o.y0), max(x1, o.x1), max(y1, o.y1))
}

/**
 * A region's shape in zoom-20 tile units: rings are flat x,y pairs, [labelX]/[labelY] is where its
 * name is written on the map (inside its largest part).
 */
class Outline(val bbox: IntArray, val labelX: Int, val labelY: Int, val rings: List<IntArray>) {
    val box: TileBox get() = TileBox(bbox[0], bbox[1], bbox[2], bbox[3])

    /** Even-odd point-in-polygon, so holes and islands need no special case. */
    fun contains(x: Double, y: Double): Boolean {
        if (x < bbox[0] || x > bbox[2] || y < bbox[1] || y > bbox[3]) return false
        var inside = false
        for (r in rings) {
            val n = r.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = r[2 * i].toDouble()
                val yi = r[2 * i + 1].toDouble()
                val xj = r[2 * j].toDouble()
                val yj = r[2 * j + 1].toDouble()
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }
}

class Province(val code: String, val name: String, val outline: Outline? = null)

/** One 시·군·구 (union of its [Dong]s). */
class District(val code: String, val name: String, val province: Int, val outline: Outline? = null)

/** One 읍·면·동; [cells] is its size in grid cells. */
class Dong(val code: String, val name: String, val district: Int, val cells: Long, val outline: Outline)

/**
 * 시·도 / 시·군·구 / 읍·면·동 of the whole country, built by tools/regions/build_regions.py
 * (admdongkor, CC BY 4.0). Lookups go through a coarse bucket grid so a cell is only tested
 * against the 동 nearby.
 */
class RegionAtlas(
    val provinces: List<Province>,
    val districts: List<District>,
    val dongs: List<Dong>,
    val source: String = "",
) {
    private val buckets = HashMap<Long, IntArray>()
    /** Grid cells per 시·군·구 / 시·도 (sum of their 동). */
    val districtCells = LongArray(districts.size).also { a -> dongs.forEach { a[it.district] += it.cells } }
    val provinceCells = LongArray(provinces.size).also { a -> districts.forEachIndexed { i, d -> a[d.province] += districtCells[i] } }

    init {
        val tmp = HashMap<Long, MutableList<Int>>()
        dongs.forEachIndexed { i, d ->
            val bbox = d.outline.bbox
            for (bx in (bbox[0] shr BUCKET_DZ)..(bbox[2] shr BUCKET_DZ)) {
                for (by in (bbox[1] shr BUCKET_DZ)..(bbox[3] shr BUCKET_DZ)) {
                    tmp.getOrPut(Tiles.pack(bx, by)) { ArrayList() }.add(i)
                }
            }
        }
        for ((k, v) in tmp) buckets[k] = v.toIntArray()
    }

    /** Index into [dongs] of the 동 holding [cell], or -1 (sea, abroad, dropped islet). */
    fun dongOf(cell: Long): Int {
        val candidates = buckets[Tiles.parent(cell, BUCKET_DZ)] ?: return -1
        val x = Tiles.x(cell) + 0.5
        val y = Tiles.y(cell) + 0.5
        for (i in candidates) if (dongs[i].outline.contains(x, y)) return i
        return -1
    }

    companion object {
        /**
         * Bucket = zoom-13 tile (~5 km). Finer buckets made rural 동 (tens of km across) span hundreds
         * each: ~270k index entries and seconds of start-up; this is ~25k, and the bbox check per
         * candidate keeps lookups cheap (each explored cell is located only once anyway).
         */
        private const val BUCKET_DZ = 7
        const val ASSET = "regions/kr_regions.bin"
        private const val FORMAT_VERSION = 2

        fun load(context: Context): RegionAtlas = context.assets.open(ASSET).use { read(it) }

        /**
         * Reads the binary layout documented in build_regions.py. The whole file is read at once and
         * ring coordinates are bulk-copied (about a million ints), not read one stream call at a time.
         */
        fun read(stream: InputStream): RegionAtlas {
            val buf = ByteBuffer.wrap(stream.readBytes()).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            require(String(magic, Charsets.US_ASCII) == "FGRG") { "not a region atlas" }
            require(buf.int == FORMAT_VERSION) { "unsupported region atlas version" }
            fun utf(): String {
                val bytes = ByteArray(buf.short.toInt() and 0xFFFF).also { buf.get(it) }
                return String(bytes, Charsets.UTF_8)
            }
            fun ints(n: Int): IntArray {
                val out = IntArray(n)
                buf.asIntBuffer().get(out)
                buf.position(buf.position() + n * 4)
                return out
            }
            fun outline(): Outline {
                val bbox = ints(4)
                val labelX = buf.int
                val labelY = buf.int
                val rings = List(buf.int) { ints(buf.int * 2) }
                return Outline(bbox, labelX, labelY, rings)
            }
            val source = utf()
            val provinces = List(buf.int) { Province(utf(), utf(), outline()) }
            val districts = List(buf.int) { District(utf(), utf(), buf.int, outline()) }
            val dongs = List(buf.int) { Dong(utf(), utf(), buf.int, buf.long, outline()) }
            return RegionAtlas(provinces, districts, dongs, source)
        }
    }
}
