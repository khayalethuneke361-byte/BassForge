package com.bassforge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.bassforge.util.FrameLoop
import com.bassforge.util.NeoColors
import com.bassforge.util.approach
import com.bassforge.util.blend
import com.bassforge.util.clamp01
import com.bassforge.util.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Living backdrop: drifting colour nebulae, rising dust, an anamorphic lens streak that
 * flares on bass hits, and a vignette. Everything reacts to [level] (bass knob) and
 * [energy] (live bass energy from the spectrum).
 */
class CinematicBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Live bass energy 0..1, pushed from the spectrum every frame it is available. */
    var energy = 0f
    /** Bass knob 0..1. */
    var level = 0.35f
    /** False when effects are bypassed: the scene dims. */
    var active = true
    /** Vertical position (fraction of height) of the lens streak. */
    var focusY = 0.30f

    private companion object {
        const val PARTICLES = 64
        const val UNIT = 1000f
    }

    private var t = 0f
    private var e = 0f
    private var dim = 1f

    private val basePaint = Paint()
    private val vignettePaint = Paint()
    private val streakPaint = Paint()
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaints = Array(3) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val blobColors = intArrayOf(NeoColors.ACCENT, NeoColors.CYAN, NeoColors.MAGENTA)

    // normalised particle state
    private val px = FloatArray(PARTICLES)
    private val py = FloatArray(PARTICLES)
    private val psize = FloatArray(PARTICLES)
    private val pspeed = FloatArray(PARTICLES)
    private val pphase = FloatArray(PARTICLES)
    private val pcolor = IntArray(PARTICLES)

    private val loop = FrameLoop { dt ->
        step(dt)
        invalidate()
    }

    init {
        val rnd = java.util.Random(42)
        for (i in 0 until PARTICLES) {
            px[i] = rnd.nextFloat()
            py[i] = rnd.nextFloat()
            psize[i] = 0.6f + rnd.nextFloat() * 2.2f
            pspeed[i] = 0.25f + rnd.nextFloat() * 0.75f
            pphase[i] = rnd.nextFloat() * 6.283f
            pcolor[i] = when (i % 3) {
                0 -> Color.WHITE
                1 -> NeoColors.CYAN
                else -> NeoColors.ACCENT_GLOW
            }
        }
        for (i in 0 until 3) {
            blobPaints[i].shader = RadialGradient(
                0f, 0f, UNIT,
                intArrayOf(blobColors[i], blend(blobColors[i], Color.TRANSPARENT, 0.6f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        basePaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            NeoColors.BG_TOP, NeoColors.BG_BOTTOM, Shader.TileMode.CLAMP
        )
        vignettePaint.shader = RadialGradient(
            w / 2f, h * 0.45f, hypot(w.toFloat(), h.toFloat()) * 0.62f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0xCC000000.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        streakPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.TRANSPARENT, NeoColors.CYAN, Color.WHITE, NeoColors.CYAN, Color.TRANSPARENT),
            floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) loop.start() else loop.stop()
    }

    override fun onDetachedFromWindow() {
        loop.stop()
        super.onDetachedFromWindow()
    }

    private fun step(dt: Float) {
        t += dt
        e = approach(e, energy, if (energy > e) 30f else 6f, dt)
        dim = approach(dim, if (active) 1f else 0.35f, 4f, dt)
        val lift = 0.02f + 0.05f * (e + level * 0.5f)
        for (i in 0 until PARTICLES) {
            py[i] -= pspeed[i] * lift * dt * 4f
            px[i] += sin(t * 0.4f + pphase[i]) * 0.004f * dt * 4f
            if (py[i] < -0.02f) { py[i] = 1.02f; px[i] = (px[i] * 7.31f + 0.13f) % 1f }
            if (px[i] < 0f) px[i] += 1f
            if (px[i] > 1f) px[i] -= 1f
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        canvas.drawRect(0f, 0f, w, h, basePaint)

        // nebulae
        val intensity = (0.20f + 0.22f * level + 0.38f * e) * dim
        for (i in 0 until 3) {
            val k = i * 2.1f
            val bx = w * (0.5f + 0.42f * sin(t * 0.13f + k))
            val by = h * (0.28f + 0.30f * cos(t * 0.10f + k * 1.3f) + i * 0.16f)
            val r = w * (0.80f + 0.12f * sin(t * 0.21f + k) + 0.18f * e)
            blobPaints[i].alpha = (clamp01(intensity * (if (i == 1) 0.8f else 1f)) * 255f).toInt()
            canvas.save()
            canvas.translate(bx, by)
            canvas.scale(r / UNIT, r / UNIT)
            canvas.drawCircle(0f, 0f, UNIT, blobPaints[i])
            canvas.restore()
        }

        // dust
        val baseAlpha = (0.22f + 0.45f * level + 0.5f * e) * dim
        val d = dp(1f)
        for (i in 0 until PARTICLES) {
            val twinkle = 0.5f + 0.5f * sin(t * (0.8f + pspeed[i]) + pphase[i])
            val a = clamp01(baseAlpha * (0.25f + 0.75f * twinkle) * pspeed[i])
            val x = px[i] * w
            val y = py[i] * h
            val rad = psize[i] * d * (1f + 0.5f * e)
            particlePaint.color = pcolor[i]
            particlePaint.alpha = (a * 70f).toInt()
            canvas.drawCircle(x, y, rad * 3.2f, particlePaint)
            particlePaint.alpha = (a * 255f).toInt()
            canvas.drawCircle(x, y, rad, particlePaint)
        }

        // anamorphic lens streak
        val sy = h * focusY
        val flare = clamp01((0.05f + 0.75f * e + 0.10f * level) * dim)
        streakPaint.alpha = (flare * 90f).toInt()
        canvas.drawRect(0f, sy - dp(14f), w, sy + dp(14f), streakPaint)
        streakPaint.alpha = (flare * 255f).toInt()
        canvas.drawRect(0f, sy - dp(1f), w, sy + dp(1f), streakPaint)

        canvas.drawRect(0f, 0f, w, h, vignettePaint)
    }
}
