package com.bassforge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.bassforge.util.NeoColors
import com.bassforge.util.approach
import com.bassforge.util.blend
import com.bassforge.util.dp
import com.bassforge.util.sp
import com.bassforge.util.withAlpha

/**
 * Glass pill button / toggle chip. Lights up with a layered glow when [active], squashes on
 * press. Used for presets, movie mode and the power switch.
 */
class NeoButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Icon { NONE, POWER, MOVIE, MUSIC }

    var text: String = ""
        set(v) { field = v; contentDescription = v; requestLayout(); invalidate() }

    var icon: Icon = Icon.NONE
        set(v) { field = v; requestLayout(); invalidate() }

    var active: Boolean = false
        set(v) { field = v; invalidate() }

    var accent: Int = NeoColors.ACCENT
        set(v) { field = v; invalidate() }

    private var actAnim = 0f
    private var pressAnim = 0f
    private var pressed = false
    private var lastNanos = 0L

    private val rect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tri = Path()

    init {
        isClickable = true
        isFocusable = true
    }

    private val iconSize get() = dp(18f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        textPaint.textSize = sp(14f)
        var w = dp(36f)
        if (icon != Icon.NONE) w += iconSize
        if (icon != Icon.NONE && text.isNotEmpty()) w += dp(8f)
        if (text.isNotEmpty()) w += textPaint.measureText(text)
        setMeasuredDimension(
            resolveSize(w.toInt(), widthMeasureSpec),
            resolveSize(dp(46f).toInt(), heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        borderPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(withAlpha(Color.WHITE, 0.28f), withAlpha(Color.WHITE, 0.04f), withAlpha(Color.WHITE, 0.12f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.05f)
        lastNanos = now
        actAnim = approach(actAnim, if (active) 1f else 0f, 14f, dt)
        pressAnim = approach(pressAnim, if (pressed) 1f else 0f, 25f, dt)
        val moving = kotlin.math.abs(actAnim - (if (active) 1f else 0f)) > 0.01f ||
            kotlin.math.abs(pressAnim - (if (pressed) 1f else 0f)) > 0.01f

        val w = width.toFloat()
        val h = height.toFloat()
        val inset = dp(5f)
        rect.set(inset, inset, w - inset, h - inset)
        val rad = rect.height() / 2f

        canvas.save()
        val s = 1f - 0.05f * pressAnim
        canvas.scale(s, s, w / 2f, h / 2f)

        // glow when active
        if (actAnim > 0.01f) {
            val widths = floatArrayOf(dp(10f), dp(6f), dp(3f))
            val alphas = floatArrayOf(0.10f, 0.18f, 0.35f)
            for (i in widths.indices) {
                glowPaint.strokeWidth = widths[i]
                glowPaint.color = withAlpha(accent, alphas[i] * actAnim)
                canvas.drawRoundRect(rect, rad, rad, glowPaint)
            }
        }

        fillPaint.color = blend(NeoColors.PANEL, withAlpha(accent, 0.55f), actAnim * 0.9f)
        canvas.drawRoundRect(rect, rad, rad, fillPaint)
        borderPaint.strokeWidth = dp(1.2f)
        canvas.drawRoundRect(rect, rad, rad, borderPaint)

        // content
        val fg = blend(NeoColors.TEXT_DIM, Color.WHITE, actAnim)
        textPaint.textSize = sp(14f)
        textPaint.color = fg
        val tw = if (text.isEmpty()) 0f else textPaint.measureText(text)
        val iw = if (icon == Icon.NONE) 0f else iconSize
        val gap = if (iw > 0f && tw > 0f) dp(8f) else 0f
        var x = (w - (iw + gap + tw)) / 2f
        if (icon != Icon.NONE) {
            drawIcon(canvas, x + iw / 2f, h / 2f, iw, fg)
            x += iw + gap
        }
        if (tw > 0f) {
            val fm = textPaint.fontMetrics
            canvas.drawText(text, x, h / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
        }
        canvas.restore()

        if (moving) postInvalidateOnAnimation()
    }

    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        iconPaint.color = color
        iconPaint.strokeWidth = dp(2f)
        iconFill.color = color
        val r = size * 0.42f
        when (icon) {
            Icon.POWER -> {
                rect.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(rect, -60f, 300f, false, iconPaint)
                canvas.drawLine(cx, cy - r * 1.15f, cx, cy - r * 0.1f, iconPaint)
            }
            Icon.MOVIE -> {
                rect.set(cx - size * 0.48f, cy - size * 0.38f, cx + size * 0.48f, cy + size * 0.38f)
                canvas.drawRoundRect(rect, dp(3f), dp(3f), iconPaint)
                tri.reset()
                tri.moveTo(cx - size * 0.10f, cy - size * 0.18f)
                tri.lineTo(cx + size * 0.20f, cy)
                tri.lineTo(cx - size * 0.10f, cy + size * 0.18f)
                tri.close()
                canvas.drawPath(tri, iconFill)
            }
            Icon.MUSIC -> {
                canvas.drawCircle(cx - size * 0.18f, cy + size * 0.28f, size * 0.20f, iconFill)
                canvas.drawLine(cx + size * 0.02f, cy + size * 0.28f, cx + size * 0.02f, cy - size * 0.42f, iconPaint)
                canvas.drawLine(cx + size * 0.02f, cy - size * 0.42f, cx + size * 0.36f, cy - size * 0.22f, iconPaint)
            }
            Icon.NONE -> {}
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val inside = e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()
                if (inside != pressed) { pressed = inside; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasPressed = pressed
                pressed = false
                invalidate()
                if (wasPressed) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
