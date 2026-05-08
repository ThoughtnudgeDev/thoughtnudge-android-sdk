package com.thoughtnudge.sdk

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Invisible "trampoline" Activity that handles ThoughtNudge notification taps.
 *
 * Replaces the prior BroadcastReceiver-based approach (TNNotificationClickReceiver)
 * because Android 10+ (API 29+) restricts broadcast receivers from launching
 * activities when the host app is in the background or has been killed. The
 * receiver fires, but its `startActivity()` call to bring the app forward is
 * silently rejected by the OS — the user's tap appears to do nothing.
 *
 * An Activity launched directly via PendingIntent.getActivity from a
 * notification tap counts as a foreground user action by the OS — it's
 * allowed to chain into other startActivity calls without restriction.
 *
 * The activity has Theme.NoDisplay + noHistory + excludeFromRecents in the
 * SDK manifest, so it's invisible to the user and finishes itself in
 * onCreate.
 *
 * Auto-registered via the SDK's manifest — clients don't declare it.
 */
class TNNotificationClickActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handleClick()
        } finally {
            finish()
        }
    }

    private fun handleClick() {
        val sourceIntent = intent ?: return
        val messageId = sourceIntent.getStringExtra("tn_message_id") ?: return
        ThoughtNudge.ensureLoaded(applicationContext)
        Log.d(ThoughtNudge.TAG, "Notification clicked: $messageId")

        TNWebhookReporter.reportEvent("clicked", messageId)

        val ctaUrl = sourceIntent.getStringExtra("cta_url")?.takeIf { it.isNotEmpty() }
        if (ctaUrl != null) {
            // Persist for cold-start consumption by the host's launcher
            // Activity via ThoughtNudge.consumePendingDeepLink().
            ThoughtNudge.storePendingDeepLink(applicationContext, ctaUrl, messageId)
            // Fire the in-process callback if the host has set one.
            ThoughtNudge.onDeepLink?.invoke(ctaUrl, messageId)
        }

        val launched = ctaUrl?.let { launchDeepLink(it, sourceIntent) } ?: false
        if (!launched) {
            launchAppMainActivity(sourceIntent)
        }

        // Action-button taps don't trigger setAutoCancel — dismiss explicitly.
        // Body taps already auto-cancel; this is a harmless no-op for those.
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(messageId.hashCode())
    }

    private fun launchDeepLink(url: String, sourceIntent: Intent): Boolean {
        return try {
            val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                setPackage(applicationContext.packageName)
                sourceIntent.extras?.let { putExtras(it) }
            }
            startActivity(deepLinkIntent)
            Log.d(ThoughtNudge.TAG, "Opened cta_url deep link: $url")
            true
        } catch (e: Exception) {
            Log.w(ThoughtNudge.TAG, "Failed to open cta_url '$url': ${e.message} — falling back to main activity")
            false
        }
    }

    private fun launchAppMainActivity(sourceIntent: Intent) {
        val launchIntent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        launchIntent?.let {
            it.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            sourceIntent.extras?.let { extras -> it.putExtras(extras) }
            startActivity(it)
        }
    }
}
