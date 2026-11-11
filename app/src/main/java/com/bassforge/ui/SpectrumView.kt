package com.bassforge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.bassforge.util.NeoSurface
import com.bassforge.util.ThemeManager
import com.bassforge.util.Tick
import com.bassforge.util.approach
import com.bassforge.util.dp
import com.bassforge.util.sp
import com.bassforge.util.withAlpha

/**
 * Real audio levels only. With nothing playing (or without the mic/visualizer permission) the
 * bars sit flat and the tag says STANDBY — no fake wave. Bars ease toward incoming levels.
 */
class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val bars = 32
    private val target = FloatArray(bars)
    private val shown = FloatArray(bars)
    private val peak = FloatArray(bars)
    private var live = false
    private val tick = Tick()
    private val box = RectF()
    private val rr = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.16f
        textAlign = Paint.Align.RIGHT
    }

    fun setLevels(levels: FloatArray) {
        live = true
        for (i in 0 until bars) {
            target[i] = if (i < levels.size) levels[i].coerceIn(0f, 1f) else 0f
        }
        postInvalidateOnAnimation()
    }

    fun setStandby() {
        live = false
        java.util.Arrays.fill(target, 0f)
        postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(dp(132f).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val dt = tick.dt()
        val p = ThemeManager.p
        var moving = false
        for (i in 0 until bars) {
            val up = target[i] > shown[i]
            shown[i] = approach(shown[i], target[i], if (up) 40f else 9f, dt)
            if (shown[i] > peak[i]) peak[i] = shown[i] else peak[i] = approach(peak[i], shown[i], 1.6f, dt)
            if (kotlin.math.abs(shown[i] - target[i]) > 0.002f || peak[i] - shown[i] > 0.002f) moving = true
        }

        val m = dp(9f)
        box.set(m, m, width - m, height - m)
        NeoSurface.raised(canvas, box, dp(22f), p, 1f, 0.7f)

        val padX = dp(16f)
        val top = box.top + dp(30f)
        val bot = box.bottom - dp(14f)
        val inner = box.width() - padX * 2
        val slot = inner / bars
        val bw = slot * 0.58f
        val maxH = bot - top

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, bot, 0f, top, p.accent2, p.accent, Shader.TileMode.CLAMP)
        for (i in 0 until bars) {
            val x = box.left + padX + slot * i + (slot - bw) / 2f
            val h = dp(3f) + (maxH - dp(3f)) * shown[i]
            rr.set(x, bot - h, x + bw, bot)
            canvas.drawRoundRect(rr, bw / 2f, bw / 2f, paint)
        }
        paint.shader = null
        // peak caps
        paint.color = withAlpha(p.accent, 0.8f)
        for (i in 0 until bars) {
            if (peak[i] < 0.04f) continue
            val x = box.left + padX + slot * i + (slot - bw) / 2f
            val y = bot - (dp(3f) + (maxH - dp(3f)) * peak[i]) - dp(4f)
            canvas.drawRoundRect(x, y, x + bw, y + dp(2f), dp(1f), dp(1f), paint)
        }

        tag.textSize = sp(10f)
        tag.color = if (live) p.accent else p.textDim
        canvas.drawText(if (live) "LIVE" else "STANDBY", box.right - padX, box.top + dp(21f), tag)

        if (moving) postInvalidateOnAnimation() else tick.reset()
    }
}
