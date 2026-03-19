package com.inf2007.healthtracker.utilities

import android.content.Context
import android.provider.Settings
import android.util.Log

object DeviceUtils {
    private const val TAG = "DeviceUtils"

    fun getUniqueDeviceId(context: Context): String {
        return try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device ID: ${e.message}")
            "unknown"
        }
    }
}