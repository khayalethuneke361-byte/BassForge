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
import android.view.MotionEvent
import android.view.View
import com.bassforge.util.Haptic
import com.bassforge.util.NeoSurface
import com.bassforge.util.Spring
import com.bassforge.util.ThemeManager
import com.bassforge.util.Tick
import com.bassforge.util.blend
import com.bassforge.util.dp
import com.bassforge.util.haptic
import com.bassforge.util.sp
import com.bassforge.util.withAlpha
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ten vertical inset capsules (-12..+12 dB), each with its own hue sweeping across the spectrum.
 * Multi-touch: drag several bands at once. Double-tap a band to flatten it.
 */
class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        const val RANGE = 12f
        val FREQS = arrayOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    }

    var onBandChanged: ((band: Int, gainDb: Float) -> Unit)? = null

    private val n = FREQS.size
    private val gains = FloatArray(n)
    private val springs = Array(n) { Spring(0f, 0f, 620f, 40f) }
    private val lifts = Array(n) { Spring(0f, 0f, 520f, 26f) }
    private val owner = HashMap<Int, Int>() // pointerId -> band
    private val lastTap = LongArray(n)
    private val tick = Tick()

    private val capsule = RectF()
    private val fillR = RectF()
    private val knob = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val sidePad get() = dp(10f)
    private val topPad get() = dp(26f)
    private val botPad get() = dp(30f)

    fun setGains(g: FloatArray) {
        for (i in 0 until n) {
            gains[i] = (if (i < g.size) g[i] else 0f).coerceIn(-RANGE, RANGE)
            springs[i].target = gains[i] / RANGE
        }
        invalidate()
    }

    private fun bandHue(i: Int): Int {
        val t = i / (n - 1f)
        val hsv = floatArrayOf(205f + 150f * t, 0.72f, if (ThemeManager.p.dark) 1f else 0.95f)
        return Color.HSVToColor(hsv)
    }

    private fun slotW() = (width - sidePad * 2) / n
    private fun capW() = min(dp(26f), slotW() * 0.62f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(dp(300f).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val dt = tick.dt()
        var moving = false
        for (i in 0 until n) {
            moving = springs[i].step(dt) or moving
            moving = lifts[i].step(dt) or moving
        }
        val p = ThemeManager.p
        val slot = slotW()
        val cw = capW()
        val top = topPad
        val bot = height - botPad
        val midY = (top + bot) / 2f

        // 0 dB guide
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(p.textDim, 0.28f)
        canvas.drawRect(sidePad, midY - 0.5f, width - sidePad, midY + 0.5f, paint)

        for (i in 0 until n) {
            val cx = sidePad + slot * (i + 0.5f)
            val hue = bandHue(i)
            val v = springs[i].value.coerceIn(-1f, 1f)
            val lift = lifts[i].value.coerceIn(0f, 1f)

            capsule.set(cx - cw / 2f, top, cx + cw / 2f, bot)
            NeoSurface.raised(canvas, capsule, cw / 2f, p, 1f, 0.5f)

            // fill from centre to knob
            val ky = midY - v * (bot - top - cw) / 2f
            val a = min(midY, ky)
            val b = max(midY, ky)
            if (b - a > dp(2f)) {
                val inset = dp(3f)
                fillR.set(cx - cw / 2f + inset, a, cx + cw / 2f - inset, b)
                paint.shader = LinearGradient(
                    0f, if (v >= 0f) fillR.bottom else fillR.top, 0f, if (v >= 0f) fillR.top else fillR.bottom,
                    withAlpha(hue, 0.35f), hue, Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(fillR, cw / 2f - inset, cw / 2f - inset, paint)
                paint.shader = null
            }

            val kr = cw / 2f + dp(2f) + dp(1.5f) * lift
            knob.set(cx - kr, ky - kr, cx + kr, ky + kr)
            NeoSurface.backlight(canvas, knob, kr, p, hue, 0.35f + 0.65f * lift)
            NeoSurface.raised(canvas, knob, kr, p, 0f, 0.5f)
            paint.color = hue
            canvas.drawCircle(cx, ky, dp(3.2f), paint)

            // value + frequency
            txt.textSize = sp(10.5f)
            txt.color = blend(p.textDim, hue, lift)
            val g = gains[i]
            val label = if (abs(g) < 0.05f) "0" else (if (g > 0) "+" else "") + (g * 10f).roundToInt() / 10f
            canvas.drawText(label, cx, dp(14f), txt)
            txt.color = p.textDim
            txt.textSize = sp(11f)
            canvas.drawText(FREQS[i], cx, height - dp(9f), txt)
        }

        if (moving) postInvalidateOnAnimation() else tick.reset()
    }

    private fun bandAt(x: Float): Int = ((x - sidePad) / slotW()).toInt().coerceIn(0, n - 1)

    private fun setFromY(band: Int, y: Float) {
        val top = topPad + capW() / 2f
        val bot = height - botPad - capW() / 2f
        val t = ((bot - y) / (bot - top)).coerceIn(0f, 1f)
        var g = (t * 2f - 1f) * RANGE
        g = (g * 2f).roundToInt() / 2f          // 0.5 dB steps
        if (abs(g) < 0.5f) g = 0f               // sticky zero
        if (g != gains[band]) {
            val crossed = (g == 0f) || (g > 0f) != (gains[band] > 0f)
            gains[band] = g
            springs[band].target = g / RANGE
            if (crossed) haptic(Haptic.TAP) else if (g.toInt() != 0 && g % 3f == 0f) haptic(Haptic.TICK)
            onBandChanged?.invoke(band, g)
            invalidate()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val idx = e.actionIndex
                val id = e.getPointerId(idx)
                val band = bandAt(e.getX(idx))
                owner[id] = band
                lifts[band].target = 1f
                val now = e.eventTime
                if (now - lastTap[band] < 280L) {
                    gains[band] = 0f
                    springs[band].target = 0f
                    haptic(Haptic.CONFIRM)
                    onBandChanged?.invoke(band, 0f)
                } else {
                    setFromY(band, e.getY(idx))
                }
                lastTap[band] = now
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val band = owner[e.getPointerId(i)] ?: continue
                    setFromY(band, e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP || e.actionMasked == MotionEvent.ACTION_UP) e.actionIndex else -1
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    for (b in owner.values) lifts[b].target = 0f
                    owner.clear()
                } else {
                    val band = owner.remove(e.getPointerId(idx))
                    if (band != null) lifts[band].target = 0f
                }
                if (owner.isEmpty()) parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }
}
