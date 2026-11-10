package com.bassforge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.bassforge.R
import com.bassforge.util.NeoColors
import com.bassforge.util.approach
import com.bassforge.util.dp
import com.bassforge.util.sp
import com.bassforge.util.withAlpha
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 10-band graphic equalizer, -12..+12 dB. Every band is its own glowing slider; several
 * fingers can drag several bands at once. A smooth response curve with a glow is drawn
 * through the knobs. Double-tap a band to reset it. Preset changes animate with a spring-like
 * ease.
 */
class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Fired for user edits only: (band index, gain dB). */
    var onBandChanged: ((Int, Float) -> Unit)? = null

    private companion object {
        const val N = 10
        const val MAX_DB = 12f
        val LABELS = arrayOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    }

    private val target = FloatArray(N)
    private val shown = FloatArray(N)
    private val valueText = Array(N) { "0" }
    private val valueInt = IntArray(N) { Int.MIN_VALUE }

    private val pointerBand = SparseIntArray()
    private var lastTapBand = -1
    private var lastTapTime = 0L
    private var lastDrawNanos = 0L

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = withAlpha(Color.WHITE, 0.06f)
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = withAlpha(Color.WHITE, 0.22f)
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
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = NeoColors.TEXT_DIM
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = NeoColors.TEXT
    }

    private val curve = Path()
    private val area = Path()
    private var knobShader: RadialGradient? = null

    init {
        contentDescription = context.getString(R.string.eq_desc)
    }

    // ── API ──

    /** Sets all bands (animated). Does not fire [onBandChanged]. */
    fun setGains(gains: FloatArray) {
        for (i in 0 until N) target[i] = gains.getOrElse(i) { 0f }.coerceIn(-MAX_DB, MAX_DB)
        invalidate()
    }

    // ── Layout helpers ──

    private val padX get() = dp(8f)
    private val trackTop get() = dp(30f)
    private val trackBottom get() = height - dp(26f)
    private fun bandX(i: Int): Float {
        val sp = (width - 2f * padX) / N
        return padX + sp * (i + 0.5f)
    }
    private fun yOf(g: Float): Float {
        val mid = (trackTop + trackBottom) / 2f
        return mid - g / MAX_DB * (trackBottom - trackTop) / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val mid = (trackTop + trackBottom) / 2f
        areaPaint.shader = LinearGradient(
            0f, trackTop, 0f, trackBottom,
            intArrayOf(withAlpha(NeoColors.CYAN, 0.30f), Color.TRANSPARENT, withAlpha(NeoColors.MAGENTA, 0.30f)),
            floatArrayOf(0f, (mid - trackTop) / (trackBottom - trackTop), 1f), Shader.TileMode.CLAMP
        )
        curvePaint.shader = LinearGradient(
            padX, 0f, w - padX, 0f,
            intArrayOf(NeoColors.ACCENT, NeoColors.CYAN, NeoColors.MAGENTA),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        val r = dp(11f)
        knobShader = RadialGradient(
            -r * 0.3f, -r * 0.35f, r * 1.5f,
            intArrayOf(0xFFEFF2FF.toInt(), 0xFF7F88A8.toInt(), 0xFF2A2F44.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    // ── Drawing ──

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastDrawNanos == 0L) 0.016f else ((now - lastDrawNanos) / 1e9f).coerceIn(0.001f, 0.05f)
        lastDrawNanos = now

        var moving = false
        for (i in 0 until N) {
            shown[i] = approach(shown[i], target[i], 22f, dt)
            if (abs(shown[i] - target[i]) > 0.01f) moving = true else shown[i] = target[i]
        }

        val mid = (trackTop + trackBottom) / 2f
        val w = width.toFloat()

        // grid
        gridPaint.strokeWidth = dp(1f)
        for (g in intArrayOf(12, 6, -6, -12)) canvas.drawLine(padX, yOf(g.toFloat()), w - padX, yOf(g.toFloat()), gridPaint)
        zeroPaint.strokeWidth = dp(1f)
        zeroPaint.pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(5f)), 0f)
        canvas.drawLine(padX, mid, w - padX, mid, zeroPaint)

        // response curve + area
        curve.reset()
        curve.moveTo(bandX(0), yOf(shown[0]))
        for (i in 1 until N) {
            val x0 = bandX(i - 1)
            val x1 = bandX(i)
            val mx = (x0 + x1) / 2f
            curve.cubicTo(mx, yOf(shown[i - 1]), mx, yOf(shown[i]), x1, yOf(shown[i]))
        }
        area.set(curve)
        area.lineTo(bandX(N - 1), mid)
        area.lineTo(bandX(0), mid)
        area.close()
        canvas.drawPath(area, areaPaint)
        val glowW = floatArrayOf(14f, 8f)
        val glowA = floatArrayOf(0.10f, 0.20f)
        for (k in glowW.indices) {
            curvePaint.strokeWidth = dp(glowW[k])
            curvePaint.alpha = (glowA[k] * 255f).toInt()
            canvas.drawPath(curve, curvePaint)
        }
        curvePaint.strokeWidth = dp(2.5f)
        curvePaint.alpha = 255
        canvas.drawPath(curve, curvePaint)

        // bands
        labelPaint.textSize = sp(10f)
        valuePaint.textSize = sp(11f)
        trackPaint.strokeWidth = dp(3f)
        for (i in 0 until N) {
            val x = bandX(i)
            val g = shown[i]
            val y = yOf(g)
            val mag = abs(g) / MAX_DB

            canvas.drawLine(x, trackTop, x, trackBottom, trackPaint)

            if (abs(g) > 0.05f) {
                val c = if (g >= 0f) NeoColors.ramp(0.15f + 0.25f * mag) else NeoColors.MAGENTA
                fillPaint.color = withAlpha(c, 0.12f)
                fillPaint.strokeWidth = dp(12f)
                canvas.drawLine(x, mid, x, y, fillPaint)
                fillPaint.color = withAlpha(c, 0.28f)
                fillPaint.strokeWidth = dp(7f)
                canvas.drawLine(x, mid, x, y, fillPaint)
                fillPaint.color = c
                fillPaint.strokeWidth = dp(3f)
                canvas.drawLine(x, mid, x, y, fillPaint)
            }

            drawKnob(canvas, x, y, mag, g)

            val dragging = pointerBand.indexOfValue(i) >= 0
            labelPaint.color = if (dragging || abs(g) > 0.05f) NeoColors.TEXT else NeoColors.TEXT_DIM
            canvas.drawText(LABELS[i], x, height - dp(8f), labelPaint)

            val r = g.roundToInt()
            if (valueInt[i] != r) {
                valueInt[i] = r
                valueText[i] = if (r > 0) "+$r" else "$r"
            }
            valuePaint.color = if (abs(g) > 0.05f) NeoColors.ramp(0.2f + 0.3f * mag) else NeoColors.TEXT_DIM
            canvas.drawText(valueText[i], x, dp(16f), valuePaint)
        }

        if (moving) postInvalidateOnAnimation()
    }

    private fun drawKnob(canvas: Canvas, x: Float, y: Float, mag: Float, g: Float) {
        val r = dp(11f)
        // bloom
        knobRing.style = Paint.Style.FILL
        knobRing.color = withAlpha(NeoColors.ramp(0.2f + 0.3f * mag), 0.10f + 0.20f * mag)
        canvas.drawCircle(x, y, r * 2.0f, knobRing)
        // drop shadow
        shadowPaint.alpha = 110
        canvas.drawCircle(x + dp(1f), y + dp(3f), r, shadowPaint)
        // metal body
        canvas.save()
        canvas.translate(x, y)
        knobPaint.shader = knobShader
        canvas.drawCircle(0f, 0f, r, knobPaint)
        knobPaint.shader = null
        canvas.restore()
        // ring + core
        knobRing.style = Paint.Style.STROKE
        knobRing.strokeWidth = dp(2f)
        knobRing.color = if (g >= 0f) NeoColors.ramp(0.2f + 0.3f * mag) else NeoColors.MAGENTA
        knobRing.alpha = (255f * (0.45f + 0.55f * minOf(1f, mag * 3f))).toInt()
        canvas.drawCircle(x, y, r * 0.62f, knobRing)
        knobPaint.color = 0xFF0B0D15.toInt()
        canvas.drawCircle(x, y, r * 0.22f, knobPaint)
    }

    // ── Touch ──

    private fun bandAt(x: Float): Int {
        val sp = (width - 2f * padX) / N
        return ((x - padX) / sp).toInt().coerceIn(0, N - 1)
    }

    private fun gainAt(y: Float): Float {
        val mid = (trackTop + trackBottom) / 2f
        val raw = (mid - y) / ((trackBottom - trackTop) / 2f) * MAX_DB
        val snapped = (raw.coerceIn(-MAX_DB, MAX_DB) * 2f).roundToInt() / 2f   // 0.5 dB steps
        return if (abs(snapped) < 0.5f) 0f else snapped                        // detent at 0 dB
    }

    private fun setBand(i: Int, g: Float) {
        if (g == target[i]) return
        if (g == 0f || (g > 0f) != (target[i] > 0f) && target[i] != 0f) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        target[i] = g
        onBandChanged?.invoke(i, g)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val idx = e.actionIndex
                val id = e.getPointerId(idx)
                val band = bandAt(e.getX(idx))
                pointerBand.put(id, band)
                if (band == lastTapBand && e.eventTime - lastTapTime < 300L) {
                    setBand(band, 0f)                     // double tap resets the band
                    lastTapBand = -1
                } else {
                    lastTapBand = band
                    lastTapTime = e.eventTime
                    setBand(band, gainAt(e.getY(idx)))
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until e.pointerCount) {
                    val band = pointerBand.get(e.getPointerId(p), -1)
                    if (band >= 0) setBand(band, gainAt(e.getY(p)))
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                pointerBand.delete(e.getPointerId(e.actionIndex))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerBand.clear()
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
