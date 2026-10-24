package com.ccplayer.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.ccplayer.app.R

/** Keeps the user-initiated render/copy operation in Android's foreground media-processing state. */
class ExportForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        val notification = buildNotification(null)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun buildNotification(progress: Int?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Media export", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("CC Player export")
            .setContentText(if (progress == null) "Rendering professional media…" else "Rendering and verifying • $progress%")
            .setOngoing(true)
            .setProgress(100, progress ?: 0, progress == null)
            .build()
    }

    private fun update(progress: Int?) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(progress))
    }

    companion object {
        private const val CHANNEL_ID = "media-export"
        private const val NOTIFICATION_ID = 41
        private var instance: ExportForegroundService? = null

        fun start(context: Context) {
            context.applicationContext.startForegroundService(
                Intent(context.applicationContext, ExportForegroundService::class.java),
            )
        }

        fun progress(percent: Int?) {
            instance?.update(percent)
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context.applicationContext, ExportForegroundService::class.java))
            instance = null
        }
    }
}
