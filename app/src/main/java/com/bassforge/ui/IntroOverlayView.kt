package com.bassforge.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.bassforge.R
import com.bassforge.util.NeoSurface
import com.bassforge.util.ThemeManager
import com.bassforge.util.clamp01
import com.bassforge.util.dp
import com.bassforge.util.easeInOutCubic
import com.bassforge.util.easeOutCubic
import com.bassforge.util.lerp
import com.bassforge.util.sp
import com.bassforge.util.withAlpha
import kotlin.math.pow
import kotlin.math.sin

/** The app logo, loaded once, drawn with the same rounded corners everywhere. */
object Logo {
    const val CORNER = 0.225f           // fraction of the side, matches the launcher icon feel
    private var bmp: Bitmap? = null

    fun bitmap(ctx: Context): Bitmap {
        bmp?.let { return it }
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val b = BitmapFactory.decodeResource(ctx.resources, R.drawable.logo, opts)
        bmp = b
        return b
    }

    private val path = Path()
    private val src = Rect()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun draw(c: Canvas, ctx: Context, dst: RectF, alpha: Float = 1f) {
        val b = bitmap(ctx)
        src.set(0, 0, b.width, b.height)
        val r = dst.width() * CORNER
        path.reset()
        path.addRoundRect(dst, r, r, Path.Direction.CW)
        c.save()
        c.clipPath(path)
        paint.alpha = (255 * clamp01(alpha)).toInt()
        c.drawBitmap(b, src, dst, paint)
        c.restore()
    }
}

/** Small header logo. */
class LogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {
    private val box = RectF()
    override fun onDraw(canvas: Canvas) {
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        Logo.draw(canvas, context, box)
    }
}

/**
 * Launch animation: the logo scales in on the solid background, a ring pulses out, the wordmark
 * settles in, then the logo glides into its slot in the header while the overlay dissolves and
 * the real UI is revealed underneath.
 */
class IntroOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var onReveal: (() -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    /** Where the logo should land (in this view's coordinates). Set before [start]. */
    var targetRect: RectF? = null

    private var startNanos = 0L
    private var revealed = false
    private var finished = false

    private val box = RectF()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val bgPaint = Paint()

    private companion object {
        const val T_IN = 0.75f
        const val T_HOLD = 1.55f
        const val T_OUT = 2.25f
    }

    init {
        isClickable = true      // swallow touches while the intro plays
    }

    fun start() {
        startNanos = System.nanoTime()
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (startNanos == 0L) start()
    }

    private fun overshoot(t: Float): Float {
        val x = clamp01(t) - 1f
        val s = 1.9f
        return x * x * ((s + 1f) * x + s) + 1f
    }

    override fun onDraw(canvas: Canvas) {
        val t = (System.nanoTime() - startNanos) / 1_000_000_000f
        val p = ThemeManager.p
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.43f
        val base = minOf(w, h) * 0.34f

        val out = easeInOutCubic((t - T_HOLD) / (T_OUT - T_HOLD))

        if (!revealed && t >= T_HOLD) { revealed = true; onReveal?.invoke() }

        // solid background dissolves during the hand-off
        bgPaint.color = withAlpha(p.bg, 1f - out)
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // logo
        val sIn = overshoot(t / T_IN)
        val aIn = clamp01(t / 0.35f)
        val size0 = base * (0.55f + 0.45f * sIn)
        box.set(cx - size0 / 2f, cy - size0 / 2f, cx + size0 / 2f, cy + size0 / 2f)
        val tr = targetRect
        if (tr != null && out > 0f) {
            box.left = lerp(box.left, tr.left, out)
            box.top = lerp(box.top, tr.top, out)
            box.right = lerp(box.right, tr.right, out)
            box.bottom = lerp(box.bottom, tr.bottom, out)
        }

        // ring pulse
        val rt = clamp01((t - 0.35f) / 0.9f)
        if (rt > 0f && rt < 1f) {
            ring.color = withAlpha(p.accent, (1f - rt) * 0.55f * (1f - out))
            ring.strokeWidth = dp(3f) * (1f - rt) + dp(0.8f)
            val rr = base * (0.55f + 0.95f * easeOutCubic(rt))
            canvas.drawCircle(cx, cy, rr, ring)
        }

        // raised tile behind the logo (soft neumorphic shadow), fades with the overlay
        if (aIn > 0.02f) {
            if (out < 0.85f) {
                canvas.save()
                canvas.saveLayerAlpha(null, (255 * aIn * (1f - out)).toInt())
                NeoSurface.raised(canvas, box, box.width() * Logo.CORNER, p, 0f, 1.1f)
                canvas.restore()
                canvas.restore()
            }
            Logo.draw(canvas, context, box, aIn)
        }

        // wordmark
        val wt = clamp01((t - 0.7f) / 0.6f)
        if (wt > 0f && out < 1f) {
            word.textSize = sp(26f)
            word.letterSpacing = lerp(0.55f, 0.28f, easeOutCubic(wt))
            word.color = withAlpha(p.text, wt * (1f - out))
            canvas.drawText("BASSFORGE", cx, cy + base * 0.5f + dp(58f) + (1f - wt) * dp(10f), word)
            word.textSize = sp(11f)
            word.letterSpacing = 0.3f
            word.color = withAlpha(p.accent, wt * (1f - out) * 0.9f)
            canvas.drawText("FEEL THE LOW END", cx, cy + base * 0.5f + dp(84f), word)
        }

        if (t >= T_OUT) {
            if (!finished) { finished = true; onFinished?.invoke() }
        } else {
            postInvalidateOnAnimation()
        }
    }
}
