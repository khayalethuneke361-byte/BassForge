package com.bassforge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.RequiresApi
import com.bassforge.R
import com.bassforge.util.Haptic
import com.bassforge.util.NeoSurface
import com.bassforge.util.Palette
import com.bassforge.util.Spring
import com.bassforge.util.ThemeManager
import com.bassforge.util.Tick
import com.bassforge.util.blend
import com.bassforge.util.clamp01
import com.bassforge.util.dp
import com.bassforge.util.haptic
import com.bassforge.util.lerp
import com.bassforge.util.smoothstep
import com.bassforge.util.sp
import com.bassforge.util.withAlpha
import com.bassforge.util.wrap180
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * The fidget spinner IS the bass knob.
 *
 * Rotate it like any volume knob: 0..300 degrees maps to 0..100% bass. Only once the bass is
 * at maximum does the spinner come free: keep pushing and it spins all the way round, and a
 * flick keeps it going on its own momentum. Because the spinner has 3-fold symmetry the
 * detent positions (every 120 degrees) look identical, so it always settles cleanly.
 */
class FidgetKnobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        private const val SWEEP = 300f          // degrees of knob travel
        private const val START = 120f          // canvas angle where the gauge begins
    }

    var onBassChanged: ((Float) -> Unit)? = null

    /** Programmatic set (from preferences / presets). Never fires [onBassChanged]. */
    var bassLevel: Float
        get() = (theta.target / SWEEP).coerceIn(0f, 1f)
        set(v) {
            if (dragging) return
            theta.target = v.coerceIn(0f, 1f) * SWEEP
            invalidate()
        }

    var isActive = true
        set(v) { field = v; invalidate() }

    var isMusicPlaying = false

    /** 0..1 bass energy from the spectrum tap; brightens the gauge, never moves the knob. */
    var beat = 0f
        set(v) { field = v.coerceIn(0f, 1f); if (isActive) postInvalidateOnAnimation() }

    // physics
    private val theta = Spring(0f, 0f, 170f, 24f)      // knob angle 0..300
    private var extra = 0f                               // free spin angle (deg)
    private var extraVel = 0f                            // deg / s
    private var dragging = false
    private var lastTouchAngle = 0f
    private var lastTouchTime = 0L
    private var lastTickBucket = 0
    private var atMax = false
    private var lastDetent = 0
    private var lightT = 1f
    private var beatShown = 0f

    private val tick = Tick()
    private val rect = RectF()
    private val arc = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.14f
    }

    private var agsl: Any? = null
    private var agslFailed = false
    private val fallbackPath = Path()
    private val fallbackTmp = Path()

    private val darkListener: (Boolean) -> Unit = { dark ->
        if (dark) { lightT = 0f; postInvalidateOnAnimation() }
    }

    init {
        isClickable = true
        contentDescription = "Bass knob. Rotate it to change the bass."
        if (Build.VERSION.SDK_INT >= 33) {
            try { agsl = Agsl(context) } catch (t: Throwable) { agslFailed = true }
        }
        theta.snap(0f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ThemeManager.onDarkChanged.add(darkListener)
        if (ThemeManager.p.dark) lightT = 0f
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        ThemeManager.onDarkChanged.remove(darkListener)
        super.onDetachedFromWindow()
    }

    /** Gives the spinner an angular kick in deg/s (used by the intro). */
    fun kick(degPerSec: Float) {
        extraVel = degPerSec
        postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.96f + dp(46f)).toInt())
    }

    private fun levelName(l: Float): String = when {
        l < 0.02f -> "OFF"
        l < 0.18f -> "WARM"
        l < 0.40f -> "PUNCHY"
        l < 0.65f -> "BOOMY"
        l < 0.98f -> "THUNDER"
        else -> "MAX"
    }

    // ───────────────────────── drawing ─────────────────────────

    override fun onDraw(canvas: Canvas) {
        val dt = tick.dt()
        var moving = theta.step(dt)

        // free spin physics
        if (!dragging && extraVel != 0f) {
            val s = sign(extraVel)
            var v = abs(extraVel)
            v -= v * 0.8f * dt + 26f * dt
            if (v < 4f) { v = 0f }
            extraVel = s * v
            extra += extraVel * dt
            val detent = Math.floorDiv(extra.roundToInt(), 120)
            if (detent != lastDetent) {
                lastDetent = detent
                if (v > 60f) haptic(Haptic.TICK)
            }
            if (extraVel == 0f) {
                // settle on a 120 degree detent (looks identical thanks to the 3-fold symmetry)
                extra = (extra / 120f).roundToInt() * 120f
                extra %= 360f
            }
            moving = true
        }
        if (lightT < 1f) { lightT = (lightT + dt / 0.95f).coerceAtMost(1f); moving = true }
        val bTarget = if (isActive) beat else 0f
        if (abs(beatShown - bTarget) > 0.004f) {
            beatShown += (bTarget - beatShown) * (1f - exp(-dt * if (bTarget > beatShown) 28f else 7f))
            moving = true
        }

        val p = ThemeManager.p
        val w = width.toFloat()
        val cx = w / 2f
        val cy = w * 0.5f
        val level = (theta.value / SWEEP).coerceIn(0f, 1f)

        drawPlatter(canvas, p, cx, cy, w)
        drawGauge(canvas, p, cx, cy, w, level)
        drawSpinner(canvas, p, cx, cy, w, level)
        drawReadout(canvas, p, cx, cy, w, level)

        if (moving || dragging) postInvalidateOnAnimation() else tick.reset()
    }

    private fun drawPlatter(c: Canvas, p: Palette, cx: Float, cy: Float, w: Float) {
        // raised outer disc holding the gauge, sunken well for the spinner
        val ro = w * 0.485f
        rect.set(cx - ro, cy - ro, cx + ro, cy + ro)
        NeoSurface.raised(c, rect, ro, p, 0f, 1.0f)
        val ri = w * 0.385f
        rect.set(cx - ri, cy - ri, cx + ri, cy + ri)
        NeoSurface.raised(c, rect, ri, p, 1f, 0.9f)
    }

    private fun drawGauge(c: Canvas, p: Palette, cx: Float, cy: Float, w: Float, level: Float) {
        val r = w * 0.435f
        val sw = dp(9f)
        arc.set(cx - r, cy - r, cx + r, cy + r)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = sw

        // track
        paint.shader = null
        paint.color = withAlpha(p.lo, lerp(0.35f, 0.55f, p.darkAmt))
        c.drawArc(arc, START, SWEEP, false, paint)
        paint.color = withAlpha(p.track, 0.9f)
        paint.strokeWidth = sw - dp(2f)
        c.drawArc(arc, START, SWEEP, false, paint)

        // ticks every 10%
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(2f)
        for (i in 0..10) {
            val a = Math.toRadians((START + SWEEP * i / 10f).toDouble())
            val r1 = r - dp(13f)
            val r2 = r - dp(if (i % 5 == 0) 19f else 16f)
            val lit = i / 10f <= level + 0.001f && level > 0.001f
            paint.color = if (lit) withAlpha(p.accent, 0.9f) else withAlpha(p.textDim, 0.45f)
            c.drawLine(
                cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(),
                cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), paint
            )
        }

        // value arc
        val sweep = SWEEP * level
        if (sweep > 0.5f) {
            val dim = if (isActive) 1f else 0.35f
            val glow = (0.35f + 0.65f * beatShown) * dim
            // fake glow: wider translucent strokes
            if (p.darkAmt > 0.01f || beatShown > 0.02f) {
                for (k in 3 downTo 1) {
                    paint.shader = null
                    paint.strokeWidth = sw + dp(5f) * k
                    paint.color = withAlpha(p.accent, 0.07f * glow * (0.4f + p.darkAmt))
                    c.drawArc(arc, START, sweep, false, paint)
                }
            }
            c.save()
            c.rotate(START, cx, cy)
            paint.shader = SweepGradient(
                cx, cy,
                intArrayOf(
                    withAlpha(p.accent2, dim), withAlpha(p.accent, dim),
                    withAlpha(p.accent, dim)
                ),
                floatArrayOf(0f, (SWEEP / 360f) * level.coerceAtLeast(0.05f), 1f)
            )
            paint.strokeWidth = sw - dp(2f)
            c.drawArc(arc, 0f, sweep, false, paint)
            c.restore()
            paint.shader = null

            // end cap bead
            val ea = Math.toRadians((START + sweep).toDouble())
            val bx = cx + r * cos(ea).toFloat()
            val by = cy + r * sin(ea).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            c.drawCircle(bx, by, dp(3.6f), paint)
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawReadout(c: Canvas, p: Palette, cx: Float, cy: Float, w: Float, level: Float) {
        val y = cy + w * 0.485f + dp(6f)
        textPaint.textSize = sp(30f)
        textPaint.color = if (isActive) p.text else p.textDim
        // sits in the gap at the bottom of the gauge, overlapping the platter slightly
        c.drawText("${(level * 100f).roundToInt()}%", cx, y + dp(18f), textPaint)
        subPaint.textSize = sp(11f)
        subPaint.color = if (isActive) p.accent else p.textDim
        c.drawText(levelName(level), cx, y + dp(38f), subPaint)
    }

    private fun drawSpinner(c: Canvas, p: Palette, cx: Float, cy: Float, w: Float, level: Float) {
        val angleDeg = theta.value + extra
        if (Build.VERSION.SDK_INT >= 33 && !agslFailed && agsl != null) {
            try {
                (agsl as Agsl).draw(c, p, cx, cy, w, angleDeg, beatShown, lightT)
                return
            } catch (t: Throwable) {
                agslFailed = true
            }
        }
        drawFallback(c, p, cx, cy, w, angleDeg)
    }

    /** Canvas-only spinner for Android 8 to 12: metal sweep gradient, no ray-traced reflections. */
    private fun drawFallback(c: Canvas, p: Palette, cx: Float, cy: Float, w: Float, angleDeg: Float) {
        val R = w * 0.345f
        val f = ThemeManager.finish
        val metal = Color.rgb((f.r * 255).toInt(), (f.g * 255).toInt(), (f.b * 255).toInt())
        val lobeR = R * 0.40f
        val armR = R * 0.60f

        fallbackPath.reset()
        fallbackPath.addCircle(0f, 0f, R * 0.30f, Path.Direction.CW)
        for (i in 0 until 3) {
            val a = Math.toRadians((-90 + i * 120).toDouble())
            val lx = (armR * cos(a)).toFloat()
            val ly = (armR * sin(a)).toFloat()
            fallbackTmp.reset()
            fallbackTmp.addCircle(lx, ly, lobeR, Path.Direction.CW)
            fallbackPath.op(fallbackTmp, Path.Op.UNION)
            // arm
            fallbackTmp.reset()
            val nx = (-sin(a)).toFloat() * R * 0.17f
            val ny = cos(a).toFloat() * R * 0.17f
            fallbackTmp.moveTo(nx, ny); fallbackTmp.lineTo(lx + nx, ly + ny)
            fallbackTmp.lineTo(lx - nx, ly - ny); fallbackTmp.lineTo(-nx, -ny); fallbackTmp.close()
            fallbackPath.op(fallbackTmp, Path.Op.UNION)
        }

        c.save()
        c.translate(cx, cy)
        // shadow
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(Color.BLACK, 0.16f + 0.14f * p.darkAmt)
        c.save(); c.translate(0f, dp(5f)); c.rotate(angleDeg); c.drawPath(fallbackPath, paint); c.restore()

        c.rotate(angleDeg)
        paint.shader = SweepGradient(
            0f, 0f,
            intArrayOf(
                blend(metal, Color.WHITE, 0.55f), blend(metal, Color.BLACK, 0.35f),
                blend(metal, Color.WHITE, 0.45f), blend(metal, Color.BLACK, 0.40f),
                blend(metal, Color.WHITE, 0.55f)
            ),
            null
        )
        c.drawPath(fallbackPath, paint)
        paint.shader = null

        // bearing holes
        for (i in 0 until 3) {
            val a = Math.toRadians((-90 + i * 120).toDouble())
            val lx = (armR * cos(a)).toFloat()
            val ly = (armR * sin(a)).toFloat()
            paint.color = withAlpha(p.bg, 1f)
            c.drawCircle(lx, ly, lobeR * 0.55f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = withAlpha(Color.BLACK, 0.25f)
            c.drawCircle(lx, ly, lobeR * 0.55f, paint)
            paint.style = Paint.Style.FILL
        }
        // hub cap
        paint.color = p.accent
        c.drawCircle(0f, 0f, R * 0.17f, paint)
        paint.color = withAlpha(Color.WHITE, 0.45f)
        c.drawCircle(-R * 0.05f, -R * 0.06f, R * 0.06f, paint)
        c.restore()
    }

    // ───────────────────────── touch ─────────────────────────

    private fun touchAngle(x: Float, y: Float): Float {
        val cx = width / 2f
        val cy = width * 0.5f
        return Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cx = width / 2f
                val cy = width * 0.5f
                val d = hypot(e.x - cx, e.y - cy)
                if (d > width * 0.5f || d < width * 0.03f) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                extraVel = 0f                       // grab the spinner to stop it
                theta.snap(theta.value)
                lastTouchAngle = touchAngle(e.x, e.y)
                lastTouchTime = e.eventTime
                lastTickBucket = (theta.value / SWEEP * 20f).roundToInt()
                atMax = theta.value >= SWEEP - 0.01f
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                val a = touchAngle(e.x, e.y)
                val dA = wrap180(a - lastTouchAngle)
                lastTouchAngle = a
                val dtMs = (e.eventTime - lastTouchTime).coerceAtLeast(1L)
                lastTouchTime = e.eventTime
                val inst = dA / (dtMs / 1000f)

                var th = theta.value + dA
                var spare = 0f
                if (th > SWEEP) { spare = th - SWEEP; th = SWEEP }
                if (th < 0f) th = 0f
                theta.snap(th)

                if (th >= SWEEP - 0.01f && dA > 0f) {
                    // knob is at maximum: now it may spin freely
                    extra += spare.coerceAtLeast(if (atMax) dA else 0f)
                    extraVel = extraVel * 0.6f + inst * 0.4f
                } else {
                    extraVel *= 0.5f
                }

                val nowMax = th >= SWEEP - 0.01f
                if (nowMax && !atMax) haptic(Haptic.CONFIRM)
                atMax = nowMax

                val bucket = (th / SWEEP * 20f).roundToInt()
                if (bucket != lastTickBucket) { lastTickBucket = bucket; if (!nowMax) haptic(Haptic.TICK) }
                onBassChanged?.invoke(th / SWEEP)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                // only a flick at maximum keeps spinning
                if (!(atMax && abs(extraVel) > 90f && extraVel > 0f)) extraVel = 0f
                extraVel = extraVel.coerceIn(-2600f, 2600f)
                theta.target = theta.value
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    // ───────────────────────── AGSL renderer (API 33+) ─────────────────────────

    @RequiresApi(33)
    private class Agsl(ctx: Context) {
        private val shader: RuntimeShader
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            val src = ctx.resources.openRawResource(R.raw.spinner_shader).bufferedReader().use { it.readText() }
            shader = RuntimeShader(src)
            paint.shader = shader
        }

        private fun rgb(c: Int, out: FloatArray) {
            out[0] = Color.red(c) / 255f; out[1] = Color.green(c) / 255f; out[2] = Color.blue(c) / 255f
        }

        private fun flick(t: Float, delay: Float): Float {
            val x = clamp01((t - delay) / (1f - delay))
            if (x <= 0f) return 0f
            if (x >= 1f) return 1f
            val stutter = if (sin(x * 46f) > -0.2f) 1f else 0.25f
            return smoothstep(0f, 0.7f, x) * stutter * 0.85f + x * x * 0.15f
        }

        fun draw(c: Canvas, p: Palette, cx: Float, cy: Float, w: Float, angleDeg: Float, beat: Float, lightT: Float) {
            val dk = p.darkAmt
            val f = ThemeManager.finish
            val col = FloatArray(3)
            shader.setFloatUniform("uSize", w, w)
            shader.setFloatUniform("uCenter", cx, cy)
            shader.setFloatUniform("uAngle", Math.toRadians(angleDeg.toDouble()).toFloat())
            shader.setFloatUniform("uAlbedo", f.r, f.g, f.b)
            rgb(p.accent, col); shader.setFloatUniform("uCap", col[0], col[1], col[2])
            shader.setFloatUniform("uAccent", col[0], col[1], col[2])
            rgb(p.bg, col); shader.setFloatUniform("uBase", col[0], col[1], col[2])

            fun mix3(name: String, l: FloatArray, d: FloatArray) = shader.setFloatUniform(
                name, lerp(l[0], d[0], dk), lerp(l[1], d[1], dk), lerp(l[2], d[2], dk)
            )
            mix3("uSky", floatArrayOf(0.72f, 0.78f, 0.90f), floatArrayOf(0.03f, 0.05f, 0.09f))
            mix3("uKeyCol", floatArrayOf(1f, 0.97f, 0.92f), floatArrayOf(1.5f, 0.98f, 0.5f))
            mix3("uFillCol", floatArrayOf(0.45f, 0.68f, 1f), floatArrayOf(0.40f, 0.70f, 1.4f))
            mix3("uRimCol", floatArrayOf(0.80f, 0.86f, 1f), floatArrayOf(1.3f, 0.40f, 1f))

            // dark mode: the night lights come on one after another (with a little flicker)
            val on = if (dk > 0.5f) 1f else 0f
            val lt = lerp(1f, lightT, dk)
            shader.setFloatUniform("uKeyOn", lerp(1f, flick(lt, 0.00f), dk))
            shader.setFloatUniform("uFillOn", lerp(1f, flick(lt, 0.22f), dk))
            shader.setFloatUniform("uRimOn", lerp(1f, flick(lt, 0.42f), dk))
            shader.setFloatUniform("uDark", dk)
            shader.setFloatUniform("uGlow", (0.25f + 0.75f * beat).coerceIn(0f, 1f))
            if (on < 0f) return
            c.drawRect(0f, 0f, w, cy + w * 0.5f, paint)
        }
    }
}
