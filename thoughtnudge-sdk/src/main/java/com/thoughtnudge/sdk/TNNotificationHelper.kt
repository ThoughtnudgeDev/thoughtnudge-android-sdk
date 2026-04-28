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
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Builds and displays Android notifications from push data.
 *
 * Recognized data keys (all optional except tn_message_id):
 *   tn_message_id  — required for delivered/clicked/read tracking
 *   title          — notification title
 *   body           — body text (visible in both collapsed and expanded views)
 *   header_text    — small line shown in the notification header (subText)
 *   footer_text    — caption text shown below the body in expanded view
 *   image_url      — large image displayed in expanded view
 *   cta_text       — passed through to the click intent for client use
 *   cta_url        — deep-link URL opened on body tap
 *   action1_text   — label for the first action button (optional)
 *   action1_url    — deep-link URL for the first action button
 *   action2_text   — label for the second action button (optional)
 *   action2_url    — deep-link URL for the second action button
 */
internal object TNNotificationHelper {
    private const val TAG = "TNNotificationHelper"
    private const val CHANNEL_ID = "tn_push_notifications"
    private const val CHANNEL_NAME = "Push Notifications"
    private const val IMAGE_FETCH_TIMEOUT_MS = 5000L

    fun show(context: Context, title: String, body: String, data: Map<String, String>) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val messageId = data["tn_message_id"] ?: ""

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Push notifications from ThoughtNudge" }
            )
        }

        val headerText = data["header_text"]?.takeIf { it.isNotEmpty() }
        val footerText = data["footer_text"]?.takeIf { it.isNotEmpty() }
        val imageUrl = data["image_url"]?.takeIf { it.isNotEmpty() }
        val bitmap = imageUrl?.let { fetchBitmap(it) }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(resolveAppIcon(context))
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(buildClickPendingIntent(context, messageId, data, codeOffset = 0))
            .setDeleteIntent(buildDismissPendingIntent(context, messageId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        headerText?.let { builder.setSubText(it) }

        // Use a custom expanded view whenever we have an image or footer or
        // a body long enough to benefit from expansion. This guarantees the
        // body text remains visible alongside the image — the standard
        // BigPictureStyle hides body text under the picture.
        val needsExpanded = bitmap != null || footerText != null || body.length > 40
        if (needsExpanded) {
            val customView = RemoteViews(context.packageName, R.layout.tn_notification_expanded)
            if (title.isNotEmpty()) {
                customView.setTextViewText(R.id.tn_title, title)
                customView.setViewVisibility(R.id.tn_title, View.VISIBLE)
            }
            if (body.isNotEmpty()) {
                customView.setTextViewText(R.id.tn_body, body)
                customView.setViewVisibility(R.id.tn_body, View.VISIBLE)
            }
            if (bitmap != null) {
                customView.setImageViewBitmap(R.id.tn_image, bitmap)
                customView.setViewVisibility(R.id.tn_image, View.VISIBLE)
                builder.setLargeIcon(bitmap)
            }
            if (footerText != null) {
                customView.setTextViewText(R.id.tn_footer, footerText)
                customView.setViewVisibility(R.id.tn_footer, View.VISIBLE)
            }
            builder.setCustomBigContentView(customView)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        }

        // Action buttons (up to two). Each routes through TNNotificationClickReceiver
        // with cta_url overridden so the existing click handler opens the right URL.
        for (i in 1..2) {
            val text = data["action${i}_text"]?.takeIf { it.isNotEmpty() } ?: continue
            val url = data["action${i}_url"]?.takeIf { it.isNotEmpty() } ?: continue
            val actionPi = buildClickPendingIntent(
                context,
                messageId,
                data,
                codeOffset = 100 + i,
                ctaUrlOverride = url,
                actionId = "action$i",
            )
            builder.addAction(0, text, actionPi)
        }

        // Use a deterministic notification ID so the click receiver can
        // cancel it on action-button taps (Android only auto-cancels on
        // body taps, not on action button taps).
        val notifId = if (messageId.isNotEmpty()) messageId.hashCode() else System.currentTimeMillis().toInt()
        nm.notify(notifId, builder.build())
    }

    private fun buildClickPendingIntent(
        context: Context,
        messageId: String,
        data: Map<String, String>,
        codeOffset: Int,
        ctaUrlOverride: String? = null,
        actionId: String? = null,
    ): PendingIntent {
        val intent = Intent(context, TNNotificationClickReceiver::class.java).apply {
            putExtra("tn_message_id", messageId)
            for ((k, v) in data) putExtra(k, v)
            ctaUrlOverride?.let { putExtra("cta_url", it) }
            actionId?.let { putExtra("tn_action_id", it) }
        }
        return PendingIntent.getBroadcast(
            context,
            messageId.hashCode() + codeOffset,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildDismissPendingIntent(context: Context, messageId: String): PendingIntent {
        val dismissIntent = Intent(context, TNNotificationDismissReceiver::class.java).apply {
            putExtra("tn_message_id", messageId)
        }
        return PendingIntent.getBroadcast(
            context,
            messageId.hashCode() + 1,
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun resolveAppIcon(context: Context): Int = try {
        context.packageManager.getApplicationInfo(context.packageName, 0).icon
    } catch (e: Exception) {
        android.R.drawable.ic_dialog_info
    }

    private fun fetchBitmap(url: String): Bitmap? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(IMAGE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(IMAGE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
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
