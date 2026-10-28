package com.zobo.burung.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.maplibre.android.maps.Style
import kotlin.math.abs

/** Viewport that holds an oversized pan layer (map + fog) and a car marker fixed at the centre. */
internal class MapFrame(context: Context, val pan: FrameLayout, val car: View, val credit: View) : FrameLayout(context) {
    /** Oversize around the viewport: PAN_MARGIN_PX while driving, 0 when parked (no slide). */
    var margin = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }
    /** Called after the pan layer was resized (mode switch), so the driving camera can re-base. */
    var onPanResized: () -> Unit = {}
    init {
        clipChildren = true
        // Nothing here is meaningful to accessibility; hiding the whole subtree keeps per-frame
        // slides from emitting content-change events (TalkBack noise; UI tools never see "idle").
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(pan)
        addView(car)
        addView(credit)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        pan.measure(
            MeasureSpec.makeMeasureSpec(w + 2 * margin, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h + 2 * margin, MeasureSpec.EXACTLY),
        )
        car.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        credit.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
        setMeasuredDimension(w, h)
    }

    var slideX = 0f
        private set
    var slideY = 0f
        private set

    /**
     * Slides the pan layer at draw time only. Changing the child's translationX/Y would send a
     * TYPE_WINDOW_CONTENT_CHANGED accessibility event per step (even with the subtree hidden).
     */
    fun slide(x: Float, y: Float) {
        slideX = x
        slideY = y
        invalidate()
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child !== pan || (slideX == 0f && slideY == 0f)) return super.drawChild(canvas, child, drawingTime)
        val save = canvas.save()
        canvas.translate(slideX, slideY)
        val result = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(save)
        return result
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val oldW = pan.width
        val oldH = pan.height
        pan.layout(-margin, -margin, r - l + margin, b - t + margin)
        if (oldW != 0 && (oldW != pan.width || oldH != pan.height)) post { onPanResized() }
        car.layout(0, 0, r - l, b - t)
        val pad = 12
        credit.layout(pad, b - t - credit.measuredHeight - pad, pad + credit.measuredWidth, b - t - pad)
        // The oversized pan layer must never paint outside the viewport (it covered the nav rail).
        clipBounds = Rect(0, 0, r - l, b - t)
    }
}

/** Car marker at the exact centre of the viewport while driving, turned the way we drive. */
internal class CarMarkerView(context: Context) : View(context) {
    /** Degrees clockwise from north. */
    var bearing = 0f
        set(value) {
            if (abs(value - field) >= 2f) {
                field = value
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        CarIcon.draw(canvas, width / 2f, height / 2f, bearing, 1.1f)
    }
}

/** The car seen from above: a small mint car with a white outline and a halo, nose up at bearing 0. */
internal object CarIcon {
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 0x5C, 0xC8, 0xB0) }
    private val haloInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(72, 0x5C, 0xC8, 0xB0) }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0, 0, 0) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5CC8B0") }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E9FBFF") }
    private val lamp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFF6B0") }
    private fun ink(width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2F4A2C")
        style = Paint.Style.STROKE
        strokeWidth = width
    }
    private val bodyInk = ink(4f)
    private val glassInk = ink(3f)
    private val r = RectF()

    fun draw(c: Canvas, cx: Float, cy: Float, bearing: Float, scale: Float) {
        c.save()
        c.translate(cx, cy)
        c.scale(scale, scale)
        c.drawCircle(0f, 0f, 58f, halo)
        c.drawCircle(0f, 0f, 42f, haloInner)
        c.drawOval(-22f, -24f, 30f, 44f, shadow)
        c.rotate(bearing)
        r.set(-25f, -37f, 25f, 37f)
        c.drawRoundRect(r, 19f, 19f, outline)
        r.set(-20f, -32f, 20f, 32f)
        c.drawRoundRect(r, 15f, 15f, body)
        c.drawRoundRect(r, 15f, 15f, bodyInk)
        r.set(-13f, -16f, 13f, 6f)
        c.drawRoundRect(r, 6f, 6f, glass)
        c.drawRoundRect(r, 6f, 6f, glassInk)
        c.drawCircle(-11f, -26f, 4f, lamp)
        c.drawCircle(11f, -26f, 4f, lamp)
        c.restore()
    }
}
