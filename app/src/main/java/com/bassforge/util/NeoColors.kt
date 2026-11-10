package com.bassforge.util

import androidx.core.graphics.ColorUtils

/** Shared palette. Dark, cinematic, with a violet -> cyan -> magenta energy ramp. */
object NeoColors {
    val BG_TOP = 0xFF1B1F2B.toInt()
    val BG_BOTTOM = 0xFF0C0E14.toInt()
    val VOID = 0xFF05060A.toInt()
    val PANEL = 0xFF1E2230.toInt()
    val TRACK = 0xFF262B3D.toInt()
    val ACCENT = 0xFF7C6CFF.toInt()
    val ACCENT_GLOW = 0xFFA99BFF.toInt()
    val CYAN = 0xFF3DE0FF.toInt()
    val MAGENTA = 0xFFFF4FD8.toInt()
    val TEXT = 0xFFE8EAF2.toInt()
    val TEXT_DIM = 0xFF8A90A6.toInt()

    /** Energy ramp: 0 = violet, ~0.4 = cyan, 1 = magenta. */
    fun ramp(f: Float): Int {
        val x = clamp01(f)
        return if (x < 0.4f) ColorUtils.blendARGB(ACCENT, CYAN, x / 0.4f)
        else ColorUtils.blendARGB(CYAN, MAGENTA, (x - 0.4f) / 0.6f)
    }
}
