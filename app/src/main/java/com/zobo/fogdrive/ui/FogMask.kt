package com.zobo.fogdrive.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.zobo.fogdrive.geo.Outline
import com.zobo.fogdrive.geo.Tiles
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** What a fog bitmap shows; a tile is redrawn when any of it changes. */
data class FogMaskKey(
    /** Explored cells and revisit counts (see ExploreRepository.version / revisitVersion). */
    val data: Long,
    /** Cell aggregation level: one drawn tile is 2^dz zoom-20 cells. */
    val dz: Int,
    val night: Boolean,
    /** Compared by identity: a new layer comes with new region counts. */
    val regions: RegionLayer?,
    val regionsUnavailable: Boolean,
    val worn: Boolean,
)

/**
 * The fog over a world rectangle ([x0], [y0]) – ([x1], [y1]) in zoom-20 tile units, at [cellPx]
 * bitmap pixels per tile. Drawn to the screen through a matrix, so panning and zooming only move it.
 */
class FogMaskTile(
    val bitmap: Bitmap,
    val x0: Double,
    val y0: Double,
    val x1: Double,
    val y1: Double,
    val cellPx: Double,
    val key: FogMaskKey,
    /** Worn roads at [wornScale]× the fog's resolution (they are thin; the fog is soft anyway). */
    val worn: Bitmap?,
    val wornScale: Int,
) {
    fun covers(ax0: Double, ay0: Double, ax1: Double, ay1: Double) = ax0 >= x0 && ay0 >= y0 && ax1 <= x1 && ay1 <= y1
}

/**
 * Renders fog tiles on one background thread. Painting the mask (tens of thousands of explored
 * cells as soft circles) took 100–300 ms on the UI thread, and it ran on every frame of a pan or zoom
 * and on every driving re-render. Now it runs only when the fog's content changes or the view leaves
 * the rendered area, off the UI thread; meanwhile the previous tile keeps being shown.
 * Only the newest request is rendered: requests that pile up while one renders are replaced.
 */
class FogMaskRenderer(
    private val onTile: (FogMaskTile) -> Unit,
    /** A render threw (a concurrent change mid-read); called on the main thread with its job. */
    private val onFailed: (Job) -> Unit,
) {
    class Job(
        val key: FogMaskKey,
        val x0: Double,
        val y0: Double,
        val x1: Double,
        val y1: Double,
        val cellPx: Double,
        /** Explored cells at [FogMaskKey.dz]: a concurrent or immutable set, safe to read here. */
        val cells: () -> Set<Long>,
        /** Revisit counts (concurrent map), or null when worn roads are not drawn. */
        val revisits: Map<Long, Int>?,
    )

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var queued: Job? = null
    /** Bitmaps from a replaced tile, reused when the next one has the same size. */
    private val spares = ArrayList<Bitmap>(MAX_SPARES + 2)

    private val canvas = Canvas()
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }
    private val fogPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val lightFogPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)  // replaces the dark fog inside the outline
    }
    private var shaderNight: Boolean? = null
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0x00, 0x81, 0xF8) }
    private val wornPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textureMatrix = Matrix()
    private val outlineMatrix = Matrix()
    private val outlinePath = Path()
    private var centres = FloatArray(4096)
    private val wornXy = Array(FogOverlayView.WORN_LEVELS.size) { FloatArray(256) }
    private val wornCount = IntArray(FogOverlayView.WORN_LEVELS.size)

    fun submit(job: Job) {
        synchronized(lock) { queued = job }
        executor.execute {
            // Requests that came in while one was rendering left only the newest queued.
            val next = synchronized(lock) { queued.also { queued = null } } ?: return@execute
            val tile = try {
                render(next)
            } catch (e: RuntimeException) {
                null  // a concurrent change mid-read: the view re-requests
            } catch (e: OutOfMemoryError) {
                null  // no room for the bitmaps this time: keep the old tile, the view re-requests
            }
            if (tile != null) main.post { onTile(tile) } else main.post { onFailed(next) }
        }
    }

    /** Hands back the bitmaps of a tile no display list references any more (main thread). */
    fun recycle(tile: FogMaskTile) {
        synchronized(lock) {
            spares.add(tile.bitmap)
            tile.worn?.let { spares.add(it) }
            while (spares.size > MAX_SPARES) spares.removeAt(0)
        }
    }

    private fun obtain(w: Int, h: Int): Bitmap {
        val reuse = synchronized(lock) {
            spares.indexOfFirst { it.width == w && it.height == h }.takeIf { it >= 0 }?.let { spares.removeAt(it) }
        }
        return reuse ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    private fun render(job: Job): FogMaskTile {
        val made = ArrayList<Bitmap>(2)
        try {
            return renderInto(job, made)
        } catch (e: Throwable) {
            // Hand the bitmaps of the failed render back, or each failure would drop them for good.
            canvas.setBitmap(null)
            synchronized(lock) {
                spares.addAll(made)
                while (spares.size > MAX_SPARES) spares.removeAt(0)
            }
            throw e
        }
    }

    private fun renderInto(job: Job, made: MutableList<Bitmap>): FogMaskTile {
        val key = job.key
        val px = job.cellPx
        val x0 = job.x0
        val y0 = job.y0
        val w = max(1, ceil((job.x1 - x0) * px).toInt())
        val h = max(1, ceil((job.y1 - y0) * px).toInt())
        val bmp = obtain(w, h).also { made.add(it) }
        bmp.eraseColor(Color.TRANSPARENT)
        val c = canvas
        c.setBitmap(bmp)
        if (shaderNight != key.night) {
            shaderNight = key.night
            fogPaint.shader = BitmapShader(FogOverlayView.cloudTexture(key.night, light = false), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            lightFogPaint.shader = BitmapShader(FogOverlayView.cloudTexture(key.night, light = true), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        // Anchor the clouds to the map (not the bitmap) so neighbouring tiles line up.
        val texSize = FogOverlayView.TEXTURE_SIZE.toDouble()
        textureMatrix.setTranslate((-(x0 * px)).mod(texSize).toFloat(), (-(y0 * px)).mod(texSize).toFloat())
        fogPaint.shader.setLocalMatrix(textureMatrix)
        lightFogPaint.shader.setLocalMatrix(textureMatrix)

        val regions = key.regions
        if (regions == null) {
            // Still loading: dark everywhere, so nothing unvisited flashes into view. If the atlas is
            // missing for good, fall back to one strength of fog (the light one).
            c.drawPaint(if (key.regionsUnavailable) lightFogPaint else fogPaint)
        } else {
            c.drawPaint(fogPaint)
            for (i in regions.counts.visitedDongs) {
                val o = regions.atlas.dongs[i].outline
                if (o.intersects(x0, y0, job.x1, job.y1)) drawOutline(c, o, i, px, x0, y0)
            }
        }

        val scale = (1 shl key.dz).toDouble()
        val cellMaskPx = (px * scale).toFloat()
        val lx0 = floor(x0 / scale).toInt() - 1
        val lx1 = floor(job.x1 / scale).toInt() + 1
        val ly0 = floor(y0 / scale).toInt() - 1
        val ly1 = floor(job.y1 / scale).toInt() + 1
        val radius = max(1.2f, cellMaskPx * 1.05f)
        var n = 0
        fun punch(cx: Int, cy: Int) {
            if (n + 2 > centres.size) centres = centres.copyOf(centres.size * 2)
            centres[n++] = (((cx + 0.5) * scale - x0) * px).toFloat()
            centres[n++] = (((cy + 0.5) * scale - y0) * px).toFloat()
        }
        val explored = job.cells()
        val tiles = (lx1 - lx0 + 1).toLong() * (ly1 - ly0 + 1)
        if (tiles < explored.size) {
            // A large explored set: probe only the tiles in the rectangle.
            for (cx in lx0..lx1) for (cy in ly0..ly1) if (Tiles.pack(cx, cy) in explored) punch(cx, cy)
        } else {
            for (cell in explored) {
                val cx = Tiles.x(cell)
                val cy = Tiles.y(cell)
                if (cx < lx0 || cx > lx1 || cy < ly0 || cy > ly1) continue
                punch(cx, cy)
            }
        }
        // Rim first (larger circles over the fog), then punch the holes: only a soft edge remains.
        val rim = radius + max(1f, radius * 0.3f)
        var i = 0
        while (i < n) {
            c.drawCircle(centres[i], centres[i + 1], rim, rimPaint)
            i += 2
        }
        i = 0
        while (i < n) {
            c.drawCircle(centres[i], centres[i + 1], radius, clearPaint)
            i += 2
        }
        c.setBitmap(null)
        val wornScale = ceil(WORN_MIN_CELL_PX / px).toInt().coerceIn(1, WORN_MAX_SCALE)
        if (wornScale == 1) {
            // Same resolution as the fog (driving zoom): drawn into the fog bitmap itself, so each
            // new tile is one texture upload instead of two.
            job.revisits?.takeIf { it.isNotEmpty() }?.let { visits ->
                c.setBitmap(bmp)
                drawWornRoads(c, visits, x0, y0, job.x1, job.y1, px)
                c.setBitmap(null)
            }
            return FogMaskTile(bmp, x0, y0, job.x1, job.y1, px, key, null, 1)
        }
        val worn = job.revisits?.takeIf { it.isNotEmpty() }?.let { visits ->
            val wb = obtain(max(1, (w * wornScale)), max(1, (h * wornScale))).also { made.add(it) }
            wb.eraseColor(Color.TRANSPARENT)
            c.setBitmap(wb)
            drawWornRoads(c, visits, x0, y0, job.x1, job.y1, px * wornScale)
            c.setBitmap(null)
            wb
        }
        return FogMaskTile(bmp, x0, y0, job.x1, job.y1, px, key, worn, wornScale)
    }

    /**
     * Roads driven on several trips get a tint that deepens with the count (see WORN_LEVELS), so
     * the daily routes stand out at a glance. Drawn into the fog bitmap, above the holes.
     */
    private fun drawWornRoads(c: Canvas, visits: Map<Long, Int>, x0: Double, y0: Double, x1: Double, y1: Double, px: Double) {
        if (visits.isEmpty()) return
        val levels = FogOverlayView.WORN_LEVELS
        val r = (px * 0.75).toFloat()
        val lx0 = floor(x0).toInt()
        val lx1 = floor(x1).toInt()
        val ly0 = floor(y0).toInt()
        val ly1 = floor(y1).toInt()
        // One scan buckets the cells by tint; each tint is then drawn in one layer.
        for (level in levels.indices) wornCount[level] = 0
        fun collect(cx: Int, cy: Int, n: Int) {
            var level = levels.size - 1
            while (level >= 0 && n < levels[level].minTrips) level--
            if (level < 0) return
            val i = wornCount[level]
            if (i + 2 > wornXy[level].size) wornXy[level] = wornXy[level].copyOf(wornXy[level].size * 2)
            wornXy[level][i] = ((cx + 0.5 - x0) * px).toFloat()
            wornXy[level][i + 1] = ((cy + 0.5 - y0) * px).toFloat()
            wornCount[level] = i + 2
        }
        if ((lx1 - lx0 + 1).toLong() * (ly1 - ly0 + 1) < visits.size) {
            for (cx in lx0..lx1) for (cy in ly0..ly1) visits[Tiles.pack(cx, cy)]?.let { collect(cx, cy, it) }
        } else {
            for ((cell, n) in visits) {
                val cx = Tiles.x(cell)
                val cy = Tiles.y(cell)
                if (cx < lx0 || cx > lx1 || cy < ly0 || cy > ly1) continue
                collect(cx, cy, n)
            }
        }
        // One layer per tint: opaque circles inside merge into an even band, and the layer's alpha
        // is applied once (overlapping translucent circles used to show as a dotted pattern).
        for (level in levels.indices) {
            val count = wornCount[level]
            if (count == 0) continue
            val xy = wornXy[level]
            wornPaint.color = levels[level].color
            val layer = c.saveLayerAlpha(null, levels[level].alpha)
            var i = 0
            while (i < count) {
                c.drawCircle(xy[i], xy[i + 1], r, wornPaint)
                i += 2
            }
            c.restoreToCount(layer)
        }
    }

    companion object {
        /**
         * Worn roads get at least this many bitmap pixels per cell: sharper than the fog when zoomed
         * out (a cell is ~1 px there and the band looked jagged), the fog's own resolution when driving
         * (cells are large; a 2× bitmap was ~20 MB to upload per redraw).
         */
        const val WORN_MIN_CELL_PX = 4.0
        const val WORN_MAX_SCALE = 2
        private const val MAX_SPARES = 4
        /** One thread for every map view (each FogMap composition makes a new view; a per-view
         *  executor was never shut down and leaked a thread each time). Jobs are serialised, so each
         *  renderer's paints and buffers are still used by one thread at a time. */
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "FogMask").apply { isDaemon = true }
        }
    }

    /**
     * Outline paths are in tile units relative to their bbox corner (floats can't hold zoom-20
     * coordinates to sub-tile precision); the offset to the bitmap is computed in double.
     */
    private fun drawOutline(c: Canvas, o: Outline, index: Int, scale: Double, x0: Double, y0: Double) {
        outlineMatrix.setScale(scale.toFloat(), scale.toFloat())
        outlineMatrix.postTranslate(((o.bbox[0] - x0) * scale).toFloat(), ((o.bbox[1] - y0) * scale).toFloat())
        OutlinePaths.get(FogOverlayView.DONG, index, o).transform(outlineMatrix, outlinePath)
        c.drawPath(outlinePath, lightFogPaint)
    }
}

/** Whether [o]'s bounding box meets the rectangle (zoom-20 tile units). */
internal fun Outline.intersects(x0: Double, y0: Double, x1: Double, y1: Double): Boolean =
    bbox[2] >= x0 && bbox[0] <= x1 && bbox[3] >= y0 && bbox[1] <= y1
