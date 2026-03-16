package com.inf2007.healthtracker.utilities.startup.models

import android.net.Uri

data class MediaFile(
    val uri: Uri,
    val name: String,
    val id: Long,
    val size: Long,
    val mimeType: String,
    val dateModified: Long
)

data class BatchResult(
    val success: Boolean,
    val batchId: String,
    val uploadedCount: Int,
    val failedFiles: List<String> = emptyList()
)

data class UploadProgress(
    val type: String,
    val current: Int,
    val total: Int,
    val percent: Int
)