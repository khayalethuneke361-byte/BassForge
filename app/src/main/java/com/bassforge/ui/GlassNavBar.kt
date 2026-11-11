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

/**
 * Floating frosted-glass tab bar (iOS style). A raised, backlit lens slides under the active tab
 * with a little overshoot. Page content scrolls underneath it.
 */
class GlassNavBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    class Tab(val label: String, val icon: Ico)

    var tabs: List<Tab> = listOf(
        Tab("Bass", Ico.SPINNER),
        Tab("EQ", Ico.SLIDERS),
        Tab("Settings", Ico.GEAR)
    )
        set(v) { field = v; invalidate() }

    var index = 0
        private set

    var onTab: ((Int) -> Unit)? = null

    private val pos = Spring(0f, 0f, 430f, 26f)
    private val tick = Tick()
    private val bar = RectF()
    private val lens = RectF()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val sideInset get() = dp(14f)
    private val barH get() = dp(66f)

    init { isClickable = true }

    fun select(i: Int, animate: Boolean = true) {
        index = i.coerceIn(0, tabs.size - 1)
        pos.target = index.toFloat()
        if (!animate) pos.snap(pos.target)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize((barH + dp(28f)).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val dt = tick.dt()
        val moving = pos.step(dt)
        val p = ThemeManager.p
        val n = tabs.size

        val bottomPad = dp(12f)
        bar.set(sideInset, height - bottomPad - barH, width - sideInset, height - bottomPad)
        val rad = barH / 2f
        NeoSurface.glass(canvas, bar, rad, p)

        val slot = (bar.width() - dp(12f)) / n
        val lensInset = dp(6f)
        val left = bar.left + dp(6f) + slot * pos.value
        lens.set(left, bar.top + lensInset, left + slot, bar.bottom - lensInset)
        val lr = lens.height() / 2f
        NeoSurface.backlight(canvas, lens, lr, p, p.accent, 0.85f)
        NeoSurface.glass(canvas, lens, lr, p, p.accent, 1f)

        textPaint.textSize = sp(11.5f)
        for (i in 0 until n) {
            val near = clamp01(1f - abs(pos.value - i))
            val col = blend(p.textDim, p.accent, near)
            val cx = bar.left + dp(6f) + slot * (i + 0.5f)
            val lift = dp(2.5f) * near
            Icons.draw(canvas, tabs[i].icon, cx, bar.centerY() - dp(9f) - lift, dp(24f), col, dp(2f))
            textPaint.color = col
            canvas.drawText(tabs[i].label, cx, bar.centerY() + dp(18f) - lift, textPaint)
        }

        if (moving) postInvalidateOnAnimation() else tick.reset()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return e.y >= bar.top - dp(8f)
            MotionEvent.ACTION_UP -> {
                val slot = (bar.width() - dp(12f)) / tabs.size
                val i = ((e.x - bar.left - dp(6f)) / slot).toInt().coerceIn(0, tabs.size - 1)
                if (i != index) {
                    select(i)
                    haptic(Haptic.TAP)
                    onTab?.invoke(i)
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
