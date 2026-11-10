package com.bassforge.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bassforge.BassApp

/** Receives "a player opened/closed an audio session" broadcasts and forwards them to the hub. */
class EffectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as? BassApp)?.sessions?.handle(intent)
    }
}
