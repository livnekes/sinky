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

        // Display account email
        val userEmail = AccountHelper.getUserEmail(this)
        binding.accountEmailText.text = userEmail ?: "Not set"

        // Set up Change Account button
        binding.changeAccountButton.setOnClickListener {
            showAccountPicker()
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

        checkPermissionAndLoadStats()
        loadCloudStats()
    }

    private fun showAccountPicker() {
        try {
            val intent = android.accounts.AccountManager.newChooseAccountIntent(
                null, // selectedAccount
                null, // allowableAccounts
                arrayOf("com.google"), // allowableAccountTypes - Google accounts only
                null, // descriptionOverrideText
                null, // addAccountAuthTokenType
                null, // addAccountRequiredFeatures
                null  // addAccountOptions
            )
            startActivityForResult(intent, REQUEST_ACCOUNT_PICKER)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open account picker: ${e.message}", Toast.LENGTH_SHORT).show()
            android.util.Log.e("SettingsActivity", "Failed to open account picker", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ACCOUNT_PICKER && resultCode == RESULT_OK) {
            data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)?.let { email ->
                AccountHelper.saveUserEmail(this, email)
                binding.accountEmailText.text = email
                Toast.makeText(this, "Account changed to: $email", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val REQUEST_ACCOUNT_PICKER = 1001
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

                // Check if endpoint is configured
                if (lambdaEndpoint.contains("your-lambda-url")) {
                    binding.cloudPhotoCountText.text = "Not configured"
                    binding.cloudStorageUsedText.text = "N/A"
                    binding.refreshCloudStatsButton.isEnabled = true
                    Toast.makeText(
                        this@SettingsActivity,
                        "Please configure lambda_stats_endpoint in strings.xml",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

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
     * Body: {"prefix": "email_guid"}
     *
     * Expected Lambda response:
     * {
     *   "objectCount": 123,
     *   "totalSize": 1234567890
     * }
     */
    private fun fetchCloudStatsFromLambda(endpoint: String, prefix: String): CloudStorageStats {
        // Create JSON request body
        val requestBody = JSONObject().apply {
            put("prefix", prefix)
        }.toString()

        android.util.Log.d("SettingsActivity", "Calling Lambda endpoint: $endpoint with prefix: $prefix")

        // Create HTTP request
        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

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

    data class PhotoStats(val count: Int, val totalSize: Long)
    data class CloudStorageStats(val objectCount: Int, val totalSize: Long)
}
