package com.example.s3photouploader.data.repository

import android.content.Context
import android.net.Uri
import com.amazonaws.regions.Regions
import com.example.s3photouploader.CognitoS3Uploader
import com.example.s3photouploader.data.model.UploadResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Implementation of MediaRepository using AWS S3 as the backing storage.
 * This class encapsulates all S3-specific upload logic.
 *
 * In the future, this can be refactored to use Hilt for dependency injection.
 */
class S3MediaRepository(
    private val context: Context,
    private val s3Uploader: CognitoS3Uploader
) : MediaRepository {

    override suspend fun uploadMedia(uri: Uri): Flow<UploadResult> = flow {
        try {
            // Emit in-progress state
            emit(UploadResult.InProgress(
                uri = uri,
                progress = 0,
                bytesUploaded = 0,
                totalBytes = 0
            ))

            // Perform upload
            val result = s3Uploader.uploadImage(
                context = context,
                imageUri = uri,
                bucketName = "", // Will be injected through constructor
                region = Regions.US_EAST_1,
                identityPoolId = "",
                securePrefix = ""
            )

            // Emit success
            emit(UploadResult.Success(
                uri = uri,
                s3Url = result,
                wasSkipped = result.contains("already uploaded")
            ))

        } catch (e: Exception) {
            // Emit error
            emit(UploadResult.Error(
                uri = uri,
                exception = e
            ))
        }
    }

    override suspend fun uploadMediaBatch(uris: List<Uri>): Flow<UploadResult> = flow {
        for (uri in uris) {
            uploadMedia(uri).collect { result ->
                emit(result)
            }
        }
    }

    override suspend fun mediaExists(uri: Uri): Boolean {
        // TODO: Implement check if media already exists in S3
        return false
    }

    override suspend fun cancelUploads() {
        // TODO: Implement cancellation logic
    }
}
