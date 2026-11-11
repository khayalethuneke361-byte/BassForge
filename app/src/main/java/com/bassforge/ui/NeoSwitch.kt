package com.bassforge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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
import com.bassforge.util.withAlpha

/** Toggle like the template's: inset track that fills with the accent, raised sliding thumb. */
class NeoSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var checked = false
        private set

    var onCheckedChange: ((Boolean) -> Unit)? = null

    private val pos = Spring(0f, 0f, 520f, 30f)
    private val press = Spring(0f, 0f, 560f, 26f)
    private val tick = Tick()
    private val track = RectF()
    private val thumb = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pad get() = dp(10f)

    init {
        isClickable = true
        isFocusable = true
    }

    /** Programmatic set: animates, never fires the listener. */
    fun setChecked(v: Boolean, animate: Boolean = true) {
        checked = v
        pos.target = if (v) 1f else 0f
        if (!animate) pos.snap(pos.target)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((dp(58f) + pad * 2).toInt(), widthMeasureSpec),
            resolveSize((dp(32f) + pad * 2).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val dt = tick.dt()
        val moving = pos.step(dt) or press.step(dt)
        val p = ThemeManager.p
        track.set(pad, pad, width - pad, height - pad)
        val rad = track.height() / 2f
        val t = pos.value.coerceIn(0f, 1f)

        NeoSurface.raised(canvas, track, rad, p, 1f, 0.55f)
        if (t > 0.01f) {
            NeoSurface.backlight(canvas, track, rad, p, p.accent, t * 0.7f)
            fillPaint.shader = LinearGradient(
                track.left, 0f, track.right, 0f,
                withAlpha(p.accent2, 0.95f * t), withAlpha(p.accent, 0.95f * t), Shader.TileMode.CLAMP
            )
            val inset = dp(2.5f)
            canvas.drawRoundRect(
                track.left + inset, track.top + inset, track.right - inset, track.bottom - inset,
                rad - inset, rad - inset, fillPaint
            )
            fillPaint.shader = null
        }

        val tr = rad - dp(3.5f) + dp(1.2f) * press.value
        val minX = track.left + rad
        val maxX = track.right - rad
        val cx = minX + (maxX - minX) * pos.value
        thumb.set(cx - tr, track.centerY() - tr, cx + tr, track.centerY() + tr)
        NeoSurface.raised(canvas, thumb, tr, p, 0f, 0.4f)

        if (moving) postInvalidateOnAnimation() else tick.reset()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                press.target = 1f
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                press.target = 0f
                checked = !checked
                pos.target = if (checked) 1f else 0f
                haptic(Haptic.TAP)
                invalidate()
                performClick()
                onCheckedChange?.invoke(checked)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                press.target = 0f
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
