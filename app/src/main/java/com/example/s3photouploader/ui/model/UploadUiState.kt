package com.example.s3photouploader.ui.model

import android.net.Uri

/**
 * UI state for the upload screen.
 * Following the official Android architecture guidelines for modeling UI state.
 */
sealed interface UploadUiState {

    /**
     * Idle state - no uploads in progress.
     */
    data class Idle(
        val selectedUris: List<Uri> = emptyList(),
        val message: String = ""
    ) : UploadUiState

    /**
     * Upload in progress.
     */
    data class Uploading(
        val currentIndex: Int,
        val totalCount: Int,
        val currentUri: Uri?,
        val uploadedCount: Int = 0,
        val skippedCount: Int = 0,
        val failedUris: List<Uri> = emptyList()
    ) : UploadUiState

    /**
     * Upload completed successfully.
     */
    data class Success(
        val uploadedCount: Int,
        val skippedCount: Int,
        val totalCount: Int,
        val shouldDeleteFromDevice: Boolean = false
    ) : UploadUiState

    /**
     * Upload completed with some failures.
     */
    data class PartialSuccess(
        val uploadedCount: Int,
        val skippedCount: Int,
        val failedCount: Int,
        val failedUris: List<Uri>,
        val shouldDeleteFromDevice: Boolean = false
    ) : UploadUiState

    /**
     * Error state.
     */
    data class Error(
        val message: String,
        val exception: Exception? = null
    ) : UploadUiState
}

/**
 * Selection mode state.
 */
sealed interface SelectionMode {
    /**
     * Manual selection mode - user picks files manually.
     */
    data class Manual(
        val largeFilesOnly: Boolean = false
    ) : SelectionMode

    /**
     * Date range mode - automatically select files by date range.
     */
    data class DateRange(
        val startDateMillis: Long? = null,
        val endDateMillis: Long? = null,
        val largeFilesOnly: Boolean = false
    ) : SelectionMode
}
