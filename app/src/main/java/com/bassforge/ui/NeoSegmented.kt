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
import com.bassforge.util.blend
import com.bassforge.util.clamp01
import com.bassforge.util.dp
import com.bassforge.util.haptic
import com.bassforge.util.sp
import kotlin.math.abs

/** Segmented control: inset rail with a raised, backlit thumb that glides between options. */
class NeoSegmented @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var options: List<String> = listOf("A", "B")
        set(v) { field = v; invalidate() }

    var index = 0
        private set

    var onSelect: ((Int) -> Unit)? = null

    private val pos = Spring(0f, 0f, 460f, 28f)
    private val tick = Tick()
    private val rail = RectF()
    private val thumb = RectF()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val pad get() = dp(10f)

    init {
        isClickable = true
    }

    fun select(i: Int, animate: Boolean = true) {
        index = i.coerceIn(0, options.size - 1)
        pos.target = index.toFloat()
        if (!animate) pos.snap(pos.target)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize((dp(50f) + pad * 2).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val dt = tick.dt()
        val moving = pos.step(dt)
        val p = ThemeManager.p
        rail.set(pad, pad, width - pad, height - pad)
        val rad = rail.height() / 2f
        NeoSurface.raised(canvas, rail, rad, p, 1f, 0.7f)

        val n = options.size
        val segW = rail.width() / n
        val inset = dp(4f)
        val left = rail.left + segW * pos.value + inset
        thumb.set(left, rail.top + inset, left + segW - inset * 2, rail.bottom - inset)
        NeoSurface.backlight(canvas, thumb, thumb.height() / 2f, p, p.accent, 0.8f)
        NeoSurface.raised(canvas, thumb, thumb.height() / 2f, p, 0f, 0.55f)

        textPaint.textSize = sp(14f)
        val fm = textPaint.fontMetrics
        val baseY = rail.centerY() - (fm.ascent + fm.descent) / 2f
        for (i in 0 until n) {
            val near = clamp01(1f - abs(pos.value - i))
            textPaint.color = blend(p.textDim, p.accent, near)
            canvas.drawText(options[i], rail.left + segW * (i + 0.5f), baseY, textPaint)
        }
        if (moving) postInvalidateOnAnimation() else tick.reset()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val segW = (width - pad * 2) / options.size
                val i = ((e.x - pad) / segW).toInt().coerceIn(0, options.size - 1)
                if (i != index) {
                    select(i)
                    haptic(Haptic.TAP)
                    onSelect?.invoke(i)
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
