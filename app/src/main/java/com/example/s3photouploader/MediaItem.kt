package com.example.s3photouploader

import android.net.Uri

data class MediaItem(
    val uri: Uri,
    val size: Long,
    val isVideo: Boolean,
    var isSelected: Boolean = false
) {
    fun getFormattedSize(): String {
        val sizeInMB = size / (1024.0 * 1024.0)
        return String.format("%.1f MB", sizeInMB)
    }
}
