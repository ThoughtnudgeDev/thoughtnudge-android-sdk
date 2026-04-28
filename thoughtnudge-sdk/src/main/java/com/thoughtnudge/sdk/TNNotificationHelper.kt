package com.thoughtnudge.sdk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Builds and displays Android notifications from push data.
 *
 * Recognized data keys (all optional except where noted):
 *   tn_message_id  — required for delivered/clicked/read tracking
 *   title          — notification title
 *   body           — short body shown in collapsed view
 *   header_text    — small line shown in the notification header (subText)
 *   footer_text    — caption text shown in the expanded view summary
 *   image_url      — large image displayed in the expanded view (BigPicture)
 *   cta_text       — passed through to the click intent for client use
 *   cta_url        — passed through to the click intent for client use (deep link)
 */
internal object TNNotificationHelper {
    private const val TAG = "TNNotificationHelper"
    private const val CHANNEL_ID = "tn_push_notifications"
    private const val CHANNEL_NAME = "Push Notifications"
    private const val IMAGE_FETCH_TIMEOUT_MS = 5000L

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

        // PendingIntent → TNNotificationDismissReceiver → reports "read"
        val dismissIntent = Intent(context, TNNotificationDismissReceiver::class.java).apply {
            putExtra("tn_message_id", messageId)
        }
        val deletePendingIntent = PendingIntent.getBroadcast(
            context,
            messageId.hashCode() + 1,
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Resolve the app's icon for the notification
        val appIconRes = try {
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
            appInfo.icon
        } catch (e: Exception) {
            android.R.drawable.ic_dialog_info
        }

        val headerText = data["header_text"]?.takeIf { it.isNotEmpty() }
        val footerText = data["footer_text"]?.takeIf { it.isNotEmpty() }
        val imageUrl = data["image_url"]?.takeIf { it.isNotEmpty() }
        val bitmap = imageUrl?.let { fetchBitmap(it) }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(appIconRes)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        headerText?.let { builder.setSubText(it) }

        if (bitmap != null) {
            builder.setLargeIcon(bitmap)
            val style = NotificationCompat.BigPictureStyle()
                .bigPicture(bitmap)
                .bigLargeIcon(null as Bitmap?)
            footerText?.let { style.setSummaryText(it) }
            builder.setStyle(style)
        } else if (footerText != null || body.length > 40) {
            val style = NotificationCompat.BigTextStyle().bigText(body)
            footerText?.let { style.setSummaryText(it) }
            builder.setStyle(style)
        }

        nm.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun fetchBitmap(url: String): Bitmap? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(IMAGE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(IMAGE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Image fetch returned ${resp.code} for $url")
                    return null
                }
                val bytes = resp.body?.bytes() ?: return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch image $url: ${e.message}")
            null
        }
    }
}
