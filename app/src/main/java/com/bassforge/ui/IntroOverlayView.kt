package com.bassforge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.bassforge.util.FrameLoop
import com.bassforge.util.NeoColors
import com.bassforge.util.clamp01
import com.bassforge.util.dp
import com.bassforge.util.easeInCubic
import com.bassforge.util.easeOutCubic
import com.bassforge.util.smoothstep
import com.bassforge.util.sp
import com.bassforge.util.withAlpha
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Title sequence. A ring draws itself, a shockwave flashes out, "BASSFORGE" slams in letter by
 * letter, the camera punches through the title and an iris opens onto the app. Tap to skip.
 */
class IntroOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Fired once when the iris starts opening: the moment to start the content entrance. */
    var onReveal: (() -> Unit)? = null
    /** Fired once at the end. The host should remove this view. */
    var onFinished: (() -> Unit)? = null

    private companion object {
        const val TOTAL = 3.0f
        const val IRIS_START = 0.66f
        const val WORD = "BASSFORGE"
        const val TAGLINE = "FORGE YOUR LOW END"
    }

    private var time = 0f
    private var revealed = false
    private var finished = false

    private val scrim = Paint().apply { color = NeoColors.VOID; style = Paint.Style.FILL }
    private val irisPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val glowText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL_AND_STROKE
        color = NeoColors.ACCENT_GLOW
    }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        color = NeoColors.TEXT_DIM
    }
    private val arcRect = RectF()
    private val widths = FloatArray(WORD.length)

    private val loop = FrameLoop { dt -> tick(dt) }

    private fun tick(dt: Float) {
        time += dt
        if (!revealed && time >= TOTAL * IRIS_START) {
            revealed = true
            onReveal?.invoke()
        }
        if (time >= TOTAL) {
            loop.stop()
            if (!finished) {
                finished = true
                onFinished?.invoke()
            }
        }
        invalidate()
    }

    init {
        isClickable = true       // swallow touches while the intro is showing
        isFocusable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loop.start()
    }

    override fun onDetachedFromWindow() {
        loop.stop()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && time < TOTAL * IRIS_START) {
            time = TOTAL * IRIS_START          // skip straight to the iris
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val cx = w / 2f
        val cy = h * 0.42f
        val p = clamp01(time / TOTAL)

        // ── scrim with iris hole ──
        val diag = hypot(w, h)
        val irisT = clamp01((p - IRIS_START) / (1f - IRIS_START))
        val irisR = easeOutCubic(irisT) * diag * 0.75f
        if (irisR <= 0.5f) {
            canvas.drawRect(0f, 0f, w, h, scrim)
        } else {
            irisPath.reset()
            irisPath.addRect(0f, 0f, w, h, Path.Direction.CW)
            irisPath.addCircle(cx, cy, irisR, Path.Direction.CW)
            canvas.drawPath(irisPath, scrim)
            // glowing iris edge
            ringPaint.color = withAlpha(NeoColors.CYAN, (1f - irisT) * 0.8f)
            ringPaint.strokeWidth = dp(3f)
            canvas.drawCircle(cx, cy, irisR, ringPaint)
        }

        // ── ring draws itself, then expands as a shockwave ──
        val ringBase = dp(84f)
        val draw = easeOutCubic(p / 0.30f)
        if (p < 0.72f) {
            val expand = clamp01((p - 0.30f) / 0.40f)
            val r = ringBase + easeOutCubic(expand) * dp(280f)
            val a = (1f - expand)
            arcRect.set(cx - r, cy - r, cx + r, cy + r)
            val sweep = 360f * draw
            val glows = floatArrayOf(dp(18f), dp(10f), dp(4f))
            val alphas = floatArrayOf(0.10f, 0.22f, 1f)
            for (i in 0 until 3) {
                ringPaint.strokeWidth = glows[i]
                ringPaint.color = withAlpha(if (i == 2) NeoColors.CYAN else NeoColors.ACCENT, alphas[i] * a)
                canvas.drawArc(arcRect, -90f, sweep, false, ringPaint)
            }
        }

        // ── flash as the ring closes ──
        val flash = smoothstep(0.24f, 0.32f, p) * (1f - smoothstep(0.32f, 0.50f, p))
        if (flash > 0.01f) {
            flashPaint.shader = RadialGradient(
                cx, cy, dp(260f),
                intArrayOf(withAlpha(Color.WHITE, 0.85f * flash), withAlpha(NeoColors.ACCENT, 0.45f * flash), Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, dp(260f), flashPaint)
        }

        // ── title ──
        val zoom = easeInCubic((p - 0.64f) / 0.20f)
        val titleAlphaOut = 1f - clamp01(zoom * 1.1f)
        if (titleAlphaOut > 0.01f) {
            val size = minOf(dp(46f), w * 0.115f)
            letterPaint.textSize = size
            glowText.textSize = size
            glowText.strokeWidth = dp(7f)
            val spacing = size * 0.16f
            var total = 0f
            for (i in WORD.indices) {
                widths[i] = letterPaint.measureText(WORD, i, i + 1)
                total += widths[i]
            }
            total += spacing * (WORD.length - 1)

            val sceneScale = 1f + zoom * 2.4f
            canvas.save()
            canvas.scale(sceneScale, sceneScale, cx, cy)

            var x = cx - total / 2f
            val baseline = cy + size * 0.35f
            for (i in WORD.indices) {
                val local = clamp01((p - 0.22f - i * 0.028f) / 0.16f)
                val a = easeOutCubic(local) * titleAlphaOut
                if (a > 0.01f) {
                    val s = 1.6f - 0.6f * easeOutCubic(local)
                    val yOff = (1f - easeOutCubic(local)) * dp(22f)
                    val gx = x + widths[i] / 2f
                    canvas.save()
                    canvas.scale(s, s, gx, baseline - size * 0.35f)
                    glowText.alpha = (a * 70f).toInt()
                    canvas.drawText(WORD, i, i + 1, gx, baseline + yOff, glowText)
                    letterPaint.color = Color.WHITE
                    letterPaint.alpha = (a * 255f).toInt()
                    canvas.drawText(WORD, i, i + 1, gx, baseline + yOff, letterPaint)
                    canvas.restore()
                }
                x += widths[i] + spacing
            }

            // underline sweep
            val line = easeOutCubic((p - 0.46f) / 0.14f)
            if (line > 0f) {
                ringPaint.strokeWidth = dp(2f)
                ringPaint.color = withAlpha(NeoColors.CYAN, 0.9f * titleAlphaOut)
                val half = total / 2f * line
                canvas.drawLine(cx - half, baseline + size * 0.42f, cx + half, baseline + size * 0.42f, ringPaint)
            }

            // tagline
            val ta = smoothstep(0.50f, 0.62f, p) * titleAlphaOut
            if (ta > 0.01f) {
                tagPaint.textSize = sp(12f)
                tagPaint.letterSpacing = 0.35f
                tagPaint.alpha = (ta * 255f).toInt()
                canvas.drawText(TAGLINE, cx, baseline + size * 1.05f, tagPaint)
            }
            canvas.restore()
        }
    }
}
