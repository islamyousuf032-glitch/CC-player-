package com.ccplayer.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.ccplayer.app.R

/** Process-lifetime companion for the user-initiated render/copy operation. */
class ExportForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val handler = cancellationHandler
            if (handler != null) handler.invoke() else stop(applicationContext)
            return START_NOT_STICKY
        }
        val notification = buildNotification(null)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        instance = this
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            cancellationHandler = null
        }
        super.onDestroy()
    }

    private fun buildNotification(progress: Int?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Media export", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ExportForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("CC Player export")
            .setContentText(if (progress == null) "Rendering professional media…" else "Rendering and verifying • $progress%")
            .setOngoing(true)
            .setProgress(100, progress ?: 0, progress == null)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun update(progress: Int?) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(progress))
    }

    companion object {
        private const val CHANNEL_ID = "media-export"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_STOP = "com.ccplayer.app.action.STOP_EXPORT"
        private var instance: ExportForegroundService? = null
        private var cancellationHandler: (() -> Unit)? = null

        fun start(context: Context, onCancel: () -> Unit) {
            cancellationHandler = onCancel
            try {
                context.applicationContext.startForegroundService(
                    Intent(context.applicationContext, ExportForegroundService::class.java),
                )
            } catch (error: RuntimeException) {
                cancellationHandler = null
                throw error
            }
        }

        fun progress(percent: Int?) {
            instance?.update(percent)
        }

        fun stop(context: Context) {
            cancellationHandler = null
            instance = null
            context.applicationContext.stopService(Intent(context.applicationContext, ExportForegroundService::class.java))
        }
    }
}
