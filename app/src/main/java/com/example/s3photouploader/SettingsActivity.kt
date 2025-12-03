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

        checkPermissionAndLoadStats()
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

    data class PhotoStats(val count: Int, val totalSize: Long)
}
