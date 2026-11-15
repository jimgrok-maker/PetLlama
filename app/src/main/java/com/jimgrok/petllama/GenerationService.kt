package com.jimgrok.petllama

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Keeps the process in the foreground while a reply is streaming.
 * Switching away from the app otherwise lets Android abort the socket
 * ("Software caused connection abort") before a slow local model answers.
 */
class GenerationService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PetLlama:generating")
                .apply { setReferenceCounted(false) }
        }
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire(30 * 60 * 1000L)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL,
            "Replies",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PetLlama")
            .setContentText("Waiting for the model to finish")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "petllama-generating"
        private const val NOTIFICATION_ID = 41
    }
}

object GenerationGuard {
    fun start() {
        val app = PetLlamaApp.app
        val intent = Intent(app, GenerationService::class.java)
        try {
            androidx.core.content.ContextCompat.startForegroundService(app, intent)
        } catch (_: Exception) {
            // A missing notification permission must not stop the chat.
        }
    }

    fun stop() {
        val app = runCatching { PetLlamaApp.app }.getOrNull() ?: return
        runCatching { app.stopService(Intent(app, GenerationService::class.java)) }
    }
}
