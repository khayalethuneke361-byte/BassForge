package com.bassforge.util

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Soft-shadow masks. Rendered once (software canvas, so BlurMaskFilter is guaranteed to work)
 * at half resolution as ALPHA_8 bitmaps, then tinted at draw time. Because they carry no colour
 * they survive theme changes and are shared between views of the same size.
 */
object NeoMasks {
    private const val S = 0.5f

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun margin(blur: Float): Float = ceil(blur * 2.2f)

    /** Blurred filled rounded rect (drop shadows, glows). */
    fun soft(w: Int, h: Int, r: Float, blur: Float): Bitmap {
        val key = "s$w,$h,${r.toInt()},${blur.toInt()}"
        cache.get(key)?.let { return it }
        val m = margin(blur)
        val bmp = Bitmap.createBitmap(
            max(1, ((w + 2 * m) * S).toInt()), max(1, ((h + 2 * m) * S).toInt()), Bitmap.Config.ALPHA_8
        )
        val c = Canvas(bmp)
        c.scale(S, S)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawRoundRect(RectF(m, m, m + w, m + h), r, r, p)
        cache.put(key, bmp)
        return bmp
    }

    /** Blurred complement of a rounded rect (inner shadows when clipped to the shape). */
    fun inner(w: Int, h: Int, r: Float, blur: Float): Bitmap {
        val key = "i$w,$h,${r.toInt()},${blur.toInt()}"
        cache.get(key)?.let { return it }
        val m = margin(blur)
        val bmp = Bitmap.createBitmap(
            max(1, ((w + 2 * m) * S).toInt()), max(1, ((h + 2 * m) * S).toInt()), Bitmap.Config.ALPHA_8
        )
        val c = Canvas(bmp)
        c.scale(S, S)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            val big = m + blur * 4f
            addRect(-big, -big, w + 2 * m + big, h + 2 * m + big, Path.Direction.CW)
            addRoundRect(RectF(m, m, m + w, m + h), r, r, Path.Direction.CW)
        }
        c.drawPath(path, p)
        cache.put(key, bmp)
        return bmp
    }
}

/** Neumorphic + glass drawing helpers. All colours come from the live [Palette]. */
object NeoSurface {

    val density: Float get() = Resources.getSystem().displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val tmp = RectF()
    private val clip = Path()

    private fun maskSoft(c: Canvas, rect: RectF, radius: Float, blur: Float, dx: Float, dy: Float, color: Int) {
        val w = rect.width().roundToInt()
        val h = rect.height().roundToInt()
        if (w <= 0 || h <= 0 || Color.alpha(color) == 0) return
        val m = NeoMasks.margin(blur)
        val mask = NeoMasks.soft(w, h, radius, blur)
        maskPaint.color = color
        dst.set(rect.left - m + dx, rect.top - m + dy, rect.left + w + m + dx, rect.top + h + m + dy)
        c.drawBitmap(mask, null, dst, maskPaint)
    }

    private fun maskInner(c: Canvas, rect: RectF, radius: Float, blur: Float, dx: Float, dy: Float, color: Int) {
        val w = rect.width().roundToInt()
        val h = rect.height().roundToInt()
        if (w <= 0 || h <= 0 || Color.alpha(color) == 0) return
        val m = NeoMasks.margin(blur)
        val mask = NeoMasks.inner(w, h, radius, blur)
        maskPaint.color = color
        dst.set(rect.left - m + dx, rect.top - m + dy, rect.left + w + m + dx, rect.top + h + m + dy)
        c.drawBitmap(mask, null, dst, maskPaint)
    }

