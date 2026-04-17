package com.thoughtnudge.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that fires when the user dismisses (swipes away) a notification.
 * Reports "read" event to ThoughtNudge backend.
 *
 * Auto-registered via manifest merger — clients don't need to declare it.
 */
class TNNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getStringExtra("tn_message_id") ?: return
        ThoughtNudge.ensureLoaded(context)
        Log.d(ThoughtNudge.TAG, "Notification dismissed (read): $messageId")

        // Report READ to ThoughtNudge backend
        TNWebhookReporter.reportEvent("read", messageId)
    }
}
