package com.bassforge.audio

import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Live spectrum from the player's audio session using android.media.audiofx.Visualizer.
 * Requires the RECORD_AUDIO permission (an Android requirement for Visualizer; the app only
 * reads FFT magnitudes and never records or stores audio).
 *
 * [onFrame] receives [BARS] log-spaced magnitudes in 0..1 and an overall bass energy in 0..1.
 * It runs on the thread that called [retarget] (the main thread).
 */
class SpectrumTap(private val onFrame: (bars: FloatArray, bass: Float) -> Unit) {

    companion object {
        private const val TAG = "SpectrumTap"
        const val BARS = 32
        private const val F_MIN = 30f
        private const val F_MAX = 16000f
    }

    private var visualizer: Visualizer? = null
    private var session = -1
    private val bars = FloatArray(BARS)

    val isRunning: Boolean get() = visualizer != null

    fun retarget(newSession: Int) {
        if (newSession == session && visualizer != null) return
        release()
        session = newSession
        if (newSession <= 0) return
        try {
            val v = Visualizer(newSession)
            v.setEnabled(false)
            val size = min(1024, Visualizer.getCaptureSizeRange()[1])
            v.setCaptureSize(size)
            val rate = min(Visualizer.getMaxCaptureRate(), 25000)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(viz: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(viz: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null) process(fft, samplingRate, size)
                }
            }, rate, false, true)
            v.setEnabled(true)
            visualizer = v
        } catch (e: Exception) {
            // No RECORD_AUDIO permission, or the session cannot be tapped. The UI falls back to
            // its standby animation.
            Log.w(TAG, "Visualizer unavailable: ${e.message}")
            visualizer = null
        }
    }

    fun release() {
        try { visualizer?.setEnabled(false) } catch (_: Exception) {}
        try { visualizer?.release() } catch (_: Exception) {}
        visualizer = null
        session = -1
    }

    private fun process(fft: ByteArray, samplingRateMilliHz: Int, captureSize: Int) {
        val sampleRate = samplingRateMilliHz / 1000f
        if (sampleRate <= 0f) return
        val binHz = sampleRate / captureSize
        val lastBin = captureSize / 2 - 1
        val ratio = F_MAX / F_MIN

        for (b in 0 until BARS) {
            val fLo = F_MIN * ratio.pow(b / BARS.toFloat())
            val fHi = F_MIN * ratio.pow((b + 1) / BARS.toFloat())
            val lo = max(1, (fLo / binHz).toInt())
            val hi = min(lastBin, max(lo, (fHi / binHz).toInt()))
            var peak = 0f
            for (k in lo..hi) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                peak = max(peak, hypot(re, im))
            }
            // magnitude (0..~181) -> dB -> 0..1, with a slight tilt so highs aren't invisible
            val db = 20f * (ln(peak + 1f) / ln(10f))
            val v = ((db - 4f) / 38f + b / BARS.toFloat() * 0.12f).coerceIn(0f, 1f)
            bars[b] = v
        }
        var bass = 0f
        for (b in 0 until 6) bass += bars[b]
        bass /= 6f
        onFrame(bars, bass)
    }
}
