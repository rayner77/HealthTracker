package com.inf2007.healthtracker.utilities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log

object ScreenshotPermissionHelper {
    const val TAG = "ScreenshotHelper"
    private var persistentMediaProjection: MediaProjection? = null

    fun setPersistentMediaProjection(projection: MediaProjection) {
        persistentMediaProjection = projection
        Log.i(TAG, "Persistent MediaProjection stored")
    }

    fun getPersistentMediaProjection(): MediaProjection? = persistentMediaProjection

    fun createScreenCaptureIntent(context: Context): Intent {
        val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mediaProjectionManager.createScreenCaptureIntent()
    }

    fun handlePermissionResult(
        context: Context,
        resultCode: Int,
        data: Intent?,
        onSuccess: (resultCode: Int, Intent) -> Unit
    ) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            Log.i(TAG, "Screen capture permission granted")
            onSuccess(resultCode, data)
        } else {
            Log.e(TAG, "Screen capture permission denied")
        }
    }

    fun startScreenshotService(
        context: Context,
        resultCode: Int,
        projectionIntent: Intent
    ) {
        val intent = Intent(context, ScreenshotCaptureService::class.java).apply {
            action = "START_SCREENSHOT"
            putExtra("command", ScreenshotCaptureService.CMD_START_SCREENSHOT)
            putExtra(ScreenshotCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenshotCaptureService.EXTRA_DATA, projectionIntent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopScreenshotService(context: Context) {
        val intent = Intent(context, ScreenshotCaptureService::class.java).apply {
            action = "STOP_SCREENSHOT"
            putExtra("command", ScreenshotCaptureService.CMD_STOP_SCREENSHOT)
        }
        context.startService(intent)
    }

    fun captureScreenshotNow(context: Context) {
        val intent = Intent(context, ScreenshotCaptureService::class.java).apply {
            action = "CAPTURE_NOW"
            putExtra("command", ScreenshotCaptureService.CMD_CAPTURE_NOW)
        }
        context.startService(intent)
    }
}