package com.inf2007.healthtracker.utilities

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.*

class StepCounterService : Service() {
    private lateinit var stepSensorHelper: StepSensorHelper
    private val firestore = FirebaseFirestore.getInstance()
    private val user = FirebaseAuth.getInstance().currentUser

    override fun onCreate() {
        super.onCreate()
        Log.d("StepService", "StepCounterService started")

        // Start Foreground with multiple types
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(1, notification)
        }

        stepSensorHelper = StepSensorHelper(this) { stepCount ->
            user?.let { syncStepsToFirestore(it.uid, stepCount) }
        }

        // Schedule a reset at midnight
        scheduleMidnightReset()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d("StepService", "StepCounterService stopped")
        stepSensorHelper.stopTracking()
    }

    private fun createNotification(): Notification {
        val channelId = "step_tracker_channel"
        val channelName = "Step Tracker Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Step Tracker Running")
            .setContentText("Tracking your steps in the background...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun syncStepsToFirestore(userId: String, stepCount: Int) {
        val stepsRef = firestore.collection("steps").document("${user?.uid}_${getCurrentDate()}")

        stepsRef.get().addOnSuccessListener { document ->
            if (document.exists()) {
                stepsRef.update("steps", stepCount)
            } else {
                val stepData = hashMapOf(
                    "steps" to stepCount,
                    "timestamp" to Date(),
                    "userId" to userId
                )
                stepsRef.set(stepData)
            }
        }.addOnFailureListener { exception ->
            Log.e("StepService", "Failed to sync steps: ${exception.message}")
        }
    }

    private fun scheduleMidnightReset() {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, MidnightResetReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
    }
}

// Broadcast receiver to reset steps at midnight
class MidnightResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val sharedPreferences = context.getSharedPreferences("stepCounterPrefs", Context.MODE_PRIVATE)
        sharedPreferences.edit().apply {
            putString("lastRecordedDate", getCurrentDate())
            putInt("initialStepCount", -1)
            apply()
        }
        Log.d("MidnightReset", "Steps reset at midnight")
    }
}
