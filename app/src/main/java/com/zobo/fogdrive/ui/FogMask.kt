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
 * One square of a tile on a grid fixed to the map: [gx], [gy] count [FogMaskRenderer.CHUNK]-pixel
 * steps at the tile's scale. [bitmap] holds it plus a pixel of each neighbour ([offX], [offY] is where
 * the square starts in it), so filtered scaling has no seams between chunks.
 */
class FogChunk(val gx: Long, val gy: Long, val bitmap: Bitmap, val offX: Int, val offY: Int)

/**
 * The fog over a world rectangle ([x0], [y0]) – ([x1], [y1]) in zoom-20 tile units, at [cellPx]
 * bitmap pixels per tile, as [chunks] (row by row, [cols] across). Drawn to the screen through a
 * matrix, so panning and zooming only move it.
 */
class FogMaskTile(
    val chunks: List<FogChunk>,
    val cols: Int,
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
    /** The last tile made: chunks whose pixels did not change are handed on, not re-uploaded. */
    @Volatile private var last: FogMaskTile? = null
    /** Whole-tile drawing surface (this thread only). */
    private var full: Bitmap? = null
    /** A chunk copy that turned out unchanged, kept for the next comparison (this thread only). */
    private val scratch = HashMap<Long, Bitmap>()

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

    private fun render(job: Job): FogMaskTile {
        try {
            return renderInto(job).also { last = it }
        } finally {
            canvas.setBitmap(null)
        }
    }

    private fun renderInto(job: Job): FogMaskTile {
        val key = job.key
        val px = job.cellPx
        // Snap the rectangle out to the chunk grid, so the same map area always falls in the same
        // chunks and unchanged ones can be reused across tiles (driving re-renders move the view).
        val step = CHUNK / px
        val gx0 = floor(job.x0 / step).toLong()
        val gy0 = floor(job.y0 / step).toLong()
        val cols = max(1, (ceil(job.x1 / step).toLong() - gx0).toInt())
        val rows = max(1, (ceil(job.y1 / step).toLong() - gy0).toInt())
        val x0 = gx0 * step
        val y0 = gy0 * step
        val x1 = x0 + cols * step
        val y1 = y0 + rows * step
        val w = cols * CHUNK
        val h = rows * CHUNK
        val bmp = full?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { full = it }
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
                if (o.intersects(x0, y0, x1, y1)) drawOutline(c, o, i, px, x0, y0)
            }
        }

        val scale = (1 shl key.dz).toDouble()
        val cellMaskPx = (px * scale).toFloat()
        val lx0 = floor(x0 / scale).toInt() - 1
        val lx1 = floor(x1 / scale).toInt() + 1
        val ly0 = floor(y0 / scale).toInt() - 1
        val ly1 = floor(y1 / scale).toInt() + 1
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
        val wornScale = ceil(WORN_MIN_CELL_PX / px).toInt().coerceIn(1, WORN_MAX_SCALE)
        var worn: Bitmap? = null
        job.revisits?.takeIf { it.isNotEmpty() }?.let { visits ->
            if (wornScale == 1) {
                // Same resolution as the fog (driving zoom): drawn into the fog itself, so it is
                // uploaded with the chunks it touches.
                drawWornRoads(c, visits, x0, y0, x1, y1, px)
            } else {
                // Zoomed out (parked): sharper than the fog, one bitmap of its own.
                val wb = Bitmap.createBitmap(w * wornScale, h * wornScale, Bitmap.Config.ARGB_8888)
                c.setBitmap(wb)
                drawWornRoads(c, visits, x0, y0, x1, y1, px * wornScale)
                worn = wb
            }
        }
        c.setBitmap(null)
        return FogMaskTile(chunk(bmp, gx0, gy0, cols, rows, px, key), cols, x0, y0, x1, y1, px, key, worn, wornScale)
    }

    /**
     * Cuts the tile into chunks. A chunk whose pixels equal the last tile's chunk at the same map
     * position (same scale and look) is handed on as is: the GPU keeps that texture, so a redraw for a
     * few new cells uploads a few chunks instead of the whole tile (~4 MB, a late frame each time).
     */
    private fun chunk(src: Bitmap, gx0: Long, gy0: Long, cols: Int, rows: Int, px: Double, key: FogMaskKey): List<FogChunk> {
        val prev = last?.takeIf { it.cellPx == px && it.key.dz == key.dz && it.key.night == key.night }
        val prevChunks = prev?.chunks?.associateBy { chunkKey(it.gx, it.gy) }
        val out = ArrayList<FogChunk>(cols * rows)
        val w = src.width
        val h = src.height
        for (j in 0 until rows) for (i in 0 until cols) {
            val gx = gx0 + i
            val gy = gy0 + j
            val left = max(0, i * CHUNK - 1)
            val top = max(0, j * CHUNK - 1)
            val right = minOf(w, (i + 1) * CHUNK + 1)
            val bottom = minOf(h, (j + 1) * CHUNK + 1)
            val cw = right - left
            val ch = bottom - top
            val sizeKey = (cw.toLong() shl 32) or ch.toLong()
            val copy = scratch.remove(sizeKey) ?: Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
            canvas.setBitmap(copy)
            srcRect.set(left, top, right, bottom)
            dstRect.set(0, 0, cw, ch)
            canvas.drawBitmap(src, srcRect, dstRect, copyPaint)
            canvas.setBitmap(null)
            val offX = i * CHUNK - left
            val offY = j * CHUNK - top
            val old = prevChunks?.get(chunkKey(gx, gy))
            if (old != null && old.offX == offX && old.offY == offY && old.bitmap.sameAs(copy)) {
                scratch[sizeKey] = copy
                out.add(old)
            } else {
                out.add(FogChunk(gx, gy, copy, offX, offY))
            }
        }
        return out
    }

    private val srcRect = android.graphics.Rect()
    private val dstRect = android.graphics.Rect()
    /** Exact copy: no filtering, replace the destination. */
    private val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    private fun chunkKey(gx: Long, gy: Long) = (gx shl 32) xor (gy and 0xffffffffL)

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
        /** Chunk edge in bitmap pixels. */
        const val CHUNK = 256
        /** One thread for every map view (each FogMap composition makes a new view; a per-view
         *  executor was never shut down and leaked a thread each time). Jobs are serialised, so each
         *  renderer's paints and buffers are still used by one thread at a time. */
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread({
                // Default, not background: under load (start-up, driving) background threads starve and the
            // fog lagged behind by seconds; the UI and render threads still run at higher priority.
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
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
