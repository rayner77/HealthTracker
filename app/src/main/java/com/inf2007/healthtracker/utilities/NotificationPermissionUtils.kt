package com.inf2007.healthtracker.utilities

import android.content.Context
import android.provider.Settings

object NotificationPermissionUtils {
    fun isNotificationAccessGranted(context: Context): Boolean {
        val contentResolver = context.contentResolver
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val packageName = context.packageName
        return enabledListeners?.contains(packageName) == true
    }
}