package com.bassforge.audio

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper

/**
 * Tells the app whether any app is playing music right now.
 *
 * Android does not expose other apps' audio session ids through public API (the per-player
 * session id and state on AudioPlaybackConfiguration are hidden), so this class only answers
 * "is something playing?". Session ids come from [SessionHub], via the broadcasts players
 * send when they open an audio-effect control session.
 */
class PlaybackDetector(
    context: Context,
    private val onChange: (Boolean) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var last = false
    private var started = false

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            // The stream state lags the callback slightly, so look again shortly after.
            handler.removeCallbacks(check)
            handler.postDelayed(check, 150)
        }
    }

    private val check = object : Runnable {
        override fun run() {
            val now = audioManager.isMusicActive
            if (now != last) {
                last = now
                onChange(now)
            }
            // Safety net in case a callback is ever missed.
            if (started) handler.postDelayed(this, 2000)
        }
    }

    val isPlaying: Boolean get() = last

    fun start() {
        if (started) return
        started = true
        audioManager.registerAudioPlaybackCallback(callback, handler)
        handler.post(check)
    }

    fun stop() {
        if (!started) return
        started = false
        try { audioManager.unregisterAudioPlaybackCallback(callback) } catch (_: Exception) {}
        handler.removeCallbacks(check)
    }
}
