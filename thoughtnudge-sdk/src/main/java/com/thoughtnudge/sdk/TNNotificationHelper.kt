package com.thoughtnudge.sdk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Builds and displays Android notifications from push data.
 * Creates a notification channel on Android 8+ automatically.
 */
internal object TNNotificationHelper {
    private const val CHANNEL_ID = "tn_push_notifications"
    private const val CHANNEL_NAME = "Push Notifications"

    fun show(context: Context, title: String, body: String, data: Map<String, String>) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val messageId = data["tn_message_id"] ?: ""

        // Create notification channel (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Push notifications from ThoughtNudge"
            }
            nm.createNotificationChannel(channel)
        }

        // PendingIntent → TNNotificationClickReceiver → reports "clicked"
        val clickIntent = Intent(context, TNNotificationClickReceiver::class.java).apply {
            putExtra("tn_message_id", messageId)
            // Pass all data extras so the client can use them
            for ((key, value) in data) {
                putExtra(key, value)
            }
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            messageId.hashCode(),
            clickIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Resolve the app's icon for the notification
        val appIconRes = try {
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
            appInfo.icon
        } catch (e: Exception) {
            android.R.drawable.ic_dialog_info
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(appIconRes)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}
