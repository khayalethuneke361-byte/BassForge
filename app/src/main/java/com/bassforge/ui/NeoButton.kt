package com.bassforge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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

/**
 * Button with a springy press: it sinks into the surface (shadows invert), shrinks a hair and
 * pops back with a little overshoot. Kinds: RAISED (neumorphic), PRIMARY (filled, backlit),
 * GLASS (frosted). [lit] lights it up with the theme's backlight.
 */
class NeoButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Kind { RAISED, PRIMARY, GLASS }

    var kind = Kind.RAISED
        set(v) { field = v; invalidate() }

    var text: String = ""
        set(v) { field = v; contentDescription = v; requestLayout(); invalidate() }

    var icon: Ico = Ico.NONE
        set(v) { field = v; requestLayout(); invalidate() }

    var lit: Boolean = false
        set(v) {
            if (field != v) { field = v; sel.target = if (v) 1f else 0f; invalidate() }
        }

    /** When true the control is circular/square (icon only) and keeps a fixed size. */
    var compact = false

    private val press = Spring(0f, 0f, 560f, 24f)
    private val sel = Spring(0f, 0f, 380f, 30f)
    private val tick = Tick()
    private var down = false

    private val rect = RectF()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val pad get() = dp(9f)

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        textPaint.textSize = sp(14f)
        var w = dp(34f)
        if (icon != Ico.NONE) w += dp(20f)
        if (icon != Ico.NONE && text.isNotEmpty()) w += dp(8f)
        if (text.isNotEmpty()) w += textPaint.measureText(text)
        w += pad * 2
        setMeasuredDimension(
            resolveSize(w.toInt(), widthMeasureSpec),
            resolveSize((dp(46f) + pad * 2).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val dt = tick.dt()
        val moving = press.step(dt) or sel.step(dt)
        val p = ThemeManager.p
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(pad, pad, w - pad, h - pad)
        val radius = rect.height() / 2f
        val s = sel.value
        val pr = press.value

        canvas.save()
        val sc = 1f - 0.035f * pr
        canvas.scale(sc, sc, w / 2f, h / 2f)

        val fg: Int
        when (kind) {
            Kind.RAISED -> {
                NeoSurface.backlight(canvas, rect, radius, p, p.accent, s)
                NeoSurface.raised(canvas, rect, radius, p, clamp01(maxOf(pr, s * 0.9f)))
                fg = blend(p.text, p.accent, s)
            }
            Kind.PRIMARY -> {
                NeoSurface.backlight(canvas, rect, radius, p, p.accent, 1f)
                NeoSurface.accentFill(canvas, rect, radius, p, clamp01(pr))
                fg = Color.WHITE
            }
            Kind.GLASS -> {
                NeoSurface.glass(canvas, rect, radius, p, p.accent, maxOf(s, clamp01(pr) * 0.5f))
                fg = blend(p.text, p.accent, s)
            }
        }

        // content
        textPaint.textSize = sp(14f)
        textPaint.color = fg
        val tw = if (text.isEmpty()) 0f else textPaint.measureText(text)
        val iw = if (icon == Ico.NONE) 0f else dp(20f)
        val gap = if (iw > 0f && tw > 0f) dp(8f) else 0f
        var x = (w - (iw + gap + tw)) / 2f
        if (icon != Ico.NONE) {
            Icons.draw(canvas, icon, x + iw / 2f, h / 2f, iw, fg, dp(2f))
            x += iw + gap
        }
        if (tw > 0f) {
            val fm = textPaint.fontMetrics
            canvas.drawText(text, x, h / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
        }
        canvas.restore()

        if (moving) postInvalidateOnAnimation() else tick.reset()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true
                press.target = 1f
                haptic(Haptic.TICK)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val inside = e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()
                if (inside != down) {
                    down = inside
                    press.target = if (inside) 1f else 0f
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val fire = down
                down = false
                press.target = 0f
                invalidate()
                if (fire) {
                    haptic(Haptic.TAP)
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                down = false
                press.target = 0f
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
