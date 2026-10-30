package com.zobo.burung.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.zobo.burung.data.Tracks
import com.zobo.burung.geo.Outline
import com.zobo.burung.geo.Tiles
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** What a fog bitmap shows; a tile is redrawn when any of it changes. */
data class FogMaskKey(
    /** Explored cells and the routes and spots (see ExploreRepository.version / Tracks.version). */
    val data: Long,
    /** Cell aggregation level: one drawn tile is 2^dz zoom-20 cells. */
    val dz: Int,
    val night: Boolean,
    /** Compared by identity: a new layer comes with new region counts. */
    val regions: RegionLayer?,
    val regionsUnavailable: Boolean,
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
) {
    fun covers(ax0: Double, ay0: Double, ax1: Double, ay1: Double) = ax0 >= x0 && ay0 >= y0 && ax1 <= x1 && ay1 <= y1
}

/**
 * Renders fog tiles on one background thread. Painting the mask (once tens of thousands of explored
 * cells as soft circles, now the driven routes as one band) took 100–300 ms on the UI thread, and it ran on every frame of a pan or zoom
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
        /** The routes and spots the veil is opened along, as they were when the job was made. */
        val tracks: Tracks.Snapshot,
        /**
         * Where the content changed since the last request (x0, y0, x1, y1 in zoom-20 tile units),
         * or null for "anywhere": only that part is redrawn when the tile is otherwise the same.
         */
        val dirty: DoubleArray? = null,
    ) {
        fun withDirty(d: DoubleArray?) = if (d === dirty) this else Job(key, x0, y0, x1, y1, cellPx, tracks, d)
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var queued: Job? = null
    /** The last tile made: chunks whose pixels did not change are handed on, not re-uploaded. */
    @Volatile private var last: FogMaskTile? = null
    /** Whole-tile drawing surface (this thread only), and what it was last drawn as. */
    private var full: Bitmap? = null
    private var fullGeometry: List<Any?>? = null
    /** A chunk copy that turned out unchanged, kept for the next comparison (this thread only). */
    private val scratch = HashMap<Long, Bitmap>()

    private val canvas = Canvas()
    private val fogPaint = Paint()
    private val lightFogPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)  // replaces the dark veil inside the outline
    }
    private val outlineMatrix = Matrix()
    private val outlinePath = Path()
    private val trackPath = Path()
    private fun bandPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rimStroke = bandPaint().apply { color = Color.argb(110, 0xFF, 0xFF, 0xFF) }
    private val bandClear = bandPaint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val spotRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rimStroke.color }
    private val spotClear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    fun submit(job: Job) {
        synchronized(lock) {
            // A replaced request's changes are not in the bitmap yet: the newer one redraws them too.
            val dropped = queued
            queued = if (dropped == null) job else job.withDirty(union(dropped.dirty, job.dirty))
        }
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

    private fun union(a: DoubleArray?, b: DoubleArray?): DoubleArray? {
        if (a == null || b == null) return null
        return doubleArrayOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3]))
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
        // Same tile as last time apart from the data (driving: new cells near the car): redraw only
        // the changed area of the bitmap, which keeps the rest. Thousands of cells were punched again
        // every few hundred ms for a handful of new ones.
        val geometry = listOf(gx0, gy0, cols, rows, px, key.dz, key.night, key.regions, key.regionsUnavailable)
        val dirty = job.dirty?.takeIf { fullGeometry == geometry }
        fullGeometry = null  // until this render completes
        val c = canvas
        c.setBitmap(bmp)
        // The band opened along a route, and its soft rim: how far a route or spot reaches (in tiles).
        val band = max(MIN_BAND_PX, (REVEAL_WIDTH * px).toFloat())
        val rimWidth = band + max(2f, band * 0.25f)
        rimStroke.strokeWidth = rimWidth
        bandClear.strokeWidth = band
        val reach = rimWidth / 2.0 / px + 1.0
        // Area to scan for routes and spots: the whole tile, or the changed part (plus their reach).
        var sx0 = x0
        var sy0 = y0
        var sx1 = x1
        var sy1 = y1
        // The pixels redrawn this time (a partial render), so chunks outside are handed on as they were.
        var changed: android.graphics.Rect? = null
        if (dirty != null) {
            val rimWorld = maxOf(RIM_CELLS * (1 shl key.dz), reach)
            val margin = 2.0 * (1 shl key.dz) + rimWorld
            sx0 = maxOf(x0, dirty[0] - margin)
            sy0 = maxOf(y0, dirty[1] - margin)
            sx1 = minOf(x1, dirty[2] + margin)
            sy1 = minOf(y1, dirty[3] + margin)
            c.save()
            // Whole pixels: a fractional clip edge blends old and new along it (a faint seam).
            val clip = android.graphics.Rect(
                floor((sx0 - x0) * px).toInt(), floor((sy0 - y0) * px).toInt(),
                ceil((sx1 - x0) * px).toInt(), ceil((sy1 - y0) * px).toInt(),
            )
            c.clipRect(clip)
            changed = clip
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            // Routes just outside still reach in with their band: scan a little wider than the clip.
            sx0 -= rimWorld
            sy0 -= rimWorld
            sx1 += rimWorld
            sy1 += rimWorld
        } else {
            bmp.eraseColor(Color.TRANSPARENT)
        }
        fogPaint.color = FogOverlayView.fogColor(key.night, light = false)
        lightFogPaint.color = FogOverlayView.fogColor(key.night, light = true)

        val regions = key.regions
        if (regions == null) {
            // Still loading: the dark veil everywhere. If the atlas is missing for good, fall back to
            // one strength (the light one).
            c.drawPaint(if (key.regionsUnavailable) lightFogPaint else fogPaint)
        } else {
            c.drawPaint(fogPaint)
            for (i in regions.counts.visitedDongs) {
                val o = regions.atlas.dongs[i].outline
                if (o.intersects(sx0, sy0, sx1, sy1)) drawOutline(c, o, i, px, x0, y0)
            }
        }

        // Open the ground along every driven route: one straight band, a soft white edge under it.
        // Only what reaches the scanned area is built: a long history is mostly elsewhere.
        trackPath.rewind()
        val ax0 = sx0 - reach
        val ay0 = sy0 - reach
        val ax1 = sx1 + reach
        val ay1 = sy1 + reach
        for (line in job.tracks.lines) {
            val b = line.bbox
            if (b[2] < ax0 || b[0] > ax1 || b[3] < ay0 || b[1] > ay1) continue
            addRoute(line, x0, y0, px, ax0, ay0, ax1, ay1)
        }
        // Ground cleared without a route through it (a drop-off, an arrival, the first-run seeding,
        // trips from before routes were stored): each cell as a slightly oversized soft circle.
        val spots = job.tracks.spots
        val spotR = max(1.2f, (px * 1.05).toFloat())
        val spotRimR = spotR + max(1f, spotR * 0.3f)
        // Rims first (routes and spots), then the openings: an opening is never covered by a rim.
        for (pass in 0..1) {
            c.drawPath(trackPath, if (pass == 0) rimStroke else bandClear)
            val paint = if (pass == 0) spotRim else spotClear
            val r = if (pass == 0) spotRimR else spotR
            for (k in 0 until job.tracks.spotCount) {
                val cx = Tiles.x(spots[k]) + 0.5
                val cy = Tiles.y(spots[k]) + 0.5
                if (cx < ax0 || cx > ax1 || cy < ay0 || cy > ay1) continue
                c.drawCircle(((cx - x0) * px).toFloat(), ((cy - y0) * px).toFloat(), r, paint)
            }
        }
        if (dirty != null) c.restore()
        c.setBitmap(null)
        val chunks = chunk(bmp, gx0, gy0, cols, rows, px, key, changed)
        // Only now: a partial render hands on [last]'s chunks outside its clip, so the bitmap may be
        // reused only when this render also became [last] (chunking can still run out of memory).
        fullGeometry = geometry
        return FogMaskTile(chunks, cols, x0, y0, x1, y1, px, key)
    }

    /**
     * Cuts the tile into chunks. A chunk whose pixels equal the last tile's chunk at the same map
     * position (same scale and look) is handed on as is: the GPU keeps that texture, so a redraw for a
     * few new cells uploads a few chunks instead of the whole tile (~4 MB, a late frame each time).
     * After a partial render ([changed]: the pixels redrawn), a chunk clear of them is the last tile's
     * as it was, without copying and comparing it (that was a third of the renderer's time).
     */
    private fun chunk(
        src: Bitmap, gx0: Long, gy0: Long, cols: Int, rows: Int, px: Double, key: FogMaskKey,
        changed: android.graphics.Rect?,
    ): List<FogChunk> {
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
            val offX = i * CHUNK - left
            val offY = j * CHUNK - top
            val old = prevChunks?.get(chunkKey(gx, gy))?.takeIf { it.offX == offX && it.offY == offY }
            if (old != null && changed != null && !changed.intersects(left, top, right, bottom)) {
                out.add(old)
                continue
            }
            val cw = right - left
            val ch = bottom - top
            val sizeKey = (cw.toLong() shl 32) or ch.toLong()
            val copy = scratch.remove(sizeKey) ?: Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
            canvas.setBitmap(copy)
            srcRect.set(left, top, right, bottom)
            dstRect.set(0, 0, cw, ch)
            canvas.drawBitmap(src, srcRect, dstRect, copyPaint)
            canvas.setBitmap(null)
            if (old != null && old.bitmap.sameAs(copy)) {
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
     * Adds [line]'s strokes to [trackPath], skipping segments outside the area (ax0..ay1, tiles) and
     * breaking at gaps (NaN points). A lone point still opens a round spot.
     */
    private fun addRoute(line: Tracks.Line, x0: Double, y0: Double, px: Double, ax0: Double, ay0: Double, ax1: Double, ay1: Double) {
        val xs = line.xs
        val ys = line.ys
        var pen = false
        var prevX = Double.NaN
        var prevY = Double.NaN
        for (k in 0 until line.count) {
            val x = xs[k]
            val y = ys[k]
            if (x.isNaN()) {
                pen = false
                prevX = Double.NaN
                continue
            }
            val sx = ((x - x0) * px).toFloat()
            val sy = ((y - y0) * px).toFloat()
            if (prevX.isNaN()) {
                val inside = x in ax0..ax1 && y in ay0..ay1
                val lone = k + 1 >= line.count || xs[k + 1].isNaN()
                if (inside && lone) {
                    trackPath.moveTo(sx, sy)
                    trackPath.lineTo(sx + 0.01f, sy)
                }
            } else {
                val outside = maxOf(prevX, x) < ax0 || minOf(prevX, x) > ax1 || maxOf(prevY, y) < ay0 || minOf(prevY, y) > ay1
                if (outside) {
                    pen = false
                } else {
                    if (!pen) trackPath.moveTo(((prevX - x0) * px).toFloat(), ((prevY - y0) * px).toFloat())
                    trackPath.lineTo(sx, sy)
                    pen = true
                }
            }
            prevX = x
            prevY = y
        }
    }

    companion object {
        /** Width of the ground opened along a driven route, in zoom-20 tiles (~80 m across). */
        private const val REVEAL_WIDTH = 2.6
        /** The band never gets narrower than this many mask pixels, so it shows beside the gold trail when zoomed out. */
        private const val MIN_BAND_PX = 12f
        /** Cells around a changed area that are redrawn too (their soft rims reach this far). */
        private const val RIM_CELLS = 2.0
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
