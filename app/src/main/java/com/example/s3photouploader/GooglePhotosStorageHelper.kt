package com.example.s3photouploader

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper class for fetching Google Photos/Drive storage information
 */
object GooglePhotosStorageHelper {

    /**
     * Get storage information from Google Drive
     * Returns a StorageInfo object with total and used storage
     */
    suspend fun getStorageInfo(context: Context): StorageInfo? = withContext(Dispatchers.IO) {
        try {
            val account = GoogleSignInHelper.getSignedInAccount(context)
                ?: return@withContext null

            // Get OAuth token
            val token = GoogleAuthUtil.getToken(
                context,
                account.account!!,
                "oauth2:https://www.googleapis.com/auth/drive.readonly"
            )

            // Create credentials
            val credential = GoogleCredential().setAccessToken(token)

            // Build Drive service
            val driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            )
                .setApplicationName(context.getString(R.string.app_name))
                .build()

            // Get storage quota from Drive API
            val about = driveService.about().get()
                .setFields("storageQuota")
                .execute()

            val storageQuota = about.storageQuota
            if (storageQuota != null) {
                return@withContext StorageInfo(
                    totalBytes = storageQuota.limit ?: 0L,
                    usedBytes = storageQuota.usage ?: 0L,
                    usedInDrive = storageQuota.usageInDrive ?: 0L,
                    usedInPhotos = storageQuota.usageInDriveTrash ?: 0L // Approximation
                )
            }

            return@withContext null
        } catch (e: Exception) {
            android.util.Log.e("GooglePhotosStorageHelper", "Error fetching storage info", e)
            return@withContext null
        }
    }

    /**
     * Data class representing storage information
     */
    data class StorageInfo(
        val totalBytes: Long,
        val usedBytes: Long,
        val usedInDrive: Long,
        val usedInPhotos: Long
    ) {
        fun getUsedPercentage(): Double {
            return if (totalBytes > 0) {
                (usedBytes.toDouble() / totalBytes.toDouble()) * 100.0
            } else {
                0.0
            }
        }

        fun getAvailableBytes(): Long {
            return totalBytes - usedBytes
        }
    }
}
