package com.bassforge.util

import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
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

fun easeInCubic(t: Float): Float {
    val u = clamp01(t)
    return u * u * u
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
