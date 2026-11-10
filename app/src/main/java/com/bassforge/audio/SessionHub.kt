package com.bassforge.audio

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/**
 * Tracks the audio sessions that music players announce and keeps the [AudioEngine]
 * attached to them.
 *
 * Players that support external equalizers broadcast
 * [AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION] when they start and
 * [AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION] when they stop. Players that never
 * send these cannot be processed by a third-party app without root.
 */
class SessionHub(private val context: Context, private val engine: AudioEngine) {

    fun interface Listener { fun onHubChanged() }

    private val open = LinkedHashMap<Int, String>()      // session id -> package name
    val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    var playing: Boolean = false
        set(value) {
            if (field != value) { field = value; notifyChanged() }
        }

    /** Sessions we are actually processing. */
    val attachedCount: Int get() = engine.activeSessions.size

    /** The most recently opened session that we successfully attached to, or -1. */
    val primarySession: Int
        get() = engine.activeSessions.lastOrNull() ?: -1

    fun handle(intent: Intent) {
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: ""
        if (session <= 0 || pkg == context.packageName) return

        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                open[session] = pkg
                engine.attach(session)
                notifyChanged()
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                open.remove(session)
                engine.detach(session)
                notifyChanged()
            }
        }
    }

    fun reset() {
        open.clear()
        engine.detachAll()
        notifyChanged()
    }

    private fun notifyChanged() {
        listeners.forEach { it.onHubChanged() }
    }
}
