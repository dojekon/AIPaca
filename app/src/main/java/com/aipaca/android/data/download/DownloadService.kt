package com.aipaca.android.data.download

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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aipaca.android.AiPacaApp
import com.aipaca.android.MainActivity
import com.aipaca.android.R
import com.aipaca.android.ui.models.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val manager: DownloadManager
        get() = (application as AiPacaApp).container.downloadManager

    private var lastNotifyAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification(null, terminal = false))
        scope.launch {
            manager.progress.collect { items ->
                val active = items.filter {
                    it.state == DownloadManager.STATE_QUEUED || it.state == DownloadManager.STATE_RUNNING
                }
                if (active.isEmpty()) {
                    val finished = items.firstOrNull { it.state == DownloadManager.STATE_COMPLETED }
                    val waiting = items.firstOrNull {
                        it.state == DownloadManager.STATE_PAUSED && manager.isPausedByWifi(it.downloadId)
                    }
                    val terminal = finished ?: waiting
                    if (terminal != null) {
                        notify(buildNotification(terminal, terminal = true))
                    }
                    stopSelf()
                } else {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotifyAt >= NOTIFY_INTERVAL_MS) {
                        lastNotifyAt = now
                        val current = active.firstOrNull { it.state == DownloadManager.STATE_RUNNING }
                            ?: active.first()
                        notify(buildNotification(current, terminal = false))
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, INVALID_ID) ?: INVALID_ID
        when (intent?.action) {
            ACTION_PAUSE -> if (id > 0L) scope.launch { manager.pause(id) }
            ACTION_CANCEL -> if (id > 0L) scope.launch { manager.cancel(id) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(notification: Notification) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(item: DownloadProgress?, terminal: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_engine_notification)
            .setContentIntent(openAppIntent)
            .setOnlyAlertOnce(true)
        if (item == null) {
            return builder
                .setContentTitle(getString(R.string.download_notif_title))
                .setContentText(getString(R.string.download_notif_title))
                .setOngoing(true)
                .build()
        }
        val percent = if (item.totalBytes > 0L) {
            (item.downloadedBytes * 100L / item.totalBytes).coerceIn(0L, 100L).toInt()
        } else {
            0
        }
        val text = when {
            terminal && item.state == DownloadManager.STATE_PAUSED ->
                getString(R.string.download_wifi_required)
            terminal -> getString(R.string.download_complete)
            item.totalBytes > 0L -> "$percent% • ${formatBytes(item.speedBps.toLong())}/s"
            else -> formatBytes(item.downloadedBytes)
        }
        builder.setContentTitle(item.fileName).setContentText(text)
        if (terminal) {
            builder.setOngoing(false)
        } else {
            builder
                .setOngoing(true)
                .setProgress(PROGRESS_MAX, percent, false)
                .addAction(
                    0,
                    getString(R.string.download_action_pause),
                    actionIntent(ACTION_PAUSE, item.downloadId, REQUEST_PAUSE),
                )
                .addAction(
                    0,
                    getString(R.string.download_action_cancel),
                    actionIntent(ACTION_CANCEL, item.downloadId, REQUEST_CANCEL),
                )
        }
        return builder.build()
    }

    private fun actionIntent(action: String, id: Long, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, DownloadService::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {

        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 2
        private const val EXTRA_ID = "download_id"
        private const val INVALID_ID = -1L
        private const val PROGRESS_MAX = 100
        private const val NOTIFY_INTERVAL_MS = 1_000L
        private const val ACTION_PAUSE = "com.aipaca.android.download.action.PAUSE"
        private const val ACTION_CANCEL = "com.aipaca.android.download.action.CANCEL"
        private const val REQUEST_PAUSE = 10
        private const val REQUEST_CANCEL = 11

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
