package com.bassforge.util

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.animation.DecelerateInterpolator

/** One complete colour set. Every custom view reads [ThemeManager.p] at draw time. */
class Palette(
    val dark: Boolean,
    /** 0 = fully light, 1 = fully dark (fractional during the theme transition). */
    val darkAmt: Float,
    val bg: Int,
    val surfaceTop: Int,
    val surfaceBottom: Int,
    /** Neumorphic highlight (top-left) and shadow (bottom-right) colours. */
    val hi: Int,
    val lo: Int,
    val text: Int,
    val textDim: Int,
    val accent: Int,
    val accent2: Int,
    val track: Int,
    val glassFill: Int
) {
    companion object {
        fun mix(a: Palette, b: Palette, t: Float) = Palette(
            dark = if (t < 0.5f) a.dark else b.dark,
            darkAmt = lerp(a.darkAmt, b.darkAmt, t),
            bg = blend(a.bg, b.bg, t),
            surfaceTop = blend(a.surfaceTop, b.surfaceTop, t),
            surfaceBottom = blend(a.surfaceBottom, b.surfaceBottom, t),
            hi = blend(a.hi, b.hi, t),
            lo = blend(a.lo, b.lo, t),
            text = blend(a.text, b.text, t),
            textDim = blend(a.textDim, b.textDim, t),
            accent = blend(a.accent, b.accent, t),
            accent2 = blend(a.accent2, b.accent2, t),
            track = blend(a.track, b.track, t),
            glassFill = blend(a.glassFill, b.glassFill, t)
        )
    }
}

class Accent(val name: String, val c1: Int, val c2: Int)

class Finish(val name: String, val r: Float, val g: Float, val b: Float)

/**
 * App-wide theme + settings. Backed by SharedPreferences. Changing the theme animates the
 * palette so the whole UI cross-fades instead of flashing.
 */
object ThemeManager {

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    val ACCENTS = listOf(
        Accent("Electric", 0xFF2F7BFF.toInt(), 0xFF29C5FF.toInt()),
        Accent("Violet", 0xFF7B5CFF.toInt(), 0xFFC65CFF.toInt()),
        Accent("Sunset", 0xFFFF8A3D.toInt(), 0xFFFFC23D.toInt()),
        Accent("Candy", 0xFFFF4F9A.toInt(), 0xFFFF8A5C.toInt()),
        Accent("Mint", 0xFF1FCB8F.toInt(), 0xFF2FD7E8.toInt())
    )

    val FINISHES = listOf(
        Finish("Steel", 0.74f, 0.77f, 0.82f),
        Finish("Titanium", 0.68f, 0.64f, 0.60f),
        Finish("Ocean", 0.20f, 0.46f, 0.96f),
        Finish("Rose", 0.93f, 0.52f, 0.62f),
        Finish("Obsidian", 0.22f, 0.23f, 0.28f)
    )

    private lateinit var prefs: SharedPreferences

    var mode = MODE_SYSTEM; private set
    var accentIndex = 0; private set
    var finishIndex = 0; private set
    var haptics = true; private set
    var introSpin = true; private set
    var liveSpectrum = true; private set
    var keepAlive = true; private set

    /** The palette currently shown (interpolated while a transition runs). */
    lateinit var p: Palette
        private set

