package com.bassforge.audio

import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlin.math.abs

/**
 * A complete description of what the DSP chain should do. [BassProcessor] computes one of
 * these from the user's intent, [AudioEngine] pushes it into every attached audio session.
 */
class DspParams(
    val enabled: Boolean = true,
    /** Pre-EQ gain per band (dB). Already includes the bass contribution and the user EQ. */
    val preEqDb: FloatArray = FloatArray(AudioEngine.BANDS),
    /** Input gain (dB, <= 0). Gain staging: -3 dB for every +6 dB of boost. */
    val preampDb: Float = 0f,
    val lowRatio: Float = 1f,
    val lowMidRatio: Float = 1f,
    val midRatio: Float = 1f,
    val postEqDb: FloatArray = FloatArray(AudioEngine.BANDS),
    val limiterThresholdDb: Float = -1f,
    val loudnessMb: Int = 0,
    /** 0..1, only used by the legacy BassBoost fallback. */
    val bassStrength: Float = 0f
)

/**
 * Owns the audio-effect chains, one per audio session announced by a player.
 *
 * Android 9+: DynamicsProcessing (pre-EQ -> multiband compressor -> post-EQ -> limiter).
 * Older / unsupported: BassBoost + Equalizer.
 *
 * Note on DynamicsProcessing bands: they are not shelving filters. Each band covers the range
 * from the previous band's cutoff up to its own cutoff, and the gain applies to that whole
 * range. The 10 pre-EQ bands are therefore defined by their *upper edges* ([EQ_EDGES]); the
 * labels in [EQ_CENTERS] are the (geometric) centre of each band.
 */
class AudioEngine {

    companion object {
        private const val TAG = "AudioEngine"
        const val BANDS = 10
        const val MBC_COUNT = 4
        private const val CHANNELS = 2
        private const val MAX_SESSIONS = 6

        /** Band centre frequencies (Hz), used for labels and the legacy fallback. */
        val EQ_CENTERS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

        /** Band upper edges (Hz): geometric midpoints between centres, last band capped at 20 kHz. */
        val EQ_EDGES = floatArrayOf(44f, 88f, 177f, 354f, 707f, 1414f, 2828f, 5657f, 11314f, 20000f)

        /** Multiband compressor band upper edges (Hz). */
        val MBC_EDGES = floatArrayOf(120f, 800f, 3000f, 20000f)
    }

    private val chains = LinkedHashMap<Int, Chain>()
    private var params = DspParams()

    val activeSessions: List<Int>
        @Synchronized get() = chains.keys.toList()

    /** Human readable name of the effect path in use, for the UI. */
    val modeName: String
        @Synchronized get() = chains.values.lastOrNull()?.mode ?: "—"

    @Synchronized
    fun attach(session: Int): Boolean {
        if (session <= 0) return false           // session 0 (global mix) is blocked for third-party apps
        if (chains.containsKey(session)) return true
        while (chains.size >= MAX_SESSIONS) {
            val oldest = chains.keys.first()
            chains.remove(oldest)?.release()
        }
        val chain = Chain.create(session) ?: return false
        chains[session] = chain
        chain.apply(params)
        Log.d(TAG, "attached to session $session via ${chain.mode}")
        return true
    }

    @Synchronized
    fun detach(session: Int) {
        chains.remove(session)?.release()
    }

    @Synchronized
    fun detachAll() {
        chains.values.forEach { it.release() }
        chains.clear()
    }

