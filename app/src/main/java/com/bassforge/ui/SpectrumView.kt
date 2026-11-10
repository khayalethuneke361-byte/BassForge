package com.bassforge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.bassforge.audio.SpectrumTap
import com.bassforge.util.FrameLoop
import com.bassforge.util.NeoColors
import com.bassforge.util.approach
import com.bassforge.util.dp
import com.bassforge.util.sp
import com.bassforge.util.withAlpha
import kotlin.math.max
import kotlin.math.sin

/**
 * Mirrored glowing spectrum with falling peak caps and a reflection. Live data comes from
 * [setLevels]. With no data (no permission, or no player) it idles in a dim "standby" wave and
 * says so in the corner, so it never pretends to show audio it doesn't have.
 */
class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val n = SpectrumTap.BARS
    private val target = FloatArray(n)
    private val shown = FloatArray(n)
    private val peak = FloatArray(n)
    private val peakVel = FloatArray(n)

    private var t = 0f
    private var lastDataAt = -10f
    private var live = false

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reflectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NeoColors.TEXT_DIM
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.2f
    }
    private val rect = RectF()

    private val loop = FrameLoop { dt ->
        step(dt)
        invalidate()
    }

    /** Push [n] magnitudes in 0..1 (call as often as data arrives). */
    fun setLevels(levels: FloatArray) {
        for (i in 0 until n) target[i] = levels.getOrElse(i) { 0f }.coerceIn(0f, 1f)
        lastDataAt = t
        live = true
    }

    /** Declare that no live data is coming (permission denied / nothing playing). */
    fun setStandby() { live = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(dp(110f).toInt(), heightMeasureSpec)
        )
    }

    private val baseY get() = height * 0.72f
    private val maxH get() = height * 0.62f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barPaint.shader = LinearGradient(
            0f, baseY - maxH, 0f, baseY,
            intArrayOf(NeoColors.MAGENTA, NeoColors.CYAN, NeoColors.ACCENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        glowPaint.shader = barPaint.shader
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) loop.start() else loop.stop()
    }

    override fun onDetachedFromWindow() {
        loop.stop()
        super.onDetachedFromWindow()
    }

    private fun step(dt: Float) {
        t += dt
        if (live && t - lastDataAt > 0.6f) live = false
        for (i in 0 until n) {
            val goal = if (live) target[i] else 0.05f + 0.035f * sin(t * 1.6f - i * 0.45f)
            val rate = if (goal > shown[i]) 32f else 9f      // fast attack, slow release
            shown[i] = approach(shown[i], goal, rate, dt)
            if (shown[i] >= peak[i]) {
                peak[i] = shown[i]
                peakVel[i] = 0f
            } else {
                peakVel[i] += dt * 1.4f                       // gravity
                peak[i] = max(shown[i], peak[i] - peakVel[i] * dt)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val pad = dp(10f)
        val slot = (w - 2f * pad) / n
        val bw = slot * 0.62f
        val base = baseY
        val dimF = if (live) 1f else 0.45f

        for (i in 0 until n) {
            val x = pad + slot * i + (slot - bw) / 2f
            val hgt = max(dp(2f), shown[i] * maxH)

            // glow
            glowPaint.alpha = (60f * dimF).toInt()
            rect.set(x - bw * 0.45f, base - hgt - dp(2f), x + bw * 1.45f, base)
            canvas.drawRoundRect(rect, bw, bw, glowPaint)

            // bar
            barPaint.alpha = (255f * dimF).toInt()
            rect.set(x, base - hgt, x + bw, base)
            canvas.drawRoundRect(rect, bw / 2f, bw / 2f, barPaint)

            // reflection
            reflectPaint.color = withAlpha(NeoColors.ramp(i / n.toFloat()), 0.16f * dimF)
            rect.set(x, base + dp(3f), x + bw, base + dp(3f) + hgt * 0.38f)
            canvas.drawRoundRect(rect, bw / 2f, bw / 2f, reflectPaint)

            // peak cap
            if (live) {
                val py = base - max(dp(2f), peak[i] * maxH) - dp(4f)
                capPaint.alpha = 200
                rect.set(x, py - dp(1.5f), x + bw, py + dp(1.5f))
                canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), capPaint)
            }
        }

        // status tag
        tagPaint.textSize = sp(10f)
        dotPaint.color = if (live) NeoColors.CYAN else NeoColors.TEXT_DIM
        canvas.drawCircle(pad + dp(4f), dp(12f), dp(3f), dotPaint)
        canvas.drawText(if (live) "LIVE SPECTRUM" else "STANDBY", pad + dp(14f), dp(16f), tagPaint)
    }
}