    private var target: Palette? = null
    private var anim: ValueAnimator? = null
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Fires when the resolved light/dark state changes, so the spinner can run its lights-on. */
    val onDarkChanged = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    val accent: Accent get() = ACCENTS[accentIndex]
    val finish: Finish get() = FINISHES[finishIndex]

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("bassforge_theme", Context.MODE_PRIVATE)
        mode = prefs.getInt("mode", MODE_SYSTEM)
        accentIndex = prefs.getInt("accent", 0).coerceIn(0, ACCENTS.size - 1)
        finishIndex = prefs.getInt("finish", 0).coerceIn(0, FINISHES.size - 1)
        haptics = prefs.getBoolean("haptics", true)
        introSpin = prefs.getBoolean("intro_spin", true)
        liveSpectrum = prefs.getBoolean("spectrum", true)
        keepAlive = prefs.getBoolean("keep_alive", true)
        p = build(resolveDark(ctx), accentIndex)
    }

    fun resolveDark(ctx: Context): Boolean = when (mode) {
        MODE_DARK -> true
        MODE_LIGHT -> false
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun build(dark: Boolean, accentIdx: Int): Palette {
        val a = ACCENTS[accentIdx]
        return if (dark) Palette(
            dark = true, darkAmt = 1f,
            bg = 0xFF0C111D.toInt(),
            surfaceTop = 0xFF172033.toInt(), surfaceBottom = 0xFF111827.toInt(),
            hi = 0xFF202D49.toInt(), lo = 0xFF03060C.toInt(),
            text = 0xFFE9EEF9.toInt(), textDim = 0xFF8795B1.toInt(),
            accent = a.c1, accent2 = a.c2,
            track = 0xFF0A0E18.toInt(), glassFill = 0xFF24324F.toInt()
        ) else Palette(
            dark = false, darkAmt = 0f,
            bg = 0xFFE4ECF7.toInt(),
            surfaceTop = 0xFFEDF3FB.toInt(), surfaceBottom = 0xFFDCE6F3.toInt(),
            hi = 0xFFFFFFFF.toInt(), lo = 0xFFA4B8D6.toInt(),
            text = 0xFF1B2640.toInt(), textDim = 0xFF6A7893.toInt(),
            accent = a.c1, accent2 = a.c2,
            track = 0xFFD3DFEE.toInt(), glassFill = 0xFFFFFFFF.toInt()
        )
    }

    /** Re-resolves the palette from current settings. Animated unless [animate] is false. */
    fun refresh(ctx: Context, animate: Boolean) {
        val dark = resolveDark(ctx)
        val goal = build(dark, accentIndex)
        val wasDark = p.dark
        anim?.cancel()
        if (!animate) {
            p = goal
            target = null
            notifyChanged()
        } else {
            val from = p
            target = goal
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420L
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    p = Palette.mix(from, goal, it.animatedValue as Float)
                    notifyChanged()
                }
                start()
            }
        }
        if (dark != wasDark) onDarkChanged.forEach { it(dark) }
    }

    private fun notifyChanged() = listeners.forEach { it() }

    fun setMode(ctx: Context, m: Int) {
        mode = m
        prefs.edit().putInt("mode", m).apply()
        refresh(ctx, true)
    }

    fun setAccent(ctx: Context, i: Int) {
        accentIndex = i.coerceIn(0, ACCENTS.size - 1)
        prefs.edit().putInt("accent", accentIndex).apply()
        refresh(ctx, true)
    }

    fun setFinish(i: Int) {
        finishIndex = i.coerceIn(0, FINISHES.size - 1)
        prefs.edit().putInt("finish", finishIndex).apply()
        notifyChanged()
    }

    fun setHaptics(v: Boolean) { haptics = v; prefs.edit().putBoolean("haptics", v).apply() }
    fun setIntroSpin(v: Boolean) { introSpin = v; prefs.edit().putBoolean("intro_spin", v).apply() }
    fun setLiveSpectrum(v: Boolean) { liveSpectrum = v; prefs.edit().putBoolean("spectrum", v).apply() }
    fun setKeepAlive(v: Boolean) { keepAlive = v; prefs.edit().putBoolean("keep_alive", v).apply() }

    fun resetAll(ctx: Context) {
        prefs.edit().clear().apply()
        mode = MODE_SYSTEM; accentIndex = 0; finishIndex = 0
        haptics = true; introSpin = true; liveSpectrum = true; keepAlive = true
        refresh(ctx, true)
    }
}
