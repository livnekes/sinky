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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    // Permission launcher for reading photos
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadPhotoStats()
        } else {
            Toast.makeText(
                this,
                "Permission denied. Cannot access photo statistics.",
                Toast.LENGTH_SHORT
            ).show()
            binding.photoCountText.text = "Permission required"
            binding.storageUsedText.text = "Permission required"
        }
    }

    // Google Sign-In launcher for Drive API access
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.let { data ->
                handleGoogleSignInResult(data)
            }
        } else {
            Toast.makeText(
                this,
                "Google Sign-In failed. Cannot access storage info.",
                Toast.LENGTH_SHORT
            ).show()
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

        checkPermissionAndLoadStats()

        // Set up Google Storage button
        binding.refreshGoogleStorageButton.setOnClickListener {
            requestGoogleStorageInfo()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun checkPermissionAndLoadStats() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                loadPhotoStats()
            }
            else -> {
                permissionLauncher.launch(permission)
            }
        }
    }

    private fun loadPhotoStats() {
        binding.photoCountText.text = "Loading..."
        binding.storageUsedText.text = "Loading..."

        lifecycleScope.launch {
            try {
                val stats = getPhotoStats()
                binding.photoCountText.text = "${stats.count} photos"
                binding.storageUsedText.text = formatBytes(stats.totalSize)
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Error loading photo stats", e)
                binding.photoCountText.text = "Error loading stats"
                binding.storageUsedText.text = "Error"
                Toast.makeText(
                    this@SettingsActivity,
                    "Error loading photo statistics: ${e.message}",
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

    private fun requestGoogleStorageInfo() {
        binding.googleStorageUsedText.text = "Loading..."
        binding.googleStorageTotalText.text = "Loading..."

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_READONLY))
            .build()

        val googleSignInClient = GoogleSignIn.getClient(this, gso)
        googleSignInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun handleGoogleSignInResult(data: Intent) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.result
            if (account != null) {
                queryGoogleDriveStorage(account)
            } else {
                binding.googleStorageUsedText.text = "Sign-in failed"
                binding.googleStorageTotalText.text = "Sign-in failed"
                Toast.makeText(this, "Failed to sign in to Google", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("SettingsActivity", "Google Sign-In error", e)
            binding.googleStorageUsedText.text = "Error"
            binding.googleStorageTotalText.text = "Error"
            Toast.makeText(this, "Error signing in: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryGoogleDriveStorage(account: GoogleSignInAccount) {
        lifecycleScope.launch {
            try {
                val storageInfo = withContext(Dispatchers.IO) {
                    // Set up credentials
                    val credential = GoogleAccountCredential.usingOAuth2(
                        this@SettingsActivity,
                        listOf(DriveScopes.DRIVE_READONLY)
                    )
                    credential.selectedAccount = account.account

                    // Build Drive service
                    val driveService = Drive.Builder(
                        AndroidHttp.newCompatibleTransport(),
                        GsonFactory.getDefaultInstance(),
                        credential
                    )
                        .setApplicationName(getString(R.string.app_name))
                        .build()

                    // Query storage quota
                    val about = driveService.about().get()
                        .setFields("storageQuota")
                        .execute()

                    val quota = about.storageQuota
                    val usage = quota?.usage ?: 0L
                    val limit = quota?.limit ?: 0L

                    Pair(usage, limit)
                }

                // Update UI with storage info
                binding.googleStorageUsedText.text = formatBytes(storageInfo.first)
                binding.googleStorageTotalText.text = formatBytes(storageInfo.second)

            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Error querying Google storage", e)
                binding.googleStorageUsedText.text = "Error"
                binding.googleStorageTotalText.text = "Error"
                Toast.makeText(
                    this@SettingsActivity,
                    "Error loading Google storage: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    data class PhotoStats(val count: Int, val totalSize: Long)
}
