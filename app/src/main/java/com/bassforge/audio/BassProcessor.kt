package com.bassforge.audio

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.pow

/**
 * The tuning brain. Turns user intent (bass knob, sub/punch character, EQ, movie mode)
 * into a [DspParams] and hands it to the [AudioEngine].
 *
 * Gain staging: for every +6 dB of boost in the chain the pre-amp drops 3 dB, so the limiter
 * rarely has to work. The boost measured is the *peak* band gain (bass + user EQ combined).
 *
 * Changes are coalesced (one engine update per ~30 ms) so dragging a knob does not flood
 * the audio server with binder calls.
 */
class BassProcessor(context: Context, private val engine: AudioEngine) {

    companion object {
        private const val PREFS = "bassforge_prefs"
        const val MAX_BASS_DB = 15f
        private const val ATTENUATION_RATIO = 0.5f          // 3 dB per 6 dB
        private const val COALESCE_MS = 30L

        // How much of the bass boost each band receives (31, 62, 125, 250, 500 Hz ...)
        private val BASS_WEIGHTS = floatArrayOf(0.85f, 1.0f, 0.7f, 0.35f, 0.1f, 0f, 0f, 0f, 0f, 0f)
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    /** Called on the main thread whenever state changes (including from outside the activity). */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    var enabled: Boolean = prefs.getBoolean("enabled", true); private set
    var bassLevel: Float = prefs.getFloat("bass_level", 0.35f); private set
    var subLevel: Float = prefs.getFloat("sub_level", 0.5f); private set
    var punchLevel: Float = prefs.getFloat("punch_level", 0.5f); private set
    var loudnessMb: Int = prefs.getInt("loudness_mb", 0); private set
    var movieMode: Boolean = prefs.getBoolean("movie_mode", false); private set
    var presetName: String? = prefs.getString("preset", EQPresets.FLAT.name); private set

    private var eq: FloatArray = loadEq("eq_gains") ?: FloatArray(AudioEngine.BANDS)
    private var eqBeforeMovie: FloatArray? = loadEq("eq_before_movie")
    private var presetBeforeMovie: String? = prefs.getString("preset_before_movie", null)

    val eqGains: FloatArray get() = eq.copyOf()

    private val flushRunnable = Runnable { flush() }

    init {
        flush()
    }

    // ── Setters (all main thread) ──

    fun setEnabled(on: Boolean) { enabled = on; changed() }
    fun setBass(v: Float) { bassLevel = v.coerceIn(0f, 1f); changed() }
    fun setSub(v: Float) { subLevel = v.coerceIn(0f, 1f); changed() }
    fun setPunch(v: Float) { punchLevel = v.coerceIn(0f, 1f); changed() }
    fun setLoudness(mb: Int) { loudnessMb = mb.coerceIn(0, 600); changed() }

    fun setEqBand(index: Int, gainDb: Float) {
        if (index !in 0 until AudioEngine.BANDS) return
        eq[index] = gainDb.coerceIn(-12f, 12f)
        presetName = null
        changed()
    }

    fun applyPreset(preset: EQPresets.Preset) {
        eq = preset.gains.copyOf()
        presetName = preset.name
        changed()
    }

    /** Movie mode swaps in the Movie EQ and remembers the previous curve to restore later. */
    fun setMovieMode(on: Boolean) {
        if (on == movieMode) return
        if (on) {
            eqBeforeMovie = eq.copyOf()
            presetBeforeMovie = presetName
            eq = EQPresets.MOVIE.gains.copyOf()
            presetName = EQPresets.MOVIE.name
        } else {
            eq = eqBeforeMovie?.copyOf() ?: EQPresets.FLAT.gains.copyOf()
            presetName = if (eqBeforeMovie != null) presetBeforeMovie else EQPresets.FLAT.name
            eqBeforeMovie = null
            presetBeforeMovie = null
        }
        movieMode = on
        changed()
    }

    // ── Internals ──

    private fun changed() {
        handler.removeCallbacks(flushRunnable)
        handler.postDelayed(flushRunnable, COALESCE_MS)
        listeners.forEach { it() }
    }

    private fun flush() {
        engine.apply(compute())
        save()
    }

    /** Exposed so the UI can draw the effective bass boost if it wants to. */
    fun bassBoostDb(): Float = bassLevel.pow(1.5f) * MAX_BASS_DB

    private fun compute(): DspParams {
        val boost = bassBoostDb() * (if (movieMode) 0.6f else 1f)   // movie mode: less sub, dialogue stays clear

        // sub slider moves weight toward 31/62 Hz, punch slider toward 125/250 Hz
        val subBias = (subLevel - 0.5f) * 6f       // -3..+3 dB
        val punchBias = (punchLevel - 0.5f) * 6f   // -3..+3 dB

        val pre = FloatArray(AudioEngine.BANDS)
        var peak = 0f
        for (i in 0 until AudioEngine.BANDS) {
            var g = eq[i] + boost * BASS_WEIGHTS[i]
            if (i <= 1) g += subBias
            if (i == 2 || i == 3) g += punchBias * (if (i == 2) 1f else 0.5f)
            g = g.coerceIn(-15f, 20f)
            pre[i] = g
            peak = max(peak, g)
        }
        val preamp = -(peak * ATTENUATION_RATIO)

        // Clarity lift in the post-EQ so bass doesn't bury vocals. Scales with the bass knob,
        // so with bass at 0 the chain stays transparent.
        val post = FloatArray(AudioEngine.BANDS)
        val lift = if (movieMode) 1f else bassLevel
        post[6] = if (movieMode) 3f else 0f           // 2 kHz: dialogue
        post[7] = (if (movieMode) 4f else 2f * lift) // 4 kHz
        post[8] = (if (movieMode) 4f else 2f * lift) // 8 kHz
        post[9] = (if (movieMode) 3f else 2f * lift) // 16 kHz

        return DspParams(
            enabled = enabled,
            preEqDb = pre,
            preampDb = preamp,
            lowRatio = 1f + 2f * bassLevel,          // 1:1 .. 3:1
            lowMidRatio = 1f + 0.5f * bassLevel,     // 1:1 .. 1.5:1
            midRatio = if (movieMode) 2f else 1f,
            postEqDb = post,
            limiterThresholdDb = -1f,
            loudnessMb = loudnessMb,
            bassStrength = bassLevel.pow(1.5f)
        )
    }

    private fun save() {
        prefs.edit()
            .putBoolean("enabled", enabled)
            .putFloat("bass_level", bassLevel)
            .putFloat("sub_level", subLevel)
            .putFloat("punch_level", punchLevel)
            .putInt("loudness_mb", loudnessMb)
            .putBoolean("movie_mode", movieMode)
            .putString("preset", presetName)
            .putString("eq_gains", eq.joinToString(","))
            .putString("eq_before_movie", eqBeforeMovie?.joinToString(","))
            .putString("preset_before_movie", presetBeforeMovie)
            .apply()
    }

    private fun loadEq(key: String): FloatArray? {
        val s = prefs.getString(key, null) ?: return null
        val parts = s.split(",").mapNotNull { it.toFloatOrNull() }
        return if (parts.size == AudioEngine.BANDS) parts.toFloatArray() else null
    }
}
