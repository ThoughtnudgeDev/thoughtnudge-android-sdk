package com.thoughtnudge.sdk

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * BroadcastReceiver that fires when the user taps a notification.
 * Reports "clicked" event to ThoughtNudge backend, then either:
 *   - launches `cta_url` as a deep link if the message carries one, or
 *   - falls back to the app's main activity.
 *
 * Auto-registered via manifest merger — clients don't need to declare it.
 */
class TNNotificationClickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getStringExtra("tn_message_id") ?: return
        ThoughtNudge.ensureLoaded(context)
        Log.d(ThoughtNudge.TAG, "Notification clicked: $messageId")

        TNWebhookReporter.reportEvent("clicked", messageId)

        val ctaUrl = intent.getStringExtra("cta_url")?.takeIf { it.isNotEmpty() }
        val launched = ctaUrl?.let { launchDeepLink(context, it, intent) } ?: false
        if (!launched) {
            launchAppMainActivity(context, intent)
        }

        // Action-button taps don't trigger setAutoCancel — dismiss explicitly.
        // Body taps already auto-cancel; calling cancel() here is a harmless no-op
        // for those, since the notification has already been removed.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(messageId.hashCode())
    }

    private fun launchDeepLink(context: Context, url: String, sourceIntent: Intent): Boolean {
        return try {
            val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                setPackage(context.packageName)
                sourceIntent.extras?.let { putExtras(it) }
            }
            context.startActivity(deepLinkIntent)
            Log.d(ThoughtNudge.TAG, "Opened cta_url deep link: $url")
            true
        } catch (e: Exception) {
            Log.w(ThoughtNudge.TAG, "Failed to open cta_url '$url': ${e.message} — falling back to main activity")
            false
        }
    }

    private fun launchAppMainActivity(context: Context, sourceIntent: Intent) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        launchIntent?.let {
            it.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            sourceIntent.extras?.let { extras -> it.putExtras(extras) }
            context.startActivity(it)
        }
    }
}
