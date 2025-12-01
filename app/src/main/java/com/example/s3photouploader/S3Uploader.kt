package com.example.s3photouploader

import android.content.Context
import android.net.Uri
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.regions.Region
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.CannedAccessControlList
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class S3Uploader {

    suspend fun uploadImage(
        context: Context,
        imageUri: Uri,
        bucketName: String,
        region: String,
        accessKey: String,
        secretKey: String
    ): String = suspendCoroutine { continuation ->
        try {
            // Create AWS credentials
            val credentials = BasicAWSCredentials(accessKey, secretKey)

            // Create S3 client
            val s3Client = AmazonS3Client(credentials, Region.getRegion(region))

            // Create transfer utility
            val transferUtility = TransferUtility.builder()
                .context(context)
                .s3Client(s3Client)
                .build()

            // Get input stream from URI
            val inputStream: InputStream? = context.contentResolver.openInputStream(imageUri)

            if (inputStream == null) {
                continuation.resumeWithException(Exception("Failed to open image file"))
                return@suspendCoroutine
            }

            // Create a temporary file
            val fileName = "photo_${UUID.randomUUID()}.jpg"
            val tempFile = File(context.cacheDir, fileName)

            // Copy input stream to file
            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()

            // Start upload with public-read ACL
            val uploadObserver = transferUtility.upload(
                bucketName,
                fileName,
                tempFile,
                CannedAccessControlList.PublicRead
            )

            // Listen to upload progress
            uploadObserver.setTransferListener(object : TransferListener {
                override fun onStateChanged(id: Int, state: TransferState?) {
                    when (state) {
                        TransferState.COMPLETED -> {
                            // Delete temp file
                            tempFile.delete()

                            // Generate public URL
                            val publicUrl = "https://${bucketName}.s3.${region}.amazonaws.com/${fileName}"
                            continuation.resume(publicUrl)
                        }
                        TransferState.FAILED -> {
                            tempFile.delete()
                            continuation.resumeWithException(
                                Exception("Upload failed")
                            )
                        }
                        TransferState.CANCELED -> {
                            tempFile.delete()
                            continuation.resumeWithException(
                                Exception("Upload canceled")
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
                    tempFile.delete()
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
