package com.bassforge.util

import android.view.Choreographer

/**
 * Vsync-locked animation loop with a real delta time (seconds, clamped so a stalled frame
 * doesn't teleport the physics).
 */
class FrameLoop(private val onFrame: (dt: Float) -> Unit) : Choreographer.FrameCallback {

    private var running = false
    private var lastNanos = 0L

    fun start() {
        if (running) return
        running = true
        lastNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastNanos == 0L) 0.016f
        else ((frameTimeNanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastNanos = frameTimeNanos
        onFrame(dt)
        if (running) Choreographer.getInstance().postFrameCallback(this)
    }
}
