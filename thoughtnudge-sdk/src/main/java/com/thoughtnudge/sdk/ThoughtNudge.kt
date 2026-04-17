package com.thoughtnudge.sdk

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging

/**
 * ThoughtNudge Push Notification SDK for Android.
 *
 * Usage:
 * ```
 * // 1. Initialize (once, in Application.onCreate or MainActivity.onCreate)
 * ThoughtNudge.init(context, "https://api.thoughtnudge.com", "YOUR_APP_ID")
 *
 * // 2. Identify user (after login)
 * ThoughtNudge.identify("user-123")
 *
 * // 3. Logout (when user logs out)
 * ThoughtNudge.logout()
 * ```
 */
object ThoughtNudge {
    internal const val TAG = "ThoughtNudge"
    private const val PREFS_NAME = "thoughtnudge_sdk"
    private const val KEY_USER_ID = "tn_user_id"
    private const val KEY_APP_ID = "tn_app_id"
    private const val KEY_API_BASE_URL = "tn_api_base_url"
    private const val KEY_FCM_TOKEN = "tn_fcm_token"

    internal var apiBaseUrl = ""
        private set
    internal var appId = ""
        private set
    internal var userId = ""
        private set
    internal var context: Context? = null
        private set

    private var prefs: SharedPreferences? = null
    private var initialized = false

    /**
     * Initialize the ThoughtNudge SDK.
     * Call once in your Application.onCreate() or MainActivity.onCreate().
     *
     * @param context     Application context
     * @param apiBaseUrl  ThoughtNudge backend URL (provided by ThoughtNudge)
     * @param appId       Your app ID (provided by ThoughtNudge)
     */
    fun init(context: Context, apiBaseUrl: String, appId: String) {
        this.context = context.applicationContext
        this.apiBaseUrl = apiBaseUrl.trimEnd('/')
        this.appId = appId
        this.prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Restore user ID from prefs (survives app restart)
        this.userId = prefs?.getString(KEY_USER_ID, "") ?: ""

        // Save config
        prefs?.edit()
            ?.putString(KEY_APP_ID, appId)
            ?.putString(KEY_API_BASE_URL, apiBaseUrl)
            ?.apply()

        initialized = true
        Log.d(TAG, "SDK initialized. appId=$appId, apiBaseUrl=$apiBaseUrl")

        // If user was previously identified, re-register token
        if (userId.isNotEmpty()) {
            refreshToken()
        }
    }

    /**
     * Associate a user with this device. Call after user login.
     * Fetches the FCM token and registers it with ThoughtNudge backend.
     *
     * @param userId  Your app's user identifier
     */
    fun identify(userId: String) {
        checkInitialized()
        this.userId = userId
        prefs?.edit()?.putString(KEY_USER_ID, userId)?.apply()
        Log.d(TAG, "User identified: $userId")
        refreshToken()
    }

    /**
     * Call on user logout to deregister the device token.
     */
    fun logout() {
        checkInitialized()
        val token = prefs?.getString(KEY_FCM_TOKEN, "") ?: ""
        if (token.isNotEmpty()) {
            TNWebhookReporter.post(
                "$apiBaseUrl/notifications/deregister-token/",
                mapOf("token" to token)
            )
        }
        userId = ""
        prefs?.edit()
            ?.remove(KEY_USER_ID)
            ?.remove(KEY_FCM_TOKEN)
            ?.apply()
        Log.d(TAG, "User logged out, token deregistered")
    }

    /**
     * Report a custom event to ThoughtNudge backend.
     * Delivered and clicked events are tracked automatically by the SDK.
     *
     * @param eventType  Event type string
     * @param messageId  The tn_message_id from the notification data
     */
    fun reportEvent(eventType: String, messageId: String) {
        checkInitialized()
        TNWebhookReporter.reportEvent(eventType, messageId)
    }

    // -- Internal --

    /**
     * Restore config from SharedPreferences. Called by the FCM service and receivers
     * which may run in a fresh process (before init() has been called).
     */
    internal fun ensureLoaded(ctx: Context) {
        if (apiBaseUrl.isNotEmpty()) return
        val p = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        apiBaseUrl = p.getString(KEY_API_BASE_URL, "") ?: ""
        appId = p.getString(KEY_APP_ID, "") ?: ""
        userId = p.getString(KEY_USER_ID, "") ?: ""
        context = ctx.applicationContext
        prefs = p
    }

    internal fun onTokenRefresh(newToken: String) {
        prefs?.edit()?.putString(KEY_FCM_TOKEN, newToken)?.apply()
        if (userId.isNotEmpty() && apiBaseUrl.isNotEmpty()) {
            registerToken(newToken)
        }
    }

    private fun refreshToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                prefs?.edit()?.putString(KEY_FCM_TOKEN, token)?.apply()
                Log.d(TAG, "FCM token obtained")
                registerToken(token)
            } else {
                Log.e(TAG, "Failed to get FCM token", task.exception)
            }
        }
    }

    private fun registerToken(token: String) {
        if (userId.isEmpty() || apiBaseUrl.isEmpty()) return
        TNWebhookReporter.post(
            "$apiBaseUrl/notifications/register-token/",
            mapOf(
                "user_id" to userId,
                "token" to token,
                "platform" to "android",
                "app_id" to appId
            )
        )
        Log.d(TAG, "Token registered with backend")
    }

    private fun checkInitialized() {
        if (!initialized) {
            throw IllegalStateException(
                "ThoughtNudge SDK not initialized. Call ThoughtNudge.init() first."
            )
        }
    }
}
