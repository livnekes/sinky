package com.example.s3photouploader.data.repository

import android.net.Uri
import com.example.s3photouploader.data.model.UploadResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for media operations.
 * Following the official Android architecture guidelines.
 */
interface MediaRepository {

    /**
     * Upload a media file to cloud storage.
     *
     * @param uri The URI of the media file to upload
     * @return Flow emitting upload progress and final result
     */
    suspend fun uploadMedia(uri: Uri): Flow<UploadResult>

    /**
     * Upload multiple media files to cloud storage.
     *
     * @param uris List of URIs to upload
     * @return Flow emitting progress updates for each file
     */
    suspend fun uploadMediaBatch(uris: List<Uri>): Flow<UploadResult>

    /**
     * Check if a media file already exists in cloud storage.
     *
     * @param uri The URI of the media file
     * @return Boolean indicating if file exists
     */
    suspend fun mediaExists(uri: Uri): Boolean

    /**
     * Cancel ongoing upload operations.
     */
    suspend fun cancelUploads()
}
