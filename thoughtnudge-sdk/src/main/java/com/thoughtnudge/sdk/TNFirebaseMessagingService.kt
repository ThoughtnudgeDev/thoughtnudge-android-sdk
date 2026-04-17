package com.thoughtnudge.sdk

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Firebase Cloud Messaging service that handles:
 * - Token refresh (re-registers with backend)
 * - Incoming push notifications (reports "delivered" + displays notification)
 *
 * This service is auto-registered via manifest merger — clients don't need
 * to declare it in their AndroidManifest.xml.
 */
class TNFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        ThoughtNudge.ensureLoaded(applicationContext)
        Log.d(ThoughtNudge.TAG, "FCM token refreshed")
        ThoughtNudge.onTokenRefresh(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        ThoughtNudge.ensureLoaded(applicationContext)
        Log.d(ThoughtNudge.TAG, "Push notification received")

        val data = remoteMessage.data
        val messageId = data["tn_message_id"] ?: ""
        val title = remoteMessage.notification?.title
            ?: data["title"]
            ?: ""
        val body = remoteMessage.notification?.body
            ?: data["body"]
            ?: ""

        // Report DELIVERED to ThoughtNudge backend
        if (messageId.isNotEmpty()) {
            TNWebhookReporter.reportEvent("delivered", messageId)
        }

        // Display the notification
        if (title.isNotEmpty() || body.isNotEmpty()) {
            TNNotificationHelper.show(applicationContext, title, body, data)
        }
    }
}
