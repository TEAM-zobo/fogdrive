package com.zobo.fogdrive.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.zobo.fogdrive.explore.RegionCounts
import com.zobo.fogdrive.geo.Outline
import com.zobo.fogdrive.geo.RegionAtlas
import com.zobo.fogdrive.geo.Tiles
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the fog of war on top of the map in two strengths: opaque dark fog over 읍·면·동 the driver
 * has never entered, and a light grey fog (the map shows through) inside the ones already entered.
 * The fog is painted into a reduced-resolution mask with tileable cloud textures, explored cells get
 * a soft blue rim, and each explored cell is punched out as a slightly oversized circle (neighbours
 * merge into smooth strips instead of a staircase). The mask is then scaled up with filtering,
 * which softens edges and clouds alike.
 * Over the fog go the administrative boundaries (시·도 always, 시·군·구 and 읍·면·동 as the map zooms
 * in) and, for regions already entered, their name and achievement rate.
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
                lightFogPaint.shader = null
                invalidate()
            }
        }
    /** Boundaries and explored counts; null until the atlas is loaded, then everything is light fog. */
    var regions: RegionLayer? = null
        set(value) {
            field = value
            invalidate()
        }
    /** Region names and rates on the map (off while driving: lines only). */
    var showLabels = true

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
    private val lightFogPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)  // replaces the dark fog inside the outline
    }
    private val outlineMatrix = Matrix()
    private val outlinePath = Path()
    private val textureMatrix = Matrix()
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0x00, 0x81, 0xF8) }
    private var centres = FloatArray(512)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0081F8") }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF9900") }
    private val detourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0081F8")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val src = Rect()
    private val dst = RectF()
    private val density = resources.displayMetrics.density
    private fun linePaint(argb: Int, width: Float, dash: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb
        style = Paint.Style.STROKE
        strokeWidth = width * density
        strokeJoin = Paint.Join.ROUND
        if (dash > 0) pathEffect = DashPathEffect(floatArrayOf(dash * density, dash * density * 0.8f), 0f)
    }
    // Each level is a light line over a dark halo so it reads on the dark fog and the light map alike.
    private val provinceHalo = linePaint(Color.argb(110, 0, 0, 0), 5f)
    private val provinceLine = linePaint(Color.argb(230, 0xF1, 0xF4, 0xF8), 2.4f)
    private val districtHalo = linePaint(Color.argb(90, 0, 0, 0), 3.4f)
    private val districtLine = linePaint(Color.argb(190, 0xC9, 0xD3, 0xDF), 1.5f)
    private val dongLine = linePaint(Color.argb(150, 0xAE, 0xB8, 0xC6), 1f, dash = 5f)
    private val nameText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F1F4F8")
        textSize = 22f * density
        textAlign = Paint.Align.CENTER
        // Medium: Pleos maps bold (700) to AstaSans ExtraBold, which smeared at this size.
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private val rateText = Paint(nameText).apply {
        color = Color.parseColor("#3D9BFF")
        textSize = 19f * density
    }
    private val textHalo = Paint(nameText).apply {
        color = Color.argb(170, 0x11, 0x11, 0x11)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val rateHalo = Paint(textHalo).apply { textSize = rateText.textSize }
    private val placedLabels = ArrayList<RectF>()

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
            fogPaint.shader = BitmapShader(cloudTexture(night, light = false), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            lightFogPaint.shader = BitmapShader(cloudTexture(night, light = true), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        // Anchor the clouds to the map (not the screen) so they move with it when panning or re-basing.
        val texSize = TEXTURE_SIZE.toDouble()
        val ox = (-(x0 * pxPerCell / MASK_DIV)).mod(texSize).toFloat()
        val oy = (-(y0 * pxPerCell / MASK_DIV)).mod(texSize).toFloat()
        textureMatrix.setTranslate(ox, oy)
        fogPaint.shader.setLocalMatrix(textureMatrix)
        lightFogPaint.shader.setLocalMatrix(textureMatrix)
        val regions = regions
        if (regions == null) {
            maskCanvas.drawPaint(lightFogPaint)
        } else {
            maskCanvas.drawPaint(fogPaint)
            for (i in regions.counts.visitedDongs) {
                val o = regions.atlas.dongs[i].outline
                if (visible(o, x0, y0, x1, y1)) drawOutline(maskCanvas, o, DONG, i, pxPerCell / MASK_DIV, x0, y0, lightFogPaint)
            }
        }

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
        if (regions != null) {
            drawBoundaries(canvas, regions, pxPerCell, x0, y0, x1, y1)
            if (showLabels) {
                placedLabels.clear()
                // Keep labels off the car dot.
                current?.takeIf { showCurrent }?.let { (lat, lng) ->
                    val cx = ((Tiles.lngToX(lng) - x0) * pxPerCell).toFloat()
                    val cy = ((Tiles.latToY(lat) - y0) * pxPerCell).toFloat()
                    placedLabels.add(RectF(cx - 24 * density, cy - 24 * density, cx + 24 * density, cy + 24 * density))
                }
                drawLabels(canvas, regions, pxPerCell, x0, y0, x1, y1)
            }
        }

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

    private fun visible(o: Outline, x0: Double, y0: Double, x1: Double, y1: Double): Boolean =
        o.bbox[2] >= x0 && o.bbox[0] <= x1 && o.bbox[3] >= y0 && o.bbox[1] <= y1

    /**
     * Outline paths are in tile units relative to their bbox corner (floats can't hold zoom-20
     * coordinates to sub-tile precision); the offset to the view is computed in double.
     */
    private fun drawOutline(c: Canvas, o: Outline, level: Int, index: Int, scale: Double, x0: Double, y0: Double, vararg paints: Paint) {
        outlineMatrix.setScale(scale.toFloat(), scale.toFloat())
        outlineMatrix.postTranslate(((o.bbox[0] - x0) * scale).toFloat(), ((o.bbox[1] - y0) * scale).toFloat())
        OutlinePaths.get(level, index, o).transform(outlineMatrix, outlinePath)
        for (p in paints) c.drawPath(outlinePath, p)
    }

    private fun drawBoundaries(c: Canvas, r: RegionLayer, px: Double, x0: Double, y0: Double, x1: Double, y1: Double) {
        if (px >= DONG_LINES_PX) {
            r.atlas.dongs.forEachIndexed { i, d ->
                if (visible(d.outline, x0, y0, x1, y1)) drawOutline(c, d.outline, DONG, i, px, x0, y0, dongLine)
            }
        }
        if (px >= DISTRICT_LINES_PX) {
            r.atlas.districts.forEachIndexed { i, d ->
                val o = d.outline ?: return@forEachIndexed
                if (visible(o, x0, y0, x1, y1)) drawOutline(c, o, DISTRICT, i, px, x0, y0, districtHalo, districtLine)
            }
        }
        r.atlas.provinces.forEachIndexed { i, p ->
            val o = p.outline ?: return@forEachIndexed
            if (visible(o, x0, y0, x1, y1)) drawOutline(c, o, PROVINCE, i, px, x0, y0, provinceHalo, provinceLine)
        }
    }

    /** Name + rate of each entered region at the level that suits the zoom; overlapping labels are skipped. */
    private fun drawLabels(c: Canvas, r: RegionLayer, px: Double, x0: Double, y0: Double, x1: Double, y1: Double) {
        fun label(o: Outline?, name: String, rate: Double) {
            o ?: return
            if (o.labelX < x0 || o.labelX > x1 || o.labelY < y0 || o.labelY > y1) return
            val x = ((o.labelX - x0) * px).toFloat()
            var y = ((o.labelY - y0) * px).toFloat()
            val rateStr = formatRate(rate)
            val half = max(nameText.measureText(name), rateText.measureText(rateStr)) / 2 + 6 * density
            val box = RectF(x - half, y - nameText.textSize, x + half, y + rateText.textSize * 1.3f)
            // Taken: try once just below the obstacle (usually the car dot) before giving up.
            placedLabels.firstOrNull { RectF.intersects(it, box) }?.let { hit ->
                box.offset(0f, hit.bottom - box.top + 4 * density)
                y = box.top + nameText.textSize
                if (placedLabels.any { RectF.intersects(it, box) }) return
            }
            placedLabels.add(box)
            c.drawText(name, x, y, textHalo)
            c.drawText(name, x, y, nameText)
            val ry = y + rateText.textSize * 1.15f
            c.drawText(rateStr, x, ry, rateHalo)
            c.drawText(rateStr, x, ry, rateText)
        }
        val n = r.counts
        when {
            px >= DONG_LINES_PX -> r.atlas.dongs.forEachIndexed { i, d -> if (n.dong[i] > 0) label(d.outline, d.name, n.dongRate(i)) }
            px >= DISTRICT_LINES_PX -> r.atlas.districts.forEachIndexed { i, d -> if (n.district[i] > 0) label(d.outline, d.name, n.districtRate(i)) }
            else -> r.atlas.provinces.forEachIndexed { i, p -> if (n.province[i] > 0) label(p.outline, p.name, n.provinceRate(i)) }
        }
    }

    private companion object {
        const val PROVINCE = 0
        const val DISTRICT = 1
        const val DONG = 2
        /** Screen px per grid cell from which 읍·면·동 (zoom ~13) / 시·군·구 (zoom ~9) are drawn. */
        const val DONG_LINES_PX = 4.0
        const val DISTRICT_LINES_PX = 0.25
        const val MASK_DIV = 3
        const val MAX_DZ = 10
        const val TEXTURE_SIZE = 128
        private val textures = HashMap<Pair<Boolean, Boolean>, Bitmap>()

        /**
         * Tileable cloud texture (two octaves of smoothed value noise), built once per kind.
         * Dark fog is fully opaque, so nothing of a never-entered 동 shows. Light fog is a grey
         * at ~62-70 % so the map of an entered 동 shows faintly through it.
         */
        fun cloudTexture(night: Boolean, light: Boolean): Bitmap = textures.getOrPut(night to light) {
            val size = TEXTURE_SIZE
            val base = when {
                // Neutral greys/near-black, like the built-in apps' monochrome UI.
                light && night -> intArrayOf(66, 68, 72)
                light -> intArrayOf(124, 128, 134)
                night -> intArrayOf(6, 6, 8)
                else -> intArrayOf(18, 19, 22)
            }
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
                val alpha = if (light) (158 + v * 20).toInt() else 255
                pixels[y * size + x] = Color.argb(alpha, base[0] + lift, base[1] + lift, base[2] + lift)
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}

/** What the overlay needs from the region atlas: shapes, and explored cells per region. */
class RegionLayer(val atlas: RegionAtlas, val counts: RegionCounts)

/** Android paths of the region outlines, built on first use and kept (shapes never change). */
object OutlinePaths {
    private val cache = HashMap<Long, Path>()

    @Synchronized
    fun get(level: Int, index: Int, o: Outline): Path = cache.getOrPut((level.toLong() shl 32) or index.toLong()) {
        val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
        val ox = o.bbox[0]
        val oy = o.bbox[1]
        for (r in o.rings) {
            path.moveTo((r[0] - ox).toFloat(), (r[1] - oy).toFloat())
            for (k in 2 until r.size step 2) path.lineTo((r[k] - ox).toFloat(), (r[k + 1] - oy).toFloat())
            path.close()
        }
        path
    }
}
