package com.bassforge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.bassforge.util.NeoColors
import com.bassforge.util.approach
import com.bassforge.util.dp
import com.bassforge.util.sp
import com.bassforge.util.withAlpha
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Horizontal glow slider with a label and a live value read-out.
 * [bipolar] sliders fill from the centre and detent at 0.5.
 */
class NeoSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var label: String = ""
        set(v) { field = v; contentDescription = v; invalidate() }

    var bipolar: Boolean = false

    /** Current value 0..1. Setting it programmatically does not fire [onChange]. */
    var value: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    var formatter: (Float) -> String = { "${(it * 100).roundToInt()}%" }
    var onChange: ((Float) -> Unit)? = null

    private var shown = 0f
    private var press = 0f
    private var pressed = false
    private var lastNanos = 0L

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NeoColors.TEXT_DIM
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.2f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = NeoColors.TRACK
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = withAlpha(Color.WHITE, 0.10f)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var thumbShader: RadialGradient? = null

    private val padX get() = dp(14f)
    private val trackY get() = height - dp(24f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resolveSize(dp(66f).toInt(), heightMeasureSpec)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = dp(12f)
        thumbShader = RadialGradient(
            -r * 0.3f, -r * 0.35f, r * 1.5f,
            intArrayOf(0xFFF2F4FF.toInt(), 0xFF8189A9.toInt(), 0xFF2A2F44.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.05f)
        lastNanos = now
        shown = approach(shown, value, 20f, dt)
        press = approach(press, if (pressed) 1f else 0f, 14f, dt)
        val moving = abs(shown - value) > 0.002f || (pressed && press < 0.99f) || (!pressed && press > 0.01f)
        if (!moving) shown = value

        val x0 = padX
        val x1 = width - padX
        val y = trackY
        val tx = x0 + (x1 - x0) * shown
        val color = NeoColors.ramp(0.05f + 0.55f * shown)

        labelPaint.textSize = sp(11f)
        canvas.drawText(label.uppercase(), padX, dp(20f), labelPaint)
        valuePaint.textSize = sp(14f)
        valuePaint.color = color
        canvas.drawText(formatter(shown), width - padX, dp(20f), valuePaint)

        // ticks
        tickPaint.strokeWidth = dp(1.5f)
        for (i in 0..20) {
            val x = x0 + (x1 - x0) * i / 20f
            val len = if (i % 5 == 0) dp(6f) else dp(3f)
            canvas.drawLine(x, y + dp(11f), x, y + dp(11f) + len, tickPaint)
        }

        trackPaint.strokeWidth = dp(6f)
        canvas.drawLine(x0, y, x1, y, trackPaint)

        val fx0 = if (bipolar) x0 + (x1 - x0) * 0.5f else x0
        val a = min(fx0, tx)
        val b = max(fx0, tx)
        if (b - a > 0.5f) {
            val widths = floatArrayOf(18f, 12f, 6f)
            val alphas = floatArrayOf(0.10f, 0.20f, 1f)
            for (i in widths.indices) {
                fillPaint.strokeWidth = dp(widths[i])
                fillPaint.color = withAlpha(color, alphas[i] * (0.7f + 0.3f * press))
                canvas.drawLine(a, y, b, y, fillPaint)
            }
        }
        if (bipolar) {
            tickPaint.color = withAlpha(Color.WHITE, 0.35f)
            canvas.drawLine(x0 + (x1 - x0) * 0.5f, y - dp(7f), x0 + (x1 - x0) * 0.5f, y + dp(7f), tickPaint)
            tickPaint.color = withAlpha(Color.WHITE, 0.10f)
        }

        // thumb
        val r = dp(12f) * (1f + 0.12f * press)
        glowPaint.shader = null
        glowPaint.color = withAlpha(color, 0.16f + 0.22f * press)
        canvas.drawCircle(tx, y, r * 1.9f, glowPaint)
        thumbPaint.color = Color.BLACK
        thumbPaint.alpha = 110
        canvas.drawCircle(tx + dp(1f), y + dp(3f), r, thumbPaint)
        canvas.save()
        canvas.translate(tx, y)
        val s = r / dp(12f)
        canvas.scale(s, s)
        thumbPaint.alpha = 255
        thumbPaint.shader = thumbShader
        canvas.drawCircle(0f, 0f, dp(12f), thumbPaint)
        thumbPaint.shader = null
        canvas.restore()
        thumbPaint.color = color
        canvas.drawCircle(tx, y, r * 0.30f, thumbPaint)

        if (moving) postInvalidateOnAnimation()
    }

    private fun valueAt(x: Float): Float {
        var v = ((x - padX) / (width - 2f * padX)).coerceIn(0f, 1f)
        v = (v * 100f).roundToInt() / 100f
        if (bipolar && abs(v - 0.5f) < 0.03f) v = 0.5f
        return v
    }

    private fun update(x: Float) {
        val v = valueAt(x)
        if (v != value) {
            if (bipolar && v == 0.5f) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            value = v
            onChange?.invoke(v)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pressed = true
                update(e.x)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                update(e.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
