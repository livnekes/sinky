package com.example.s3photouploader

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException

/**
 * S3 Uploader using AWS Cognito Identity Pools for credential-less authentication
 *
 * This approach is more secure as it doesn't require storing AWS access keys in the app.
 * Instead, it uses temporary credentials from Cognito Identity Pools.
 */
class CognitoS3Uploader {

    /**
     * Extracts photo timestamp from EXIF data
     * Returns a pair of (monthDirectory, timestamp) for organizing photos
     */
    private fun getPhotoTimestamp(context: Context, imageUri: Uri): Pair<String, String> {
        try {
            context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                val dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)

                if (dateTime != null) {
                    // EXIF date format: "YYYY:MM:DD HH:MM:SS"
                    val exifFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    val date = exifFormat.parse(dateTime)

                    if (date != null) {
                        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.US)
                        val timestampFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

                        val monthDir = monthFormat.format(date)
                        val timestamp = timestampFormat.format(date)

                        return Pair(monthDir, timestamp)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CognitoS3Uploader", "Error reading EXIF data", e)
        }

        // Fallback to current time if EXIF data is not available
        val now = Date()
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.US)
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

        return Pair(monthFormat.format(now), timestampFormat.format(now))
    }

    suspend fun uploadImage(
        context: Context,
        imageUri: Uri,
        bucketName: String,
        region: Regions,
        identityPoolId: String,
        securePrefix: String
    ): String = suspendCancellableCoroutine { continuation ->
        var isResumed = false
        var tempFile: File? = null
        var uploadObserver: com.amazonaws.mobileconnectors.s3.transferutility.TransferObserver? = null

        try {
            // Create Cognito credentials provider
            // This provides temporary AWS credentials without exposing permanent keys
            val credentialsProvider = CognitoCachingCredentialsProvider(
                context,
                identityPoolId,
                region
            )

            // Create S3 client with Cognito credentials
            val s3Client = AmazonS3Client(credentialsProvider, Region.getRegion(region))

            // Create transfer utility
            val transferUtility = TransferUtility.builder()
                .context(context)
                .s3Client(s3Client)
                .build()

            // Get input stream from URI
            val inputStream: InputStream? = context.contentResolver.openInputStream(imageUri)

            if (inputStream == null) {
                continuation.resumeWithException(Exception("Failed to open image file"))
                return@suspendCancellableCoroutine
            }

            // Get photo timestamp from EXIF data
            val (monthDir, timestamp) = getPhotoTimestamp(context, imageUri)

            // Create S3 key with structure: email_guid/YYYY-MM/YYYY-MM-DD_HH-MM-SS.jpg
            val fileName = "${timestamp}.jpg"
            val s3Key = "${securePrefix}/${monthDir}/${fileName}"

            // Use simple UUID for temp file name
            val tempFileName = "photo_${UUID.randomUUID()}.jpg"
            tempFile = File(context.cacheDir, tempFileName)

            android.util.Log.d("CognitoS3Uploader", "Uploading to S3 path: $s3Key")

            // Copy input stream to file
            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()

            // Start upload without ACL (bucket doesn't support ACLs)
            // Upload to email/photo_uuid.jpg path for organization
            uploadObserver = transferUtility.upload(
                bucketName,
                s3Key,
                tempFile
            )

            // Handle cancellation
            continuation.invokeOnCancellation {
                android.util.Log.d("CognitoS3Uploader", "Upload cancelled, cleaning up...")
                if (!isResumed) {
                    uploadObserver?.let { observer ->
                        try {
                            transferUtility.cancel(observer.id)
                        } catch (e: Exception) {
                            android.util.Log.e("CognitoS3Uploader", "Error cancelling upload", e)
                        }
                    }
                    tempFile?.delete()
                }
            }

            // Listen to upload progress
            uploadObserver?.setTransferListener(object : TransferListener {
                override fun onStateChanged(id: Int, state: TransferState?) {
                    if (isResumed) return

                    when (state) {
                        TransferState.COMPLETED -> {
                            // Delete temp file
                            tempFile?.delete()

                            // Generate public URL with email prefix
                            val publicUrl = "https://${bucketName}.s3.${region.getName()}.amazonaws.com/${s3Key}"
                            isResumed = true
                            continuation.resume(publicUrl)
                        }
                        TransferState.FAILED -> {
                            tempFile?.delete()
                            isResumed = true
                            continuation.resumeWithException(
                                Exception("Upload failed")
                            )
                        }
                        TransferState.CANCELED -> {
                            tempFile?.delete()
                            isResumed = true
                            continuation.resumeWithException(
                                CancellationException("Upload canceled")
                            )
                        }
                        else -> {
                            // In progress, waiting, etc.
                        }
                    }
                }

                override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                    val percentDone = ((bytesCurrent.toFloat() / bytesTotal.toFloat()) * 100).toInt()
                    // You could emit progress updates here if needed
                }

                override fun onError(id: Int, ex: Exception?) {
                    if (isResumed) return

                    tempFile?.delete()
                    isResumed = true
                    continuation.resumeWithException(
                        ex ?: Exception("Unknown upload error")
                    )
                }
            })

        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
}
