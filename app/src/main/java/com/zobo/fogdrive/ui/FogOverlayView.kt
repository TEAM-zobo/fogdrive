package com.zobo.fogdrive.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.zobo.fogdrive.geo.Tiles
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the fog of war on top of the map. The fog is painted into a reduced-resolution mask with a
 * tileable cloud texture, explored cells get a soft mint rim, and each explored cell is punched out
 * as a slightly oversized circle (neighbours merge into smooth strips instead of a staircase).
 * The mask is then scaled up with filtering, which softens edges and clouds alike. The fog is
 * slightly translucent so major roads show faintly through it.
 * At low zoom it switches to coarser parent tiles so the cost stays bounded.
 * Map rotation/tilt are disabled, so tile coordinates map linearly to the screen.
 */
class FogOverlayView(context: Context) : View(context) {
    var map: MapLibreMap? = null
    var cellsAtLevel: (Int) -> Set<Long> = { emptySet() }
    var current: Pair<Double, Double>? = null
    var landings: List<Pair<Double, Double>> = emptyList()
    var detour: Pair<Double, Double>? = null
    /** Draw the car dot here (parked). While driving a fixed centre marker is used instead. */
    var showCurrent = true
    /** Keep drawing with the transform from [captureTransform] (driving: fog moves with the slid map). */
    var freezeTransform = false
    /** Night palette (darker fog) to match the night map style. */
    var night = false
        set(value) {
            if (field != value) {
                field = value
                fogPaint.shader = null
                invalidate()
            }
        }

    // Screen <-> zoom-20 tile transform: top-left and bottom-right of the view in tile units.
    private var tx0 = 0.0
    private var ty0 = 0.0
    private var tx1 = 0.0
    private var ty1 = 0.0
    private var hasTransform = false

    /** Snapshot of the map projection, taken on the frame a re-render lands. */
    fun captureTransform(): Boolean {
        val m = map ?: return false
        if (width == 0 || height == 0) return false
        val proj = m.projection
        val tl = proj.fromScreenLocation(PointF(0f, 0f))
        val br = proj.fromScreenLocation(PointF(width.toFloat(), height.toFloat()))
        tx0 = Tiles.lngToX(tl.longitude)
        ty0 = Tiles.latToY(tl.latitude)
        tx1 = Tiles.lngToX(br.longitude)
        ty1 = Tiles.latToY(br.latitude)
        hasTransform = true
        return true
    }

