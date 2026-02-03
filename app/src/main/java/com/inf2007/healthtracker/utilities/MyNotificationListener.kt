package com.inf2007.healthtracker.utilities

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class MyNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i("HealthTracker", "=== SERVICE STARTED AND STABLE ===")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val packageName = sbn.packageName
        
        // Get notification content
        val title = extras.getCharSequence("android.title")?.toString() ?: "Notification"
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        // Sometimes use "bigText" for the full details
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
        val fullMessage = if (bigText.length > text.length) bigText else text

        Log.i("SecurityMonitor", "📬 Notification from $packageName: $title - $fullMessage")

        // Send ALL notifications to your Python server
        sendToPlaceholderServer(packageName, title, fullMessage, sbn.postTime)
    }

    private fun sendToPlaceholderServer(pkg: String, title: String, content: String, time: Long) {
        val client = OkHttpClient()

        // 1. Create the JSON object
        val jsonPayload = JSONObject()
        jsonPayload.put("package", pkg)
        jsonPayload.put("title", title)
        jsonPayload.put("content", content)
        jsonPayload.put("timestamp", time)

        // 2. Prepare the Request Body
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonPayload.toString().toRequestBody(mediaType)

        // 3. Build the Request (Using 10.0.2.2 to reach your laptop)
        val request = Request.Builder()
            .url("http://10.0.2.2:5000/notifications")
            .post(body)
            .build()

        // 4. Send the data asynchronously
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("MockServer", "Failed to send to server: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.i("MockServer", "Server Received: ${response.code}")
                } else {
                    Log.e("MockServer", "Server Error: ${response.code}")
                }
                response.close()
            }
        })
    }
}