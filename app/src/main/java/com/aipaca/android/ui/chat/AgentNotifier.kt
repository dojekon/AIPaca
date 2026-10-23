package com.aipaca.android.ui.chat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aipaca.android.MainActivity
import com.aipaca.android.R

class AgentNotifier(
    private val context: Context,
    private val isForeground: () -> Boolean,
) {
    fun notifyFinished(chatId: Long, title: String?, error: String?) {
        if (isForeground()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.agent_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_engine_notification)
            .setContentTitle(
                context.getString(
                    if (error == null) R.string.agent_notification_done else R.string.agent_notification_failed,
                ),
            )
            .setContentText(error ?: title.orEmpty())
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_BASE_ID + chatId.toInt(), notification)
    }

    private companion object {
        const val CHANNEL_ID = "agent"
        const val NOTIFICATION_BASE_ID = 4000
    }
}