    private var mask: Bitmap? = null
    private val maskCanvas = Canvas()
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fogPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textureMatrix = Matrix()
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0x5E, 0xE6, 0xB8) }
    private var centres = FloatArray(512)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5EE6B8") }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB547") }
    private val detourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7FB2FF")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val src = Rect()
    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        if (map == null) return
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        if (!freezeTransform || !hasTransform) {
            if (!captureTransform()) return
        }
        val x0 = tx0
        val y0 = ty0
        val x1 = tx1
        val y1 = ty1
        if (x1 <= x0 || y1 <= y0) return
        val pxPerCell = w / (x1 - x0)

        val mw = max(1, w / MASK_DIV)
        val mh = max(1, h / MASK_DIV)
        val bmp = mask?.takeIf { it.width == mw && it.height == mh }
            ?: Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888).also { mask = it }
        maskCanvas.setBitmap(bmp)
        bmp.eraseColor(Color.TRANSPARENT)
        if (fogPaint.shader == null) {
            fogPaint.shader = BitmapShader(cloudTexture(night), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        // Anchor the clouds to the map (not the screen) so they move with it when panning or re-basing.
        val texSize = TEXTURE_SIZE.toDouble()
        val ox = (-(x0 * pxPerCell / MASK_DIV)).mod(texSize).toFloat()
        val oy = (-(y0 * pxPerCell / MASK_DIV)).mod(texSize).toFloat()
        textureMatrix.setTranslate(ox, oy)
        fogPaint.shader.setLocalMatrix(textureMatrix)
        maskCanvas.drawPaint(fogPaint)

        // Pick a level so one drawn tile covers at least ~1.5 mask pixels.
        var dz = 0
        while (dz < MAX_DZ && pxPerCell * (1 shl dz) / MASK_DIV < 1.5) dz++
        val scale = (1 shl dz).toDouble()
        val cellMaskPx = (pxPerCell * scale / MASK_DIV).toFloat()
        val lx0 = floor(x0 / scale).toInt() - 1
        val lx1 = floor(x1 / scale).toInt() + 1
        val ly0 = floor(y0 / scale).toInt() - 1
        val ly1 = floor(y1 / scale).toInt() + 1
        val radius = max(1.2f, cellMaskPx * 1.05f)
        var n = 0
        fun punch(cx: Int, cy: Int) {
            if (n + 2 > centres.size) centres = centres.copyOf(centres.size * 2)
            centres[n++] = (((cx + 0.5) * scale - x0) * pxPerCell / MASK_DIV).toFloat()
            centres[n++] = (((cy + 0.5) * scale - y0) * pxPerCell / MASK_DIV).toFloat()
        }
        val explored = cellsAtLevel(dz)
        val visibleTiles = (lx1 - lx0 + 1).toLong() * (ly1 - ly0 + 1)
        if (visibleTiles < explored.size) {
            // Zoomed in on a large explored set: probe only the tiles on screen.
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
            maskCanvas.drawCircle(centres[i], centres[i + 1], rim, rimPaint)
            i += 2
        }
        i = 0
        while (i < n) {
            maskCanvas.drawCircle(centres[i], centres[i + 1], radius, clearPaint)
            i += 2
        }
        src.set(0, 0, mw, mh)
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawBitmap(bmp, src, dst, maskPaint)

        fun toScreen(lat: Double, lng: Double): PointF = PointF(
            ((Tiles.lngToX(lng) - x0) * pxPerCell).toFloat(),
            ((Tiles.latToY(lat) - y0) * pxPerCell).toFloat(),
        )
        for ((lat, lng) in landings) {
            val p = toScreen(lat, lng)
            canvas.drawCircle(p.x, p.y - 26f, 14f, pinPaint)
            canvas.drawRect(p.x - 2.5f, p.y - 26f, p.x + 2.5f, p.y, pinPaint)
        }
        detour?.let { (lat, lng) ->
            val p = toScreen(lat, lng)
            canvas.drawCircle(p.x, p.y, min(160f, max(48f, pxPerCell.toFloat() * 3)), detourPaint)
        }
        current?.takeIf { showCurrent }?.let { (lat, lng) ->
            val p = toScreen(lat, lng)
            canvas.drawCircle(p.x, p.y, 16f, dotPaint)
            canvas.drawCircle(p.x, p.y, 16f, dotRing)
        }
    }

    private companion object {
        const val MASK_DIV = 3
        const val MAX_DZ = 10
        const val TEXTURE_SIZE = 128
        private var cachedTexture: Bitmap? = null
        private var cachedNight = false

        /**
         * Tileable cloud texture (two octaves of smoothed value noise) in the fog colour, built once.
         * Translucent enough (~89-96 %) for major roads to show faintly through the fog.
         */
        fun cloudTexture(night: Boolean): Bitmap {
            cachedTexture?.takeIf { cachedNight == night }?.let { return it }
            val size = TEXTURE_SIZE
            val base = if (night) intArrayOf(9, 13, 20) else intArrayOf(28, 37, 51)
            val rnd = java.util.Random(19)
            fun grid(n: Int) = Array(n) { FloatArray(n) { rnd.nextFloat() } }
            val coarse = grid(8)
            val fine = grid(32)
            fun sample(g: Array<FloatArray>, x: Float, y: Float): Float {
                val n = g.size
                val gx = x * n / size
                val gy = y * n / size
                val ix = gx.toInt()
                val iy = gy.toInt()
                val fx = (gx - ix).let { it * it * (3 - 2 * it) }
                val fy = (gy - iy).let { it * it * (3 - 2 * it) }
                val a = g[iy % n][ix % n]
                val b = g[iy % n][(ix + 1) % n]
                val c = g[(iy + 1) % n][ix % n]
                val d = g[(iy + 1) % n][(ix + 1) % n]
                return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
            }
            val pixels = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) {
                val v = 0.7f * sample(coarse, x.toFloat(), y.toFloat()) + 0.3f * sample(fine, x.toFloat(), y.toFloat())
                val lift = (v * 26).toInt()            // lighter wisps
                val alpha = (226 + v * 20).toInt()     // 89-96 % opaque
                pixels[y * size + x] = Color.argb(alpha, base[0] + lift, base[1] + lift, base[2] + lift)
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).also {
                cachedTexture = it
                cachedNight = night
            }
        }
    }
}