    @Synchronized
    fun apply(p: DspParams) {
        params = p
        chains.values.forEach { it.apply(p) }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────

    private class Chain private constructor(
        val session: Int,
        private val dp: DynamicsProcessing?,
        private val bass: BassBoost?,
        private val eq: Equalizer?
    ) {
        private var loudness: LoudnessEnhancer? = null

        val mode: String get() = if (dp != null) "DynamicsProcessing" else "BassBoost + Equalizer"

        fun apply(p: DspParams) {
            try {
                if (dp != null) applyDynamics(dp, p) else applyLegacy(p)
                applyLoudness(p)
            } catch (e: Exception) {
                Log.w(TAG, "apply failed on session $session: ${e.message}")
            }
        }

        @RequiresApi(Build.VERSION_CODES.P)
        private fun applyDynamics(d: DynamicsProcessing, p: DspParams) {
            d.setInputGainAllChannelsTo(p.preampDb)

            for (i in 0 until BANDS) {
                d.setPreEqBandAllChannelsTo(
                    i, DynamicsProcessing.EqBand(true, EQ_EDGES[i], p.preEqDb[i].coerceIn(-20f, 24f))
                )
                d.setPostEqBandAllChannelsTo(
                    i, DynamicsProcessing.EqBand(true, EQ_EDGES[i], p.postEqDb[i].coerceIn(-20f, 24f))
                )
            }

            // Band 0: 0-120 Hz. Attack 15 ms / release 150 ms: kicks hit and stop, sustained
            // bass is not squashed. Ratio rises with the bass knob, so 0% bass is transparent.
            d.setMbcBandAllChannelsTo(0, mbc(true, MBC_EDGES[0], 15f, 150f, p.lowRatio, -18f))
            // Band 1: 120-800 Hz. Gentle, keeps low-mids from turning to mud.
            d.setMbcBandAllChannelsTo(1, mbc(p.lowMidRatio > 1.01f, MBC_EDGES[1], 20f, 200f, p.lowMidRatio, -26f))
            // Band 2: 800-3000 Hz. Dialogue lives here; only active in movie mode.
            d.setMbcBandAllChannelsTo(2, mbc(p.midRatio > 1.01f, MBC_EDGES[2], 10f, 100f, p.midRatio, -22f))
            // Band 3: 3 kHz and up. Left untouched.
            d.setMbcBandAllChannelsTo(3, mbc(false, MBC_EDGES[3], 5f, 80f, 1f, 0f))

            // Limiter: ceiling at threshold (default -1 dBFS) so we never hard clip.
            d.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(
                    true, true, 0,
                    2f,                      // attack ms
                    80f,                     // release ms
                    20f,                     // ratio
                    p.limiterThresholdDb,    // threshold dB
                    0f                       // post gain
                )
            )
            d.setEnabled(p.enabled)
        }

        @RequiresApi(Build.VERSION_CODES.P)
        private fun mbc(
            enabled: Boolean, cutoff: Float, attack: Float, release: Float,
            ratio: Float, threshold: Float
        ) = DynamicsProcessing.MbcBand(
            enabled, cutoff, attack, release,
            ratio.coerceAtLeast(1f), threshold,
            6f,        // knee width dB
            -90f,      // noise gate threshold (effectively off)
            1f,        // expander ratio (off)
            0f, 0f     // pre / post gain
        )

        private fun applyLegacy(p: DspParams) {
            bass?.let {
                if (it.strengthSupported) {
                    it.setStrength((p.bassStrength.coerceIn(0f, 1f) * 1000f).toInt().toShort())
                }
                it.setEnabled(p.enabled)
            }
            eq?.let { e ->
                val range = e.bandLevelRange
                val lo = range[0].toInt()
                val hi = range[1].toInt()
                for (b in 0 until e.numberOfBands.toInt()) {
                    val centreHz = e.getCenterFreq(b.toShort()) / 1000f
                    var nearest = 0
                    var best = Float.MAX_VALUE
                    for (i in 0 until BANDS) {
                        val d = abs(EQ_CENTERS[i] - centreHz)
                        if (d < best) { best = d; nearest = i }
                    }
                    val mb = (p.preEqDb[nearest] * 100f).toInt().coerceIn(lo, hi)
                    e.setBandLevel(b.toShort(), mb.toShort())
                }
                e.setEnabled(p.enabled)
            }
        }

        private fun applyLoudness(p: DspParams) {
            if (p.loudnessMb <= 0 || !p.enabled) {
                try { loudness?.release() } catch (_: Exception) {}
                loudness = null
                return
            }
            if (loudness == null) {
                loudness = try {
                    LoudnessEnhancer(session).also { it.setEnabled(true) }
                } catch (e: Exception) {
                    Log.w(TAG, "LoudnessEnhancer unavailable: ${e.message}")
                    null
                }
            }
            loudness?.setTargetGain(p.loudnessMb)
        }

        fun release() {
            try { dp?.release() } catch (_: Exception) {}
            try { bass?.release() } catch (_: Exception) {}
            try { eq?.release() } catch (_: Exception) {}
            try { loudness?.release() } catch (_: Exception) {}
            loudness = null
        }

        companion object {
            fun create(session: Int): Chain? {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        return Chain(session, createDynamics(session), null, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "DynamicsProcessing failed on $session: ${e.message}")
                    }
                }
                var bb: BassBoost? = null
                var eq: Equalizer? = null
                try {
                    bb = try { BassBoost(0, session) } catch (_: Exception) { null }
                    eq = try { Equalizer(0, session) } catch (_: Exception) { null }
                    if (bb == null && eq == null) return null
                    return Chain(session, null, bb, eq)
                } catch (e: Exception) {
                    try { bb?.release() } catch (_: Exception) {}
                    try { eq?.release() } catch (_: Exception) {}
                    Log.w(TAG, "Legacy effects failed on $session: ${e.message}")
                    return null
                }
            }

            @RequiresApi(Build.VERSION_CODES.P)
            private fun createDynamics(session: Int): DynamicsProcessing {
                val cfg = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    CHANNELS,
                    true, BANDS,        // pre-EQ
                    true, MBC_COUNT,    // multiband compressor
                    true, BANDS,        // post-EQ
                    true                // limiter
                ).build()
                return DynamicsProcessing(0, session, cfg)
            }
        }
    }
}
