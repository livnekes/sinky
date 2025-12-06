package com.example.s3photouploader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.s3photouploader.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Permission launcher for reading media
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            loadMediaStats()
        } else {
            Toast.makeText(
                this,
                "Permission denied. Cannot access media statistics.",
                Toast.LENGTH_SHORT
            ).show()
            binding.photoCountText.text = "Permission required"
            binding.storageUsedText.text = "Permission required"
            binding.videoCountText.text = "Permission required"
            binding.videoStorageUsedText.text = "Permission required"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        // Display Google account email
        val googleAccount = GoogleSignInHelper.getSignedInAccount(this)
        binding.accountEmailText.text = googleAccount?.email ?: "Not signed in"

        // Set up Disconnect button
        binding.disconnectButton.setOnClickListener {
            handleDisconnect()
        }

        // Set up delete after upload switch
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        binding.deleteAfterUploadSwitch.isChecked = prefs.getBoolean("delete_after_upload", false)
        binding.deleteAfterUploadSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("delete_after_upload", isChecked).apply()
        }

        // Set up refresh cloud stats button
        binding.refreshCloudStatsButton.setOnClickListener {
            loadCloudStats()
        }

        // Set up refresh Google stats button
        binding.refreshGoogleStatsButton.setOnClickListener {
            loadGoogleStats()
        }

        checkPermissionAndLoadStats()
        loadCloudStats()
        loadGoogleStats()
    }

    private fun handleDisconnect() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Disconnect Account")
            .setMessage("Are you sure you want to disconnect your Google account? You will need to sign in again to use the app.")
            .setPositiveButton("Disconnect") { _, _ ->
                GoogleSignInHelper.signOut(this) {
                    // Clear Cognito credentials
                    CognitoAuthManager.signOut()

                    // Clear saved data
                    AccountHelper.clearUserData(this)

                    Toast.makeText(this, "Disconnected successfully", Toast.LENGTH_SHORT).show()

                    // Go back to MainActivity which will prompt for sign-in
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()

        // Check if we should refresh cloud stats after an upload
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val shouldRefresh = prefs.getBoolean("should_refresh_cloud_stats", false)

        if (shouldRefresh) {
            // Clear the flag
            prefs.edit().putBoolean("should_refresh_cloud_stats", false).apply()
            // Refresh stats
            loadCloudStats()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun checkPermissionAndLoadStats() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        when {
            allGranted -> {
                loadMediaStats()
            }
            else -> {
                permissionLauncher.launch(permissions)
            }
        }
    }

    private fun loadMediaStats() {
        binding.photoCountText.text = "Loading..."
        binding.storageUsedText.text = "Loading..."
        binding.videoCountText.text = "Loading..."
        binding.videoStorageUsedText.text = "Loading..."

        lifecycleScope.launch {
            try {
                val photoStats = getPhotoStats()
                binding.photoCountText.text = "${photoStats.count} photos"
                binding.storageUsedText.text = formatBytes(photoStats.totalSize)

                val videoStats = getVideoStats()
                binding.videoCountText.text = "${videoStats.count} videos"
                binding.videoStorageUsedText.text = formatBytes(videoStats.totalSize)
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Error loading media stats", e)
                binding.photoCountText.text = "Error loading stats"
                binding.storageUsedText.text = "Error"
                binding.videoCountText.text = "Error loading stats"
                binding.videoStorageUsedText.text = "Error"
                Toast.makeText(
                    this@SettingsActivity,
                    "Error loading media statistics: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private suspend fun getPhotoStats(): PhotoStats = withContext(Dispatchers.IO) {
        var count = 0
        var totalSize = 0L

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.SIZE
        )

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                count++
                val size = cursor.getLong(sizeColumn)
                totalSize += size
            }
        }

        android.util.Log.d("SettingsActivity", "Found $count photos, total size: $totalSize bytes")
        return@withContext PhotoStats(count, totalSize)
    }

    private suspend fun getVideoStats(): PhotoStats = withContext(Dispatchers.IO) {
        var count = 0
        var totalSize = 0L

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.SIZE
        )

        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                count++
                val size = cursor.getLong(sizeColumn)
                totalSize += size
            }
        }

        android.util.Log.d("SettingsActivity", "Found $count videos, total size: $totalSize bytes")
        return@withContext PhotoStats(count, totalSize)
    }

    private fun loadCloudStats() {
        binding.cloudPhotoCountText.text = "Loading..."
        binding.cloudStorageUsedText.text = "Loading..."
        binding.refreshCloudStatsButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val securePrefix = AccountHelper.getSecureUploadPrefix(this@SettingsActivity)
                if (securePrefix == null) {
                    binding.cloudPhotoCountText.text = "Account not set"
                    binding.cloudStorageUsedText.text = "N/A"
                    binding.refreshCloudStatsButton.isEnabled = true
                    return@launch
                }

                val lambdaEndpoint = getString(R.string.lambda_stats_endpoint)

                val stats = withContext(Dispatchers.IO) {
                    fetchCloudStatsFromLambda(lambdaEndpoint, securePrefix)
                }

                binding.cloudPhotoCountText.text = "${stats.objectCount} items"
                binding.cloudStorageUsedText.text = formatBytes(stats.totalSize)
                binding.refreshCloudStatsButton.isEnabled = true

            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Error loading cloud stats", e)
                binding.cloudPhotoCountText.text = "Error loading"
                binding.cloudStorageUsedText.text = "Error"
                binding.refreshCloudStatsButton.isEnabled = true
                Toast.makeText(
                    this@SettingsActivity,
                    "Error loading cloud statistics: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Calls Lambda function to get cloud storage statistics
     *
     * Expected Lambda request:
     * POST /stats
     * Content-Type: application/json
     * Body: {"prefix": "email_cognitoId", "email": "user@example.com"}
     *
     * Expected Lambda response:
     * {
     *   "objectCount": 123,
     *   "totalSize": 1234567890
     * }
     */
    private fun fetchCloudStatsFromLambda(endpoint: String, prefix: String): CloudStorageStats {
        // Get user email for verification
        val userEmail = AccountHelper.getUserEmail(this)
            ?: throw Exception("User email not found")

        // Create JSON request body
        val requestBody = JSONObject().apply {
            put("prefix", prefix)
            put("email", userEmail)
        }.toString()

        android.util.Log.d("SettingsActivity", "Calling Lambda endpoint: $endpoint with prefix: $prefix, email: $userEmail")

        // Get Cognito credentials provider for signing
        val credentialsProvider = CognitoAuthManager.getCredentialsProvider()
            ?: throw Exception("Not authenticated with Cognito")

        // Refresh credentials before making the request
        CognitoAuthManager.refreshCredentialsIfNeeded()

        // Get AWS region
        val region = getString(R.string.aws_region)

        // Create signed HTTP request with IAM auth
        val request = AwsSignedRequestHelper.createSignedPostRequest(
            url = endpoint,
            jsonBody = requestBody,
            credentialsProvider = credentialsProvider,
            region = region
        )

        android.util.Log.d("SettingsActivity", "Request signed with AWS Signature V4")

        // Execute request
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Lambda returned error: ${response.code} ${response.message}")
            }

            val responseBody = response.body?.string()
                ?: throw Exception("Empty response from Lambda")

            android.util.Log.d("SettingsActivity", "Lambda response: $responseBody")

            // Parse JSON response
            val json = JSONObject(responseBody)
            val objectCount = json.getInt("objectCount")
            val totalSize = json.getLong("totalSize")

            return CloudStorageStats(objectCount, totalSize)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"

        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0

        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }

        val df = DecimalFormat("#.##")
        return "${df.format(size)} ${units[unitIndex]}"
    }

    private fun loadGoogleStats() {
        if (!GoogleSignInHelper.isSignedIn(this)) {
            binding.googleTotalStorageText.text = "Not signed in"
            binding.googleUsedStorageText.text = "Not signed in"
            binding.googleAvailableStorageText.text = "Not signed in"
            binding.refreshGoogleStatsButton.isEnabled = true
            return
        }

        binding.googleTotalStorageText.text = "Loading..."
        binding.googleUsedStorageText.text = "Loading..."
        binding.googleAvailableStorageText.text = "Loading..."
        binding.refreshGoogleStatsButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val storageInfo = GooglePhotosStorageHelper.getStorageInfo(this@SettingsActivity)

                if (storageInfo != null) {
                    binding.googleTotalStorageText.text = formatBytes(storageInfo.totalBytes)
                    binding.googleUsedStorageText.text = "${formatBytes(storageInfo.usedBytes)} (${String.format("%.1f", storageInfo.getUsedPercentage())}%)"
                    binding.googleAvailableStorageText.text = formatBytes(storageInfo.getAvailableBytes())
                } else {
                    binding.googleTotalStorageText.text = "Error loading"
                    binding.googleUsedStorageText.text = "Error loading"
                    binding.googleAvailableStorageText.text = "Error loading"
                    Toast.makeText(
                        this@SettingsActivity,
                        "Failed to load Google storage information",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                binding.refreshGoogleStatsButton.isEnabled = true
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Error loading Google stats", e)
                binding.googleTotalStorageText.text = "Error loading"
                binding.googleUsedStorageText.text = "Error loading"
                binding.googleAvailableStorageText.text = "Error loading"
                binding.refreshGoogleStatsButton.isEnabled = true
                Toast.makeText(
                    this@SettingsActivity,
                    "Error loading Google statistics: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    data class PhotoStats(val count: Int, val totalSize: Long)
    data class CloudStorageStats(val objectCount: Int, val totalSize: Long)
}
