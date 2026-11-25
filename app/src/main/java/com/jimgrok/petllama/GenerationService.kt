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
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the process in the foreground while a reply is streaming.
 * Switching away from the app otherwise lets Android abort the socket
 * ("Software caused connection abort") before a slow local model answers.
 */
class GenerationService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always invoke startForeground() promptly so Android does not throw
        // ForegroundServiceDidNotStartInTimeException if stop() raced ahead.
        promoteToForeground()

        val action = intent?.action
        if (action == ACTION_STOP || (stopRequested.get() && activeCount.get() <= 0)) {
            releaseWakeLock()
            stopSelf(startId)
            return START_NOT_STICKY
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

    @Suppress("Override")
    fun onTimeout(startId: Int, fgsType: Int) {
        activeCount.set(0)
        stopRequested.set(true)
        releaseWakeLock()
        stopSelf(startId)
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val notif = notification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (_: Exception) {
            // Avoid crashing if foreground start is disallowed by OS policy.
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
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
        internal const val ACTION_STOP = "com.jimgrok.petllama.action.STOP_GENERATION"
        internal val activeCount = AtomicInteger(0)
        internal val stopRequested = AtomicBoolean(false)
    }
}

object GenerationGuard {
    fun start() {
        val app = runCatching { PetLlamaApp.app }.getOrNull() ?: return
        GenerationService.stopRequested.set(false)
        GenerationService.activeCount.incrementAndGet()
        val intent = Intent(app, GenerationService::class.java)
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (_: Exception) {
            GenerationService.activeCount.decrementAndGet()
        }
    }

    fun stop() {
        val remaining = GenerationService.activeCount.decrementAndGet()
        if (remaining > 0) return
        GenerationService.activeCount.set(0)
        GenerationService.stopRequested.set(true)
        val app = runCatching { PetLlamaApp.app }.getOrNull() ?: return
        val stopIntent = Intent(app, GenerationService::class.java).apply {
            action = GenerationService.ACTION_STOP
        }
        runCatching {
            ContextCompat.startForegroundService(app, stopIntent)
        }.onFailure {
            runCatching { app.stopService(Intent(app, GenerationService::class.java)) }
        }
    }
}
