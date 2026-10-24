package com.zobo.fogdrive.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
 * Draws the fog of war on top of the map. The fog is painted into a reduced-resolution mask
 * where each explored cell is punched out as a slightly oversized circle (neighbours merge into
 * smooth strips instead of a staircase), then scaled up with filtering for soft edges.
 * At low zoom it switches to coarser parent tiles so the cost stays bounded.
 * Map rotation/tilt are disabled, so tile coordinates map linearly to the screen.
 */
class FogOverlayView(context: Context) : View(context) {
    var map: MapLibreMap? = null
    var cellsAtLevel: (Int) -> Set<Long> = { emptySet() }
    var current: Pair<Double, Double>? = null
    var landings: List<Pair<Double, Double>> = emptyList()
    var detour: Pair<Double, Double>? = null

    private var mask: Bitmap? = null
    private val maskCanvas = Canvas()
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG)
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
        val m = map ?: return
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        val proj = m.projection
        val tl = proj.fromScreenLocation(PointF(0f, 0f))
        val br = proj.fromScreenLocation(PointF(w.toFloat(), h.toFloat()))
        val x0 = Tiles.lngToX(tl.longitude)
        val y0 = Tiles.latToY(tl.latitude)
        val x1 = Tiles.lngToX(br.longitude)
        val y1 = Tiles.latToY(br.latitude)
        if (x1 <= x0 || y1 <= y0) return
        val pxPerCell = w / (x1 - x0)

        val mw = max(1, w / MASK_DIV)
        val mh = max(1, h / MASK_DIV)
        val bmp = mask?.takeIf { it.width == mw && it.height == mh }
            ?: Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888).also { mask = it }
        maskCanvas.setBitmap(bmp)
        bmp.eraseColor(FOG_COLOR)

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
        fun punch(cx: Int, cy: Int) {
            val centerX = (((cx + 0.5) * scale - x0) * pxPerCell / MASK_DIV).toFloat()
            val centerY = (((cy + 0.5) * scale - y0) * pxPerCell / MASK_DIV).toFloat()
            maskCanvas.drawCircle(centerX, centerY, radius, clearPaint)
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
        current?.let { (lat, lng) ->
            val p = toScreen(lat, lng)
            canvas.drawCircle(p.x, p.y, 16f, dotPaint)
            canvas.drawCircle(p.x, p.y, 16f, dotRing)
        }
    }

    private companion object {
        const val MASK_DIV = 3
        const val MAX_DZ = 10
        val FOG_COLOR = Color.argb(236, 20, 27, 38)
    }
}
