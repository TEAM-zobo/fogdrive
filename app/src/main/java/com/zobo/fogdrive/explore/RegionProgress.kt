package com.zobo.fogdrive.explore

import com.zobo.fogdrive.geo.RegionAtlas
import com.zobo.fogdrive.geo.TileBox

/** Explored cells out of all grid cells of one region, at any level. */
sealed interface RegionStat {
    val code: String
    val name: String
    val explored: Int
    val total: Long
    /** Bounding box, for framing the region on the map. */
    val bounds: TileBox?
    val visited: Boolean get() = explored > 0
    val rate: Double get() = if (total > 0) explored.toDouble() / total else 0.0
}

data class DongProgress(
    override val code: String,
    override val name: String,
    override val explored: Int,
    override val total: Long,
    override val bounds: TileBox,
) : RegionStat

data class DistrictProgress(override val code: String, override val name: String, val dongs: List<DongProgress>) : RegionStat {
    override val explored: Int = dongs.sumOf { it.explored }
    override val total: Long = dongs.sumOf { it.total }
    override val bounds: TileBox? = dongs.map { it.bounds }.reduceOrNull(TileBox::union)
    val visitedDongs: Int get() = dongs.count { it.visited }
}

data class ProvinceProgress(override val code: String, override val name: String, val districts: List<DistrictProgress>) : RegionStat {
    override val explored: Int = districts.sumOf { it.explored }
    override val total: Long = districts.sumOf { it.total }
    override val bounds: TileBox? = districts.mapNotNull { it.bounds }.reduceOrNull(TileBox::union)
    val visitedDistricts: Int get() = districts.count { it.visited }
}

/**
 * Achievement rate per 시·도 / 시·군·구 / 읍·면·동 = explored cells inside it / all grid cells inside it.
 * Each cell's 동 is resolved once and remembered, so a re-tally after a drive only locates the
 * newly cleared cells.
 */
class RegionTally(val atlas: RegionAtlas) {
    private val memo = HashMap<Long, Int>()
    private val dongsByDistrict = atlas.dongs.indices.groupBy { atlas.dongs[it].district }
    private val districtsByProvince = atlas.districts.indices.groupBy { atlas.districts[it].province }

    @Synchronized
    private fun counts(cells: Set<Long>): IntArray {
        val counts = IntArray(atlas.dongs.size)
        for (c in cells) {
            val d = memo.getOrPut(c) { atlas.dongOf(c) }
            if (d >= 0) counts[d]++
        }
        if (memo.size > cells.size) memo.keys.retainAll(cells)  // after a reset
        return counts
    }

    /** Explored cells per 동 / 시·군·구 / 시·도, by atlas index (for the map's fog and labels). */
    fun levels(cells: Set<Long>): RegionCounts {
        val dong = counts(cells)
        val district = IntArray(atlas.districts.size)
        val province = IntArray(atlas.provinces.size)
        dong.forEachIndexed { i, n -> district[atlas.dongs[i].district] += n }
        district.forEachIndexed { i, n -> province[atlas.districts[i].province] += n }
        return RegionCounts(atlas, dong, district, province)
    }

    /** How many 읍·면·동 have all their explored cells in [tripCells], i.e. were first entered on that trip. */
    @Synchronized
    fun firstEntered(tripCells: Collection<Long>, all: RegionCounts): Int {
        val n = IntArray(atlas.dongs.size)
        for (c in tripCells) {
            val d = memo.getOrPut(c) { atlas.dongOf(c) }
            if (d >= 0) n[d]++
        }
        return n.indices.count { n[it] > 0 && n[it] == all.dong[it] }
    }

    /** Indices into [RegionAtlas.dongs] holding at least one explored cell. */
    fun visitedDongs(cells: Set<Long>): Set<Int> = levels(cells).visitedDongs

    fun tally(cells: Set<Long>): List<ProvinceProgress> {
        val counts = counts(cells)
        return atlas.provinces.mapIndexed { p, province ->
            val districts = districtsByProvince[p].orEmpty().map { di ->
                val district = atlas.districts[di]
                val dongs = dongsByDistrict[di].orEmpty().map { i ->
                    val d = atlas.dongs[i]
                    DongProgress(d.code, d.name, counts[i], d.cells, d.outline.box)
                }
                DistrictProgress(district.code, district.name, dongs)
            }
            ProvinceProgress(province.code, province.name, districts)
        }
    }
}

/** Explored cell counts at every level, indexed like [RegionAtlas.dongs] / districts / provinces. */
class RegionCounts(val atlas: RegionAtlas, val dong: IntArray, val district: IntArray, val province: IntArray) {
    val visitedDongs: Set<Int> = dong.indices.filterTo(HashSet()) { dong[it] > 0 }

    fun dongRate(i: Int): Double = dong[i].toDouble() / atlas.dongs[i].cells.coerceAtLeast(1)
    fun districtRate(i: Int): Double = district[i].toDouble() / atlas.districtCells[i].coerceAtLeast(1)
    fun provinceRate(i: Int): Double = province[i].toDouble() / atlas.provinceCells[i].coerceAtLeast(1)
}
