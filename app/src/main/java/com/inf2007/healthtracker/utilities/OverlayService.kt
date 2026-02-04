package com.inf2007.healthtracker.utilities

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class OverlayService : Service() {

    companion object {
        const val TAG = "OverlayService"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayTextView: TextView

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Overlay service created")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission")
            stopSelf()
            return
        }

        setupOverlay()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Create TextView programmatically (no layout file needed)
        overlayTextView = TextView(this).apply {
            text = "Health Tracker Active\nSteps: 0 | Heart: --"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.argb(128, 0, 0, 0))
            setPadding(16, 8, 16, 8)
            textSize = 12f
        }

        // Setup layout params
        val params = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
        } else {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
        }

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        try {
            windowManager.addView(overlayTextView, params)
            Log.d(TAG, "Overlay added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Overlay service started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::overlayTextView.isInitialized && ::windowManager.isInitialized) {
                windowManager.removeView(overlayTextView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay: ${e.message}")
        }
        Log.d(TAG, "Overlay service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}