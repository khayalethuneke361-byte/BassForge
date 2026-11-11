package com.bassforge.util

import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.exp

fun View.dp(v: Float): Float = v * resources.displayMetrics.density

fun View.sp(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun clamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = clamp01((x - e0) / (e1 - e0))
    return t * t * (3f - 2f * t)
}

fun easeOutCubic(t: Float): Float {
    val u = 1f - clamp01(t)
    return 1f - u * u * u
}

fun easeInOutCubic(t: Float): Float {
    val x = clamp01(t)
    return if (x < 0.5f) 4f * x * x * x else 1f - Math.pow((-2.0 * x + 2.0), 3.0).toFloat() / 2f
}

/** Frame-rate independent exponential smoothing toward [target]. */
fun approach(cur: Float, target: Float, rate: Float, dt: Float): Float =
    cur + (target - cur) * (1f - exp(-rate * dt))

fun withAlpha(color: Int, a: Float): Int =
    ColorUtils.setAlphaComponent(color, (clamp01(a) * 255f).toInt())

fun blend(c1: Int, c2: Int, t: Float): Int = ColorUtils.blendARGB(c1, c2, clamp01(t))

/** Wraps an angle difference into (-180, 180]. */
fun wrap180(deg: Float): Float {
    var d = deg % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}

/** Damped spring used for press / toggle / indicator motion. Slight overshoot = premium feel. */
class Spring(
    var value: Float = 0f,
    var target: Float = 0f,
    private val stiffness: Float = 520f,
    private val damping: Float = 26f
) {
    var vel = 0f

    /** Advances the spring. Returns true while it is still moving. */
    fun step(dt: Float): Boolean {
        val h = if (dt > 0.033f) 0.033f else dt
        val acc = stiffness * (target - value) - damping * vel
        vel += acc * h
        value += vel * h
        val settled = abs(vel) < 0.02f && abs(target - value) < 0.002f
        if (settled) {
            value = target
            vel = 0f
        }
        return !settled
    }

    fun snap(v: Float) {
        value = v
        target = v
        vel = 0f
    }
}

/** Per-view frame timer so animated views can use postInvalidateOnAnimation(). */
class Tick {
    private var last = 0L

    fun dt(): Float {
        val now = System.nanoTime()
        val d = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.033f)
        last = now
        return d
    }

    fun reset() {
        last = 0L
    }
}

enum class Haptic { TICK, TAP, CONFIRM }

/** Respects the in-app haptics setting. */
fun View.haptic(kind: Haptic = Haptic.TICK) {
    if (!ThemeManager.haptics) return
    val c = when (kind) {
        Haptic.TICK -> HapticFeedbackConstants.CLOCK_TICK
        Haptic.TAP -> HapticFeedbackConstants.KEYBOARD_TAP
        Haptic.CONFIRM -> HapticFeedbackConstants.LONG_PRESS
    }
    performHapticFeedback(c)
}

/** Implemented by views that need to refresh non-drawn state (e.g. text colours) on theme change. */
interface Themed {
    fun applyTheme()
}
