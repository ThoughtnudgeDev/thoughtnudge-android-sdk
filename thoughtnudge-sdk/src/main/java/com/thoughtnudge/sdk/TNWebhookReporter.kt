package com.thoughtnudge.sdk

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Internal HTTP client for reporting events (delivered, clicked, etc.)
 * back to the ThoughtNudge backend.
 */
internal object TNWebhookReporter {
    private val client = OkHttpClient()

    fun reportEvent(eventType: String, messageId: String) {
        if (ThoughtNudge.apiBaseUrl.isEmpty()) {
            Log.w(ThoughtNudge.TAG, "apiBaseUrl not set, skipping event report")
            return
        }
        post(
            "${ThoughtNudge.apiBaseUrl}/notifications/event/",
            mapOf(
                "event_type" to eventType,
                "message_id" to messageId,
                "user_id" to ThoughtNudge.userId,
                "platform" to "android"
            )
        )
        Log.d(ThoughtNudge.TAG, "Reported event: $eventType for message $messageId")
    }

    internal fun post(url: String, body: Map<String, String>) {
        val json = JSONObject(body as Map<*, *>).toString()
        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(ThoughtNudge.TAG, "API call failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d(ThoughtNudge.TAG, "API response: ${response.code}")
                response.close()
            }
        })
    }
}
