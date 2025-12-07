package com.example.s3photouploader.data.model

import android.net.Uri

/**
 * Sealed interface representing the result of a media upload operation.
 * Following the official Android architecture guidelines for modeling state.
 */
sealed interface UploadResult {
    val uri: Uri

    /**
     * Upload is in progress.
     */
    data class InProgress(
        override val uri: Uri,
        val progress: Int, // 0-100
        val bytesUploaded: Long,
        val totalBytes: Long
    ) : UploadResult

    /**
     * Upload completed successfully.
     */
    data class Success(
        override val uri: Uri,
        val s3Url: String,
        val wasSkipped: Boolean = false // true if file already existed
    ) : UploadResult

    /**
     * Upload failed with an error.
     */
    data class Error(
        override val uri: Uri,
        val exception: Exception,
        val message: String = exception.message ?: "Unknown error"
    ) : UploadResult
}
