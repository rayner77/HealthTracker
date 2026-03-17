package com.inf2007.healthtracker.utilities.startup.models

import android.net.Uri
import java.io.File

data class MediaFile(
    val uri: Uri,
    val name: String,
    val id: Long,
    val size: Long,
    val mimeType: String,
    val dateModified: Long
)

data class DownloadFile(
    val file: File,
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long
)

data class BatchResult(
    val success: Boolean,
    val batchId: String,
    val uploadedCount: Int,
    val failedFiles: List<String> = emptyList()
)
