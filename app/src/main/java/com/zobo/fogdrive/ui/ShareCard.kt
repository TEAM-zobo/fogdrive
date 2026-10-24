package com.zobo.fogdrive.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Environment
import com.zobo.fogdrive.FogRuntime
import com.zobo.fogdrive.geo.Area
import com.zobo.fogdrive.geo.Tiles
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max

/**
 * "내 안개지도" card: the explored footprint drawn as glowing cells on the fog, with level, area,
 * 읍·면·동 count and the best-explored 시·군·구, saved as a PNG to share. Drawn from the explored set
 * itself (not a screenshot), so it works offline and never shows street detail around home.
 */
object ShareCard {
    const val WIDTH = 1600
    const val HEIGHT = 900
    private const val MAP_SIZE = 820f
    private const val MAP_LEFT = 40f
    /** Coarsest-level cells across the map square: each stays at least ~2 px wide. */
    private const val MAX_SPAN_CELLS = 400.0
    /**
     * Share of the framed cells trimmed on each side (5th–95th percentile), so a stray cell at the
     * edge of the frame doesn't widen it.
     */
    private const val EDGE_TRIM = 0.05
    /** Blocks of 2^6 cells (~2 km); the frame covers the busiest block and [FRAME_BLOCKS] around it. */
    private const val BLOCK_DZ = 6
    private const val FRAME_BLOCKS = 3
    /** At least ~2 km across, so one short drive is a line on a map, not a few giant squares. */
    private const val MIN_SPAN_CELLS = 70.0
    private const val MIN_CELL_PX = 4f

    class Stats(
        val level: Int,
        val areaM2: Double,
        val dongs: Int,
        val stampKinds: Int,
        val landings: Int,
        val topDistrict: String?,
        val topRate: Double,
    )

