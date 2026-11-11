package com.bassforge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.bassforge.util.Haptic
import com.bassforge.util.NeoSurface
import com.bassforge.util.Spring
import com.bassforge.util.ThemeManager
import com.bassforge.util.Tick
import com.bassforge.util.dp
import com.bassforge.util.haptic
import com.bassforge.util.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Inset track with a gradient fill and a raised thumb that lifts and glows while held.
 * [bipolar] sliders fill from the centre and click into a detent at 0.5.
 */
class NeoSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var label: String = ""
        set(v) { field = v; contentDescription = v; invalidate() }

    var bipolar = false

    var value: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); pos.target = field; invalidate() }

    var formatter: (Float) -> String = { "${(it * 100).roundToInt()}%" }
    var onChange: ((Float) -> Unit)? = null

    private val pos = Spring(0f, 0f, 700f, 48f)
    private val lift = Spring(0f, 0f, 520f, 26f)
    private val tick = Tick()
    private var dragging = false

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.12f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val track = RectF()
    private val fillR = RectF()
    private val thumb = RectF()

    private val sidePad get() = dp(22f)
    private val trackH get() = dp(14f)
    private val thumbR get() = dp(14f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(dp(84f).toInt(), heightMeasureSpec)
        )
    }

    private fun x0() = sidePad + thumbR
    private fun x1() = width - sidePad - thumbR
    private fun cy() = height - dp(30f)

    override fun onDraw(canvas: Canvas) {
        val dt = tick.dt()
        val moving = pos.step(dt) or lift.step(dt)
        val p = ThemeManager.p
        val d = dp(1f)

        labelPaint.textSize = sp(12f)
        labelPaint.color = p.textDim
        canvas.drawText(label.uppercase(), sidePad, dp(24f), labelPaint)
        valuePaint.textSize = sp(15f)
        valuePaint.color = p.accent
        canvas.drawText(formatter(pos.value.coerceIn(0f, 1f)), width - sidePad, dp(24f), valuePaint)

        val cy = cy()
        val tx = x0() + (x1() - x0()) * pos.value.coerceIn(0f, 1f)

        // inset track
        track.set(sidePad, cy - trackH / 2f, width - sidePad, cy + trackH / 2f)
        NeoSurface.raised(canvas, track, trackH / 2f, p, 1f, 0.45f)

        // gradient fill
        val startX = if (bipolar) (x0() + x1()) / 2f else track.left + dp(3f)
        val a = min(startX, tx)
        val b = max(startX, tx)
        if (b - a > dp(2f)) {
            fillR.set(a, cy - trackH / 2f + dp(3f), b, cy + trackH / 2f - dp(3f))
            NeoSurface.backlight(canvas, fillR, fillR.height() / 2f, p, p.accent, 0.6f)
            NeoSurface.accentFill(canvas, fillR, fillR.height() / 2f, p, 0f)
        }

        // thumb
        val r = thumbR * (1f + 0.10f * lift.value)
        thumb.set(tx - r, cy - r - dp(1f) * lift.value, tx + r, cy + r - dp(1f) * lift.value)
        NeoSurface.backlight(canvas, thumb, r, p, p.accent, lift.value.coerceIn(0f, 1f) * 0.9f)
        NeoSurface.raised(canvas, thumb, r, p, 0f, 0.55f)
        // grip dot
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.color = p.accent
        canvas.drawCircle(tx, thumb.centerY(), 3.2f * d, dot)

        if (moving) postInvalidateOnAnimation() else tick.reset()
    }

    private fun valueAt(x: Float): Float {
        var v = ((x - x0()) / (x1() - x0())).coerceIn(0f, 1f)
        v = (v * 100f).roundToInt() / 100f
        if (bipolar && abs(v - 0.5f) < 0.03f) v = 0.5f
        return v
    }

    private fun update(x: Float) {
        val v = valueAt(x)
        if (v != value) {
            val crossedDetent = bipolar && v == 0.5f
            val steps = (v * 20f).toInt() != (value * 20f).toInt()
            value = v
            if (crossedDetent) haptic(Haptic.TAP) else if (steps) haptic(Haptic.TICK)
            onChange?.invoke(v)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                lift.target = 1f
                update(e.x)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) update(e.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                lift.target = 0f
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
