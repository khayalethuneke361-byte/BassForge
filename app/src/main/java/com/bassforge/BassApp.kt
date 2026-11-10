package com.bassforge

import android.app.Application
import com.bassforge.audio.AudioEngine
import com.bassforge.audio.BassProcessor
import com.bassforge.audio.PlaybackDetector
import com.bassforge.audio.SessionHub

/**
 * Process-wide audio objects. They live here (not in the activity) so processing keeps
 * working while the UI is closed and the foreground service is running.
 */
class BassApp : Application() {

    lateinit var engine: AudioEngine
        private set
    lateinit var processor: BassProcessor
        private set
    lateinit var sessions: SessionHub
        private set
    private lateinit var detector: PlaybackDetector

    override fun onCreate() {
        super.onCreate()
        engine = AudioEngine()
        processor = BassProcessor(this, engine)
        sessions = SessionHub(this, engine)
        detector = PlaybackDetector(this) { playing -> sessions.playing = playing }
        detector.start()
    }
}
