package com.thoughtnudge.sdk

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage

/**
 * ThoughtNudge Push Notification SDK for Android.
 *
 * The SDK does NOT register its own FirebaseMessagingService. You must forward
 * FCM messages and token refreshes from your existing service.
 *
 * Usage:
 * ```
 * // 1. Initialize once in Application.onCreate()
 * ThoughtNudge.init(
 *     context = this,
 *     appId = "YOUR_APP_ID",
 *     environment = ThoughtNudge.Environment.PRODUCTION
 * )
 *
 * // 2. Identify user after login
 * ThoughtNudge.identify("user-123")
 *
 * // 3. In your existing FirebaseMessagingService:
 * override fun onMessageReceived(message: RemoteMessage) {
 *     if (ThoughtNudge.isThoughtNudgeNotification(message)) {
 *         ThoughtNudge.handleMessage(applicationContext, message)
 *         return
 *     }
 *     // your logic
 * }
 * override fun onNewToken(token: String) {
 *     ThoughtNudge.onNewToken(token)
 * }
 *
 * // 4. Logout
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
    private const val MESSAGE_ID_KEY = "tn_message_id"

    // ---- ThoughtNudge Firebase project credentials (hardcoded) ----
    // The SDK initializes a SECONDARY FirebaseApp with these credentials so
    // the client's default FirebaseApp (their own google-services.json)
    // remains untouched. FCM tokens obtained via this secondary app are bound
    // to ThoughtNudge's sender ID — our backend sends to them through our
    // own service account.
    private const val TN_FIREBASE_APP_NAME = "ThoughtNudgeApp"
    private const val TN_FIREBASE_PROJECT_ID = "python-fcm-test-52411"
    private const val TN_FIREBASE_APPLICATION_ID = "1:428970762530:android:41c334a96967cff444c57b"
    private const val TN_FIREBASE_API_KEY = "AIzaSyC-ExiAw4OELxkU0Y1iqSWNNNYMSZiZ0i4"
    private const val TN_FIREBASE_SENDER_ID = "428970762530"

    /**
     * Target ThoughtNudge environment. Each maps to a different backend URL
     * internally — you never need to configure URLs directly.
     */
    enum class Environment(internal val url: String) {
        PRODUCTION("https://api.thoughtnudge.com"),
        STAGING("https://staging-api.thoughtnudge.com"),
        DEVELOPMENT("https://9twvb42p-8000.inc1.devtunnels.ms")
    }

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
    private var pendingUserId: String? = null
    private var tnFirebaseApp: FirebaseApp? = null

    /**
     * Initialize the ThoughtNudge SDK.
     * Call once in Application.onCreate() or MainActivity.onCreate().
     *
     * @param context      Application context
     * @param appId        Your app ID (provided by ThoughtNudge)
     * @param environment  Target environment (defaults to PRODUCTION)
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        context: Context,
        appId: String,
        environment: Environment = Environment.PRODUCTION
    ) {
        this.context = context.applicationContext
        this.apiBaseUrl = environment.url
        this.appId = appId
        this.prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        this.userId = prefs?.getString(KEY_USER_ID, "") ?: ""

        prefs?.edit()
            ?.putString(KEY_APP_ID, appId)
            ?.putString(KEY_API_BASE_URL, environment.url)
            ?.apply()

        ensureTNFirebaseApp(context.applicationContext)

        initialized = true
        Log.d(TAG, "SDK initialized. appId=$appId, env=${environment.name}")

        // Replay any identify() call that was made before init()
        pendingUserId?.let {
            pendingUserId = null
            identify(it)
        }

        if (userId.isNotEmpty()) {
            refreshToken()
        }
    }

    /**
     * Associate a user with this device. Call after user login.
     * If called before init(), the call is queued and replayed once init() completes.
     */
    @JvmStatic
    fun identify(userId: String) {
        if (!initialized) {
            Log.w(TAG, "identify() called before init() — queued")
            pendingUserId = userId
            return
        }
        this.userId = userId
        prefs?.edit()?.putString(KEY_USER_ID, userId)?.apply()
        Log.d(TAG, "User identified: $userId")
        refreshToken()
    }

    /**
     * Call on user logout to deregister the device token.
     */
    @JvmStatic
    fun logout() {
        if (!initialized) return
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
     * Delivered, read, and clicked events are tracked automatically.
     */
    @JvmStatic
    fun reportEvent(eventType: String, messageId: String) {
        TNWebhookReporter.reportEvent(eventType, messageId)
    }

    // ---- FCM forwarding API (called from client's FirebaseMessagingService) ----

    /**
     * Returns true if the given FCM message originated from ThoughtNudge.
     * Call this from your FirebaseMessagingService.onMessageReceived() to
     * decide whether to forward the message to ThoughtNudge.
     */
    @JvmStatic
    fun isThoughtNudgeNotification(remoteMessage: RemoteMessage): Boolean {
        return isThoughtNudgeNotification(remoteMessage.data)
    }

    /**
     * Map-based variant for clients that route FCM payloads as Map<String,String>
     * (e.g. KMPNotifier) and no longer have the RemoteMessage at the detection point.
     */
    @JvmStatic
    fun isThoughtNudgeNotification(data: Map<String, String>): Boolean {
        return data.containsKey(MESSAGE_ID_KEY)
    }

    /**
     * Handle a ThoughtNudge notification: reports "delivered" and displays
     * the notification. Call from FirebaseMessagingService.onMessageReceived()
     * after confirming with isThoughtNudgeNotification().
     */
    @JvmStatic
    fun handleMessage(context: Context, remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val titleOverride = remoteMessage.notification?.title
        val bodyOverride = remoteMessage.notification?.body
        handleMessage(
            context,
            data,
            titleOverride = titleOverride,
            bodyOverride = bodyOverride
        )
    }

    /**
     * Map-based variant for clients that route FCM payloads as Map<String,String>
     * (e.g. KMPNotifier). ThoughtNudge messages are data-only, so title/body
     * are always present in the data payload under "title" and "body" keys —
     * no information is lost vs the RemoteMessage variant.
     */
    @JvmStatic
    @JvmOverloads
    fun handleMessage(
        context: Context,
        data: Map<String, String>,
        titleOverride: String? = null,
        bodyOverride: String? = null
    ) {
        ensureLoaded(context)
        val messageId = data[MESSAGE_ID_KEY] ?: ""
        val title = titleOverride ?: data["title"] ?: ""
        val body = bodyOverride ?: data["body"] ?: ""

        if (messageId.isNotEmpty()) {
            TNWebhookReporter.reportEvent("delivered", messageId)
        }
        if (title.isNotEmpty() || body.isNotEmpty()) {
            TNNotificationHelper.show(context.applicationContext, title, body, data)
        }
    }

    /**
     * Signal that the default FirebaseApp's token has changed. Call from
     * FirebaseMessagingService.onNewToken().
     *
     * The passed token is your default Firebase project's token and is
     * NOT stored by ThoughtNudge — we manage our own token via a secondary
     * FirebaseApp. This call is used as a signal to refresh OUR token,
     * since FCM token rotations often affect both projects.
     */
    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun onNewToken(token: String) {
        val ctx = context
        if (ctx != null) ensureLoaded(ctx)
        if (userId.isNotEmpty() && apiBaseUrl.isNotEmpty()) {
            refreshToken()
        }
    }

    // ---- Internal ----

    /**
     * Restore config from SharedPreferences. Called by receivers that may run
     * in a fresh process before init() has been called.
     */
    internal fun ensureLoaded(ctx: Context) {
        if (apiBaseUrl.isNotEmpty()) return
        val p = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        apiBaseUrl = p.getString(KEY_API_BASE_URL, "") ?: ""
        appId = p.getString(KEY_APP_ID, "") ?: ""
        userId = p.getString(KEY_USER_ID, "") ?: ""
        context = ctx.applicationContext
        prefs = p
        ensureTNFirebaseApp(ctx.applicationContext)
    }

    /**
     * Initialize (or reuse) a secondary FirebaseApp named "ThoughtNudgeApp"
     * with hardcoded ThoughtNudge credentials. This keeps the client's
     * default FirebaseApp (from their google-services.json) untouched while
     * still letting us obtain FCM tokens from ThoughtNudge's project.
     */
    private fun ensureTNFirebaseApp(context: Context) {
        if (tnFirebaseApp != null) return
        val existing = FirebaseApp.getApps(context).firstOrNull { it.name == TN_FIREBASE_APP_NAME }
        if (existing != null) {
            tnFirebaseApp = existing
            return
        }
        val options = FirebaseOptions.Builder()
            .setProjectId(TN_FIREBASE_PROJECT_ID)
            .setApplicationId(TN_FIREBASE_APPLICATION_ID)
            .setApiKey(TN_FIREBASE_API_KEY)
            .setGcmSenderId(TN_FIREBASE_SENDER_ID)
            .build()
        tnFirebaseApp = FirebaseApp.initializeApp(context, options, TN_FIREBASE_APP_NAME)
        Log.d(TAG, "Secondary FirebaseApp initialized: $TN_FIREBASE_APP_NAME")
    }

    private fun refreshToken() {
        val app = tnFirebaseApp ?: run {
            Log.w(TAG, "refreshToken() skipped — secondary FirebaseApp not initialized")
            return
        }
        FirebaseMessaging.getInstance(app).token.addOnCompleteListener { task ->
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
}
