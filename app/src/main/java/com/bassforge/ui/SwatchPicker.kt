package com.bassforge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
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

/** Row of colour swatches (accent colours, spinner finishes). [metallic] gives them a metal sheen. */
class SwatchPicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var colors: IntArray = IntArray(0)
        set(v) { field = v; springs = Array(v.size) { Spring(if (it == index) 1f else 0f, if (it == index) 1f else 0f, 420f, 24f) }; invalidate() }

    var names: List<String> = emptyList()
    var metallic = false
    var index = 0
        private set
    var onPick: ((Int) -> Unit)? = null

    private var springs: Array<Spring> = emptyArray()
    private val tick = Tick()
    private val cell = RectF()
    private val dot = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    init { isClickable = true }

    fun select(i: Int) {
        index = i
        for (k in springs.indices) springs[k].target = if (k == i) 1f else 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(dp(96f).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (colors.isEmpty()) return
        val dt = tick.dt()
        var moving = false
        for (s in springs) moving = s.step(dt) or moving
        val p = ThemeManager.p
        val n = colors.size
        val slot = (width - dp(16f)) / n
        val size = dp(46f)

        for (i in 0 until n) {
            val sel = springs[i].value.coerceIn(0f, 1f)
            val cx = dp(8f) + slot * (i + 0.5f)
            val cy = dp(40f)
            val r = size / 2f
            cell.set(cx - r, cy - r, cx + r, cy + r)
            NeoSurface.backlight(canvas, cell, r, p, colors[i], sel * 0.8f)
            NeoSurface.raised(canvas, cell, r, p, sel * 0.5f, 0.5f)

            val ir = r * (0.62f + 0.06f * sel)
            dot.set(cx - ir, cy - ir, cx + ir, cy + ir)
            if (metallic) {
                paint.shader = RadialGradient(
                    cx - ir * 0.35f, cy - ir * 0.4f, ir * 1.5f,
                    intArrayOf(blend(colors[i], Color.WHITE, 0.65f), colors[i], blend(colors[i], Color.BLACK, 0.55f)),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
                )
            } else {
                paint.shader = RadialGradient(
                    cx - ir * 0.3f, cy - ir * 0.35f, ir * 1.5f,
                    intArrayOf(blend(colors[i], Color.WHITE, 0.35f), colors[i], blend(colors[i], Color.BLACK, 0.25f)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
                )
            }
            paint.style = Paint.Style.FILL
            canvas.drawOval(dot, paint)
            paint.shader = null

            if (sel > 0.05f) {
                Icons.draw(canvas, Ico.CHECK, cx, cy, ir * 1.1f, Color.argb((255 * sel).toInt(), 255, 255, 255), dp(2.4f))
            }

            if (i < names.size) {
                labelPaint.textSize = sp(11f)
                labelPaint.color = blend(p.textDim, p.text, sel)
                canvas.drawText(names[i], cx, dp(40f) + size / 2f + dp(18f), labelPaint)
            }
        }
        if (moving) postInvalidateOnAnimation() else tick.reset()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (colors.isEmpty()) return true
                val slot = (width - dp(16f)) / colors.size
                val i = ((e.x - dp(8f)) / slot).toInt().coerceIn(0, colors.size - 1)
                if (i != index) {
                    select(i)
                    haptic(Haptic.TAP)
                    onPick?.invoke(i)
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
