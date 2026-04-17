package com.thoughtnudge.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that fires when the user taps a notification.
 * Reports "clicked" event to ThoughtNudge backend and opens the app.
 *
 * Auto-registered via manifest merger — clients don't need to declare it.
 */
class TNNotificationClickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getStringExtra("tn_message_id") ?: return
        ThoughtNudge.ensureLoaded(context)
        Log.d(ThoughtNudge.TAG, "Notification clicked: $messageId")

        // Report CLICKED to ThoughtNudge backend
        TNWebhookReporter.reportEvent("clicked", messageId)

        // Launch the app's main activity
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        launchIntent?.let {
            it.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // Forward notification data to the activity
            intent.extras?.let { extras -> it.putExtras(extras) }
            context.startActivity(it)
        }
    }
}