    /**
     * Raised <-> pressed-in surface. [press] 0 = fully raised, 1 = fully inset.
     * [scale] shrinks shadows for small elements.
     */
    fun raised(
        c: Canvas, rect: RectF, radius: Float, p: Palette, press: Float,
        scale: Float = 1f, fillTop: Int = p.surfaceTop, fillBottom: Int = p.surfaceBottom
    ) {
        val d = density
        val e = 5.5f * d * scale
        val blur = 10f * d * scale
        val a = clamp01(press)
        val dk = p.darkAmt

        if (a < 0.995f) {
            val k = 1f - a
            maskSoft(c, rect, radius, blur, e, e, withAlpha(p.lo, lerp(0.55f, 0.95f, dk) * k))
            maskSoft(c, rect, radius, blur, -e, -e, withAlpha(p.hi, lerp(0.95f, 0.55f, dk) * k))
        }

        paint.style = Paint.Style.FILL
        val top = blend(fillTop, fillBottom, a)
        val bot = blend(fillBottom, p.track, a * 0.55f)
        paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, top, bot, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null

        if (a > 0.005f) {
            val ie = e * 0.75f
            val ib = blur * 0.8f
            c.save()
            clip.reset()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
            c.clipPath(clip)
            maskInner(c, rect, radius, ib, ie, ie, withAlpha(p.lo, lerp(0.60f, 0.95f, dk) * a))
            maskInner(c, rect, radius, ib, -ie, -ie, withAlpha(p.hi, lerp(0.95f, 0.50f, dk) * a))
            c.restore()
        }
        if (a < 0.6f) {
            // fine top-left edge light: the "premium" edge catch
            val k = (1f - a / 0.6f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.1f * d
            paint.shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(withAlpha(Color.WHITE, lerp(0.85f, 0.16f, dk) * k), Color.TRANSPARENT, withAlpha(Color.WHITE, lerp(0.20f, 0.05f, dk) * k)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            tmp.set(rect.left + 0.5f * d, rect.top + 0.5f * d, rect.right - 0.5f * d, rect.bottom - 0.5f * d)
            c.drawRoundRect(tmp, radius, radius, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
        }
    }

    /** Filled accent surface (primary buttons, filled tracks). */
    fun accentFill(c: Canvas, rect: RectF, radius: Float, p: Palette, press: Float = 0f) {
        val d = density
        val a = clamp01(press)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            blend(p.accent2, p.accent, 0.15f + 0.3f * a), blend(p.accent, Color.BLACK, 0.12f + 0.1f * a), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
        // top sheen
        paint.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(withAlpha(Color.WHITE, 0.38f * (1f - a)), withAlpha(Color.WHITE, 0.0f), withAlpha(Color.BLACK, 0.10f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * d
        paint.color = withAlpha(Color.WHITE, 0.30f)
        tmp.set(rect.left + 0.5f * d, rect.top + 0.5f * d, rect.right - 0.5f * d, rect.bottom - 0.5f * d)
        c.drawRoundRect(tmp, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    /**
     * Coloured backlight behind an element.
     * Light mode: soft coloured under-glow cast below it.
     * Dark mode: neon rim light + halo around it.
     */
    fun backlight(c: Canvas, rect: RectF, radius: Float, p: Palette, color: Int, amt: Float) {
        if (amt <= 0.005f) return
        val d = density
        val dk = p.darkAmt
        // light mode under-glow
        if (dk < 0.999f) {
            maskSoft(c, rect, radius, 12f * d, 0f, 7f * d, withAlpha(color, 0.50f * amt * (1f - dk)))
        }
        // dark mode halo + rim
        if (dk > 0.001f) {
            maskSoft(c, rect, radius, 11f * d, 0f, 0f, withAlpha(color, 0.45f * amt * dk))
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f * d
            paint.color = withAlpha(color, 0.95f * amt * dk)
            tmp.set(rect.left + 0.8f * d, rect.top + 0.8f * d, rect.right - 0.8f * d, rect.bottom - 0.8f * d)
            c.drawRoundRect(tmp, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
    }

    /** iOS-style frosted glass. Translucent so content scrolling behind it shows through. */
    fun glass(c: Canvas, rect: RectF, radius: Float, p: Palette, tint: Int = 0, tintAmt: Float = 0f) {
        val d = density
        val dk = p.darkAmt
        maskSoft(c, rect, radius, 16f * d, 0f, 8f * d, withAlpha(p.lo, lerp(0.40f, 0.85f, dk)))

        paint.style = Paint.Style.FILL
        paint.color = withAlpha(p.glassFill, lerp(0.62f, 0.58f, dk))
        c.drawRoundRect(rect, radius, radius, paint)
        if (tintAmt > 0.01f && tint != 0) {
            paint.color = withAlpha(tint, 0.20f * tintAmt)
            c.drawRoundRect(rect, radius, radius, paint)
        }
        // vertical sheen
        paint.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(withAlpha(Color.WHITE, lerp(0.55f, 0.16f, dk)), withAlpha(Color.WHITE, 0f), withAlpha(Color.WHITE, lerp(0.14f, 0.04f, dk))),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null

        // specular edge
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * d
        paint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(withAlpha(Color.WHITE, lerp(0.95f, 0.45f, dk)), withAlpha(Color.WHITE, lerp(0.20f, 0.06f, dk)), withAlpha(Color.WHITE, lerp(0.65f, 0.28f, dk))),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        tmp.set(rect.left + 0.6f * d, rect.top + 0.6f * d, rect.right - 0.6f * d, rect.bottom - 0.6f * d)
        c.drawRoundRect(tmp, radius, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
    }
}