    /** Renders and saves the card; returns the file. Call off the main thread. */
    fun create(context: Context, stats: Stats, landings: List<Pair<Double, Double>>): File {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bmp), stats, landings)
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: File(context.filesDir, Environment.DIRECTORY_PICTURES).apply { mkdirs() }
            // One card at a time: older ones are replaced instead of piling up in the car's storage.
            dir.listFiles { f -> f.name.startsWith(PREFIX) }?.forEach { it.delete() }
            val file = File(dir, PREFIX + SimpleDateFormat("yyyyMMdd-HHmm", Locale.KOREA).format(Date()) + ".png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return file
        } finally {
            bmp.recycle()
        }
    }

    private const val PREFIX = "fogdrive-card-"

    private fun draw(c: Canvas, s: Stats, landings: List<Pair<Double, Double>>) {
        c.drawColor(Color.rgb(0x10, 0x16, 0x22))
        val fog = Paint().apply { color = Color.rgb(0x1E, 0x27, 0x36) }
        val mapRect = RectF(MAP_LEFT, (HEIGHT - MAP_SIZE) / 2, MAP_LEFT + MAP_SIZE, (HEIGHT + MAP_SIZE) / 2)
        c.drawRoundRect(mapRect, 24f, 24f, fog)
        drawFootprint(c, mapRect, landings)

        val x = MAP_LEFT + MAP_SIZE + 70f
        val title = text(34f, Color.rgb(0x8C, 0x98, 0xAB))
        val big = text(96f, Color.rgb(0x2E, 0xD3, 0xB7), bold = true)
        val value = text(54f, Color.WHITE, bold = true)
        val label = text(30f, Color.rgb(0x8C, 0x98, 0xAB))
        c.drawText("안개지도 · 나의 개척 기록", x, 120f, title)
        c.drawText("Lv.${s.level}", x, 250f, big)
        var y = 360f
        fun row(name: String, v: String) {
            c.drawText(name, x, y, label)
            c.drawText(v, x, y + 58f, value)
            y += 120f
        }
        row("밝힌 면적", Area.format(s.areaM2))
        row("개척한 읍·면·동", "${s.dongs}곳 · 하차 ${s.landings}곳")
        row("모은 스탬프", "${s.stampKinds}종")
        s.topDistrict?.let { c.drawText("가장 많이 밝힌 곳  $it ${formatRate(s.topRate)}", x, y + 10f, label) }
        c.drawText(SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date()) + "  ·  #안개지도", x, HEIGHT - 60f, title)
    }

    /**
     * Explored cells inside the bulk of the footprint ([EDGE_TRIM], so one far trip does not shrink
     * home to a dot), at the finest level that keeps each cell a couple of pixels wide.
     */
    private fun drawFootprint(c: Canvas, rect: RectF, landings: List<Pair<Double, Double>>) {
        // Frame the busiest area: the block (~2 km) with the most explored cells and the blocks
        // around it. Places seeded from the navigation lie km apart; framing all of them left a few
        // specks on an empty square.
        val blocks = FogRuntime.repo.cellsAtLevel(BLOCK_DZ)
        if (blocks.isEmpty()) return
        val counts = HashMap<Long, Int>()
        for (cell in FogRuntime.repo.cellsAtLevel(0)) counts.merge(Tiles.parent(cell, BLOCK_DZ), 1, Int::plus)
        val busiest = counts.maxByOrNull { it.value }?.key ?: return
        val bx = Tiles.x(busiest)
        val by = Tiles.y(busiest)
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (cell in FogRuntime.repo.cellsAtLevel(0)) {
            val p = Tiles.parent(cell, BLOCK_DZ)
            if (kotlin.math.abs(Tiles.x(p) - bx) <= FRAME_BLOCKS && kotlin.math.abs(Tiles.y(p) - by) <= FRAME_BLOCKS) {
                xs += Tiles.x(cell)
                ys += Tiles.y(cell)
            }
        }
        xs.sort()
        ys.sort()
        val k = xs.size
        val lo = (k * EDGE_TRIM).toInt()
        val hi = (k * (1 - EDGE_TRIM)).toInt().coerceIn(lo, k - 1)
        val cx = (xs[lo] + xs[hi]) / 2.0 + 0.5
        val cy = (ys[lo] + ys[hi]) / 2.0 + 0.5
        val span = max(max(xs[hi] - xs[lo], ys[hi] - ys[lo]) + 1.0, MIN_SPAN_CELLS)
        val dz = max(0, ceil(ln(span / MAX_SPAN_CELLS) / ln(2.0)).toInt())
        // Some margin around the footprint inside the square.
        val pxPerFine = (rect.width() * 0.88f) / span
        // A few pixels at least, so a short first drive still reads as a glowing line, not dust.
        val cellPx = (pxPerFine * (1 shl dz)).toFloat().coerceAtLeast(MIN_CELL_PX)
        val glow = Paint().apply { color = Color.argb(70, 0x2E, 0xD3, 0xB7) }
        val core = Paint().apply { color = Color.rgb(0x2E, 0xD3, 0xB7) }
        val midX = rect.centerX()
        val midY = rect.centerY()
        c.save()
        c.clipRect(rect)
        for (cell in FogRuntime.repo.cellsAtLevel(dz)) {
            val px = (midX + ((Tiles.x(cell).toDouble() * (1 shl dz)) - cx) * pxPerFine).toFloat()
            val py = (midY + ((Tiles.y(cell).toDouble() * (1 shl dz)) - cy) * pxPerFine).toFloat()
            c.drawRect(px - cellPx, py - cellPx, px + cellPx * 2, py + cellPx * 2, glow)
            c.drawRect(px, py, px + cellPx, py + cellPx, core)
        }
        val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xFF, 0x9F, 0x1C) }
        for ((lat, lng) in landings) {
            val px = (midX + (Tiles.lngToX(lng) - cx) * pxPerFine).toFloat()
            val py = (midY + (Tiles.latToY(lat) - cy) * pxPerFine).toFloat()
            c.drawCircle(px, py, 7f, pin)
        }
        c.restore()
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
}
