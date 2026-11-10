package com.bassforge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.bassforge.R
import com.bassforge.util.FrameLoop
import com.bassforge.util.NeoColors
import com.bassforge.util.approach
import com.bassforge.util.blend
import com.bassforge.util.dp
import com.bassforge.util.easeOutCubic
import com.bassforge.util.lerp
import com.bassforge.util.wrap180
import com.bassforge.util.withAlpha
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * The bass knob: a metal fidget spinner sitting in a glowing dial.
 *
 *  - Outer ring = the bass dial (270 degree sweep). Drag or tap it to set 0..100 %.
 *  - The spinner = a physical object. Grab it and it follows your finger; let go and it
 *    keeps its momentum. Friction is lower at high bass, so max bass spins longest.
 *  - While music plays the spinner idles at a speed proportional to the bass level.
 *  - Live bass energy ([beat]) fires shockwave rings and flares the glow.
 *  - Motion-blur ghosts trail the arms when it spins fast; the metal highlights stay put
 *    in world space while the body rotates under them, like real brushed metal.
 */
class FidgetKnobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ── Public API ──
    /** Fired only for user changes (dragging the dial). */
    var onBassChanged: ((Float) -> Unit)? = null

    var bassLevel: Float = 0.35f
        set(v) { field = v.coerceIn(0f, 1f) }

    var isMusicPlaying: Boolean = false
    /** Live bass energy 0..1 from the spectrum (0 when unavailable). */
    var beat: Float = 0f
    /** False when effects are bypassed: the whole knob dims. */
    var isActive: Boolean = true

    /** Gives the spinner an instant push, in degrees per second (used by the entrance). */
    fun kick(degPerSec: Float) {
        if (!grabbed) spinVel += degPerSec
    }

    private companion object {
        const val ARC_START = 135f
        const val ARC_SWEEP = 270f
        const val TICKS = 54
        const val LOBES = 3
        const val MAX_RINGS = 4
        const val UNIT = 1000f
        const val MODE_NONE = 0
        const val MODE_DIAL = 1
        const val MODE_SPIN = 2
    }

    // ── Geometry (set in onSizeChanged) ──
    private var cX = 0f
    private var cY = 0f
    private var radius = 0f
    private var rArc = 0f
    private var armLen = 0f
    private var lobeR = 0f
    private var hubR = 0f

    // ── Animated state ──
    private var t = 0f
    private var shown = 0.35f            // smoothed bass for drawing
    private var dim = 1f
    private var beatEnv = 0f
    private var prevBeat = 0f
    private var sinceRing = 1f
    private var sinceTick = 1f
    private val ringP = FloatArray(MAX_RINGS) { 1f }

    private var spinTotal = 0f           // unwrapped angle, degrees (master)
    private var lastTotal = 0f
    private var spinVel = 0f             // deg / s

    // ── Touch ──
    private var mode = MODE_NONE
    private var grabbed = false
    private var grabOffset = 0f
    private var emaVel = 0f
    private var touchIdle = 0f
    private var lastMoveTime = 0L
    private var lastDialTick = -1

    // ── Reusable draw objects ──
    private val dishPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val recessPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = NeoColors.TRACK
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8B93B3.toInt() }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF07080D.toInt() }
    private val holeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hubLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF050608.toInt()
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        color = Color.WHITE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = NeoColors.TEXT_DIM
        letterSpacing = 0.35f
    }

    private val arcRect = RectF()
    private val tmpRect = RectF()
    private val tmpMatrix = Matrix()
    private val sweepMatrix = Matrix()
    private val basePath = Path()
    private val workPath = Path()
    private val ghostPath = Path()
    private val scratch = Path()
    private val scratchMatrix = Matrix()

    private var lastPct = -1
    private var pctText = ""

    private val loop = FrameLoop { dt ->
        step(dt)
        invalidate()
    }

    init {
        contentDescription = context.getString(R.string.bass_knob_desc)
        bloomPaint.shader = RadialGradient(
            0f, 0f, UNIT,
            intArrayOf(NeoColors.ACCENT_GLOW, withAlpha(NeoColors.ACCENT, 0.35f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        shown = bassLevel
    }

    // ───────────────────────── lifecycle / layout ─────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cX = w / 2f
        cY = h / 2f
        radius = min(w, h) / 2f - dp(8f)
        rArc = radius * 0.86f
        armLen = radius * 0.40f
        lobeR = radius * 0.205f
        hubR = radius * 0.13f
        if (radius <= 0f) return

        dishPaint.shader = RadialGradient(
            cX, cY, radius,
            intArrayOf(0xFF1F2335.toInt(), 0xFF141824.toInt(), 0xFF0A0C13.toInt()),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        recessPaint.shader = RadialGradient(
            cX - radius * 0.25f, cY - radius * 0.3f, radius * 0.95f,
            intArrayOf(0xFF0E111B.toInt(), 0xFF0A0C14.toInt(), 0xFF05060A.toInt()),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        rimPaint.strokeWidth = dp(1.5f)
        rimPaint.shader = LinearGradient(
            cX - radius, cY - radius, cX + radius, cY + radius,
            intArrayOf(withAlpha(Color.WHITE, 0.22f), withAlpha(Color.WHITE, 0.02f), withAlpha(Color.WHITE, 0.10f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )

        // dial fill gradient: violet -> cyan -> magenta across the 270 degree sweep
        val sweep = SweepGradient(
            cX, cY,
            intArrayOf(NeoColors.ACCENT, NeoColors.CYAN, NeoColors.MAGENTA, NeoColors.MAGENTA),
            floatArrayOf(0f, 0.40f * 0.75f, 0.75f, 1f)
        )
        sweepMatrix.setRotate(ARC_START, cX, cY)
        sweep.setLocalMatrix(sweepMatrix)
        fillPaint.shader = sweep

        // brushed-metal body: fixed (world space) highlights; the body rotates under them
        bodyPaint.shader = SweepGradient(
            cX, cY,
            intArrayOf(
                0xFF2A2F42.toInt(), 0xFF8A93B4.toInt(), 0xFF1A1D2B.toInt(), 0xFFB4BCD8.toInt(),
                0xFF1C2030.toInt(), 0xFF6B7392.toInt(), 0xFF2A2F42.toInt()
            ),
            floatArrayOf(0f, 0.17f, 0.34f, 0.5f, 0.67f, 0.84f, 1f)
        )
        panelPaint.shader = LinearGradient(
            cX - radius * 0.6f, cY - radius * 0.6f, cX + radius * 0.6f, cY + radius * 0.6f,
            intArrayOf(0xFF3E4560.toInt(), 0xFF202538.toInt(), 0xFF11141F.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        hubPaint.shader = RadialGradient(
            cX - hubR * 0.35f, cY - hubR * 0.4f, hubR * 1.5f,
            intArrayOf(0xFFE6EAF7.toInt(), 0xFF7A83A3.toInt(), 0xFF262B3E.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        buildBody()
    }

    /** Hub + 3 arms + 3 weights, unioned into one outline centred on (0,0). */
    private fun buildBody() {
        basePath.reset()
        basePath.addCircle(0f, 0f, hubR * 1.7f, Path.Direction.CW)
        val armHalf = lobeR * 0.55f
        for (i in 0 until LOBES) {
            val ang = i * 360f / LOBES - 90f
            scratch.reset()
            scratch.addRoundRect(
                RectF(0f, -armHalf, armLen, armHalf), armHalf, armHalf, Path.Direction.CW
            )
            scratchMatrix.setRotate(ang)
            scratch.transform(scratchMatrix)
            basePath.op(scratch, Path.Op.UNION)

            val a = Math.toRadians(ang.toDouble())
            scratch.reset()
            scratch.addCircle(
                (cos(a) * armLen).toFloat(), (sin(a) * armLen).toFloat(), lobeR, Path.Direction.CW
            )
            basePath.op(scratch, Path.Op.UNION)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) loop.start() else loop.stop()
    }

    override fun onDetachedFromWindow() {
        loop.stop()
        super.onDetachedFromWindow()
    }

    // ───────────────────────── physics ─────────────────────────

    private fun step(dt: Float) {
        t += dt
        shown = approach(shown, bassLevel, 14f, dt)
        dim = approach(dim, if (isActive) 1f else 0.28f, 6f, dt)

        // beat envelope: fast attack, slow release
        beatEnv = approach(beatEnv, beat, if (beat > beatEnv) 40f else 7f, dt)
        sinceRing += dt
        sinceTick += dt
        if (isActive && beat > 0.55f && prevBeat <= 0.55f && sinceRing > 0.16f) spawnRing()
        prevBeat = beat
        for (i in 0 until MAX_RINGS) if (ringP[i] < 1f) ringP[i] = min(1f, ringP[i] + dt / 0.85f)

        // spinner
        if (grabbed) {
            touchIdle += dt
            if (touchIdle > 0.08f) emaVel *= exp(-20f * dt)
            spinVel = emaVel
        } else {
            val idleSpeed = if (isMusicPlaying && isActive) 40f + 220f * bassLevel else 0f
            // friction rate (1/s): lighter at high bass so a flung spinner keeps going longer
            val k = lerp(1.1f, 0.35f, bassLevel)
            spinVel += (idleSpeed - spinVel) * (1f - exp(-k * dt))
            if (idleSpeed == 0f && abs(spinVel) < 1f) spinVel = 0f
            spinTotal += spinVel * dt
        }

        // tick each time a weight passes a detent (every 120 degrees)
        val before = floor(lastTotal / 120f)
        val after = floor(spinTotal / 120f)
        if (before != after && abs(spinVel) > 15f && sinceTick > 0.09f) {
            sinceTick = 0f
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        lastTotal = spinTotal
    }

    private fun spawnRing() {
        for (i in 0 until MAX_RINGS) {
            if (ringP[i] >= 1f) {
                ringP[i] = 0f
                sinceRing = 0f
                return
            }
        }
    }

    // ───────────────────────── touch ─────────────────────────

    private fun angleDeg(x: Float, y: Float): Float {
        var a = Math.toDegrees(atan2((y - cY).toDouble(), (x - cX).toDouble())).toFloat()
        if (a < 0f) a += 360f
        return a
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val d = hypot(e.x - cX, e.y - cY)
                if (d > radius * 0.72f) {
                    mode = MODE_DIAL
                    updateDial(e.x, e.y, true)
                } else {
                    mode = MODE_SPIN
                    grabbed = true
                    grabOffset = spinTotal - angleDeg(e.x, e.y)
                    emaVel = 0f
                    spinVel = 0f
                    touchIdle = 0f
                    lastMoveTime = e.eventTime
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == MODE_DIAL) {
                    updateDial(e.x, e.y, false)
                } else if (mode == MODE_SPIN && hypot(e.x - cX, e.y - cY) > hubR * 0.6f) {
                    val target = angleDeg(e.x, e.y) + grabOffset
                    val delta = wrap180(target - spinTotal)
                    val dtS = max((e.eventTime - lastMoveTime) / 1000f, 0.001f)
                    emaVel = lerp(emaVel, (delta / dtS).coerceIn(-3000f, 3000f), 0.5f)
                    spinTotal += delta
                    lastMoveTime = e.eventTime
                    touchIdle = 0f
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == MODE_SPIN) {
                    grabbed = false
                    // only fling if the finger was actually moving when it lifted
                    spinVel = if (e.eventTime - lastMoveTime < 90L) emaVel.coerceIn(-2600f, 2600f) else 0f
                }
                mode = MODE_NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun updateDial(x: Float, y: Float, initial: Boolean) {
        val rel = ((angleDeg(x, y) - ARC_START) % 360f + 360f) % 360f
        val v = if (rel <= ARC_SWEEP) rel / ARC_SWEEP else if (bassLevel > 0.5f) 1f else 0f
        if (!initial && abs(v - bassLevel) > 0.6f) return      // ignore teleports across the gap
        val nv = (v * 100f).roundToInt() / 100f
        if (nv != bassLevel) {
            bassLevel = nv
            val tick = (nv * 20f).toInt()
            if (tick != lastDialTick) {
                lastDialTick = tick
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            onBassChanged?.invoke(nv)
        }
    }

    // ───────────────────────── drawing ─────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return
        val glow = (0.35f + 0.65f * shown) * (0.55f + 0.45f * beatEnv) *
            (0.94f + 0.06f * sin(t * 2.2f)) * dim

        drawDish(canvas)
        drawTicks(canvas, glow)
        drawArc(canvas, glow)
        drawShockwaves(canvas)
        drawBloom(canvas, glow)
        drawSpinner(canvas, glow)
        drawReadout(canvas)
    }

    private fun drawDish(canvas: Canvas) {
        // soft drop shadow under the whole dish
        shadowPaint.alpha = 90
        canvas.drawCircle(cX + dp(2f), cY + dp(8f), radius * 0.97f, shadowPaint)
        canvas.drawCircle(cX, cY, radius * 0.97f, dishPaint)
        canvas.drawCircle(cX, cY, radius * 0.97f, rimPaint)
        canvas.drawCircle(cX, cY, radius * 0.74f, recessPaint)
        canvas.drawCircle(cX, cY, radius * 0.74f, rimPaint)
    }

    private fun drawTicks(canvas: Canvas, glow: Float) {
        val r0 = radius * 0.925f
        val baseLen = dp(5f)
        tickPaint.strokeWidth = dp(2f)
        for (i in 0..TICKS) {
            val f = i / TICKS.toFloat()
            val ang = Math.toRadians((ARC_START + ARC_SWEEP * f).toDouble())
            val c = cos(ang).toFloat()
            val s = sin(ang).toFloat()
            val lit = f <= shown + 0.0001f
            val near = max(0f, 1f - abs(f - shown) * 10f)
            val len = baseLen + dp(5f) * near * glow
            if (lit) {
                tickPaint.color = withAlpha(NeoColors.ramp(f), (0.55f + 0.45f * glow) * dim.coerceAtLeast(0.4f))
            } else {
                tickPaint.color = withAlpha(Color.WHITE, 0.10f)
            }
            canvas.drawLine(cX + c * r0, cY + s * r0, cX + c * (r0 + len), cY + s * (r0 + len), tickPaint)
        }
    }

    private fun drawArc(canvas: Canvas, glow: Float) {
        arcRect.set(cX - rArc, cY - rArc, cX + rArc, cY + rArc)
        trackPaint.strokeWidth = dp(10f)
        canvas.drawArc(arcRect, ARC_START, ARC_SWEEP, false, trackPaint)
        if (shown <= 0.002f) return

        val sweep = ARC_SWEEP * shown
        val layers = floatArrayOf(26f, 18f, 12f)
        val alphas = floatArrayOf(0.10f, 0.16f, 0.26f)
        for (i in layers.indices) {
            fillPaint.strokeWidth = dp(layers[i])
            fillPaint.alpha = (alphas[i] * glow * 255f).toInt().coerceIn(0, 255)
            canvas.drawArc(arcRect, ARC_START, sweep, false, fillPaint)
        }
        fillPaint.strokeWidth = dp(9f)
        fillPaint.alpha = (255f * (0.45f + 0.55f * dim)).toInt()
        canvas.drawArc(arcRect, ARC_START, sweep, false, fillPaint)

        // marker with bloom
        val a = Math.toRadians((ARC_START + sweep).toDouble())
        val mx = cX + rArc * cos(a).toFloat()
        val my = cY + rArc * sin(a).toFloat()
        bloomPaint.alpha = (0.75f * glow * 255f).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.translate(mx, my)
        val br = dp(26f) * (1f + 0.35f * beatEnv)
        canvas.scale(br / UNIT, br / UNIT)
        canvas.drawCircle(0f, 0f, UNIT, bloomPaint)
        canvas.restore()
        markerPaint.color = Color.WHITE
        canvas.drawCircle(mx, my, dp(7.5f), markerPaint)
        markerPaint.color = NeoColors.ramp(shown)
        canvas.drawCircle(mx, my, dp(4.5f), markerPaint)
    }

    private fun drawShockwaves(canvas: Canvas) {
        for (i in 0 until MAX_RINGS) {
            val p = ringP[i]
            if (p >= 1f) continue
            val e = easeOutCubic(p)
            val r = radius * (0.62f + 0.34f * e)
            val fade = (1f - p).pow(1.5f)
            ringPaint.strokeWidth = dp(1f) + dp(4f) * (1f - p)
            ringPaint.color = withAlpha(blend(NeoColors.CYAN, NeoColors.ACCENT, p), 0.55f * fade * dim)
            canvas.drawCircle(cX, cY, r, ringPaint)
        }
    }

    private fun drawBloom(canvas: Canvas, glow: Float) {
        bloomPaint.alpha = (0.50f * glow * 255f).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.translate(cX, cY)
        val s = radius * (0.80f + 0.12f * beatEnv)
        canvas.scale(s / UNIT, s / UNIT)
        canvas.drawCircle(0f, 0f, UNIT, bloomPaint)
        canvas.restore()
    }

    private fun bodyAt(angle: Float, scale: Float, out: Path) {
        tmpMatrix.setScale(scale, scale)
        tmpMatrix.postRotate(angle)
        tmpMatrix.postTranslate(cX, cY)
        basePath.transform(tmpMatrix, out)
    }

    private fun drawSpinner(canvas: Canvas, glow: Float) {
        val angle = ((spinTotal % 360f) + 360f) % 360f

        // cast shadow
        bodyAt(angle, 1f, workPath)
        shadowPaint.alpha = 120
        canvas.save()
        canvas.translate(dp(3f), dp(8f))
        canvas.drawPath(workPath, shadowPaint)
        canvas.restore()
        shadowPaint.alpha = 50
        canvas.save()
        canvas.translate(dp(5f), dp(14f))
        canvas.drawPath(workPath, shadowPaint)
        canvas.restore()

        // motion-blur ghosts
        val speed = abs(spinVel)
        val ghosts = min(6, (speed / 260f).toInt())
        if (ghosts > 0) {
            val dir = sign(spinVel)
            val step = speed * 0.006f
            for (g in ghosts downTo 1) {
                bodyAt(angle - dir * step * g, 1f, ghostPath)
                ghostPaint.alpha = (0.15f * (1f - g / (ghosts + 1f)) * 255f).toInt()
                canvas.drawPath(ghostPath, ghostPaint)
            }
        }

        // body: metal rim
        canvas.drawPath(workPath, bodyPaint)
        bodyStroke.strokeWidth = dp(1.2f)
        bodyStroke.color = withAlpha(Color.WHITE, 0.30f)
        canvas.drawPath(workPath, bodyStroke)

        // body: inset panel, lit from the top-left in world space
        bodyAt(angle, 0.86f, ghostPath)
        canvas.drawPath(ghostPath, panelPaint)
        bodyStroke.strokeWidth = dp(1.6f)
        bodyStroke.color = withAlpha(NeoColors.ramp(shown), (0.25f + 0.5f * glow) * dim)
        canvas.drawPath(ghostPath, bodyStroke)

        // weights
        for (i in 0 until LOBES) {
            val a = Math.toRadians((angle + i * 360f / LOBES - 90f).toDouble())
            drawWeight(canvas, cX + cos(a).toFloat() * armLen, cY + sin(a).toFloat() * armLen, glow)
        }
        drawHub(canvas, angle, glow)
    }

    private fun drawWeight(canvas: Canvas, x: Float, y: Float, glow: Float) {
        val hole = lobeR * 0.60f
        canvas.drawCircle(x, y, hole, holePaint)

        // energy ring
        val ringR = lobeR * 0.76f
        val widths = floatArrayOf(9f, 5f, 2f)
        val alphas = floatArrayOf(0.12f, 0.25f, 0.95f)
        for (i in widths.indices) {
            holeRing.strokeWidth = dp(widths[i])
            holeRing.color = withAlpha(NeoColors.ramp(shown), alphas[i] * glow)
            canvas.drawCircle(x, y, ringR, holeRing)
        }

        // bevel on the hole's edge: shadow upper-left, highlight lower-right (fixed light)
        tmpRect.set(x - hole, y - hole, x + hole, y + hole)
        holeRing.strokeWidth = dp(1.6f)
        holeRing.color = withAlpha(Color.BLACK, 0.85f)
        canvas.drawArc(tmpRect, 160f, 120f, false, holeRing)
        holeRing.color = withAlpha(Color.WHITE, 0.35f)
        canvas.drawArc(tmpRect, -20f, 120f, false, holeRing)
    }

    private fun drawHub(canvas: Canvas, angle: Float, glow: Float) {
        canvas.drawCircle(cX, cY, hubR * 1.25f, holePaint)
        canvas.drawCircle(cX, cY, hubR, hubPaint)
        // three notches rotate with the body so slow spin is visible at the hub
        hubLine.strokeWidth = dp(2f)
        for (i in 0 until 3) {
            val a = Math.toRadians((angle + i * 120f).toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            canvas.drawLine(cX + c * hubR * 0.55f, cY + s * hubR * 0.55f, cX + c * hubR * 0.92f, cY + s * hubR * 0.92f, hubLine)
        }
        capPaint.color = withAlpha(NeoColors.ramp(shown), 0.25f * glow)
        canvas.drawCircle(cX, cY, hubR * 0.52f, capPaint)
        capPaint.color = if (isActive) NeoColors.ramp(shown) else 0xFF3A4054.toInt()
        canvas.drawCircle(cX, cY, hubR * 0.32f, capPaint)
        capPaint.color = withAlpha(Color.WHITE, 0.8f)
        canvas.drawCircle(cX - hubR * 0.09f, cY - hubR * 0.09f, hubR * 0.09f, capPaint)
    }

    private fun drawReadout(canvas: Canvas) {
        val pct = (shown * 100f).roundToInt()
        if (pct != lastPct) {
            lastPct = pct
            pctText = "$pct%"
        }
        pctPaint.textSize = radius * 0.17f
        pctPaint.alpha = (255f * (0.4f + 0.6f * dim)).toInt()
        canvas.drawText(pctText, cX, cY + radius * 0.80f, pctPaint)
        labelPaint.textSize = radius * 0.065f
        canvas.drawText("BASS", cX, cY + radius * 0.80f + radius * 0.10f, labelPaint)
    }
}
