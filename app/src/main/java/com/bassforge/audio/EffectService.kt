package com.bassforge.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bassforge.BassApp
import com.bassforge.MainActivity
import com.bassforge.R

/**
 * Foreground service that keeps the process alive while effects are on, and listens for
 * player session broadcasts at runtime (implicit broadcasts reach runtime receivers more
 * reliably than manifest receivers on modern Android).
 */
class EffectService : Service() {

    companion object {
        const val ACTION_STOP = "com.bassforge.action.STOP"
        private const val CHANNEL_ID = "bassforge_fx"
        private const val NOTIF_ID = 7

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, EffectService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EffectService::class.java))
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            (applicationContext as BassApp).sessions.handle(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (applicationContext as BassApp).processor.setEnabled(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EffectService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bass)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }
}
