package com.example.s3photouploader

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.amazonaws.regions.Regions
import com.example.s3photouploader.databinding.ActivityMainBinding
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val selectedImageUris = mutableListOf<Uri>()
    private val failedImageUris = mutableListOf<Uri>()
    private lateinit var s3Uploader: CognitoS3Uploader
    private var uploadJob: Job? = null
    private var isSignInDialogShowing = false

    // Date range for backup mode
    private var startDateMillis: Long? = null
    private var endDateMillis: Long? = null
    private var isDateRangeMode = false
    private val dateRangePhotos = mutableListOf<Uri>()

    // For batch deletion after upload
    private var photosToDelete = mutableListOf<Uri>()

    // Delete request launcher for Android 10+
    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        android.util.Log.d("MainActivity", "Delete request result code: ${result.resultCode}, RESULT_OK=$RESULT_OK")

        if (result.resultCode == RESULT_OK) {
            val deletedCount = photosToDelete.size
            Toast.makeText(
                this,
                "System confirmed deletion of $deletedCount item(s)",
                Toast.LENGTH_LONG
            ).show()
            android.util.Log.d("MainActivity", "Successfully deleted $deletedCount items: ${photosToDelete.joinToString()}")
            photosToDelete.clear()
        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            Toast.makeText(
                this,
                "Deletion was cancelled by user",
                Toast.LENGTH_LONG
            ).show()
            android.util.Log.w("MainActivity", "Deletion cancelled by user (RESULT_CANCELED)")
            photosToDelete.clear()
        } else {
            Toast.makeText(
                this,
                "Deletion failed - result code: ${result.resultCode}",
                Toast.LENGTH_LONG
            ).show()
            android.util.Log.e("MainActivity", "Deletion failed with result code: ${result.resultCode}")
            photosToDelete.clear()
        }
    }

    // Image picker launcher for multiple selection
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            selectedImageUris.clear()

            result.data?.let { data ->
                // Handle multiple selection
                if (data.clipData != null) {
                    val count = data.clipData!!.itemCount
                    for (i in 0 until count) {
                        val uri = data.clipData!!.getItemAt(i).uri
                        selectedImageUris.add(uri)
                    }
                } else if (data.data != null) {
                    // Single selection
                    selectedImageUris.add(data.data!!)
                }
            }

            if (selectedImageUris.isNotEmpty()) {
                // Show first image as preview
                binding.imagePreview.setImageURI(selectedImageUris[0])
                binding.uploadButton.isEnabled = true
                binding.statusTextView.text = "${selectedImageUris.size} item(s) selected. Ready to upload."
            }
        }
    }

    // Permission launcher for images (when selecting photos)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openImagePicker()
        } else {
            Toast.makeText(
                this,
                "Permission denied. Cannot access media.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Permission launcher for startup
    private val startupPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(
                this,
                "Media access permissions are required to use this app.",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    // Google Sign-In launcher
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        android.util.Log.d("MainActivity", "Sign-In result code: ${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                android.util.Log.d("MainActivity", "Google Sign-In successful: ${account.email}")

                // Get Google ID token
                val idToken = account.idToken
                android.util.Log.d("MainActivity", "ID token retrieved: ${if (idToken != null) "Yes (${idToken.length} chars)" else "No"}")

                if (idToken != null) {
                    // Authenticate with Cognito using Google ID token
                    lifecycleScope.launch {
                        try {
                            val identityPoolId = getString(R.string.aws_identity_pool_id)
                            val regionStr = getString(R.string.aws_region)
                            val region = Regions.fromName(regionStr)

                            // Exchange Google token for Cognito credentials
                            withContext(Dispatchers.IO) {
                                CognitoAuthManager.authenticateWithGoogle(
                                    this@MainActivity,
                                    idToken,
                                    identityPoolId,
                                    region
                                )
                            }

                            android.util.Log.d("MainActivity", "Successfully authenticated with Cognito")

                            // Save email and set S3 prefix to email
                            account.email?.let { email ->
                                AccountHelper.saveUserEmail(this@MainActivity, email)
                                // Use email directly as S3 prefix
                                AccountHelper.saveS3Prefix(this@MainActivity, email)
                                android.util.Log.d("MainActivity", "S3 prefix set to: $email")
                            }

                            // Enable UI after successful sign-in
                            enableUI()

                            Toast.makeText(
                                this@MainActivity,
                                "Signed in as ${account.email}",
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Failed to authenticate with Cognito", e)
                            Toast.makeText(
                                this@MainActivity,
                                "Authentication failed: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    android.util.Log.e("MainActivity", "ID token is null")
                    Toast.makeText(
                        this,
                        "Failed to get authentication token",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: ApiException) {
                android.util.Log.e("MainActivity", "Google Sign-In ApiException - Status Code: ${e.statusCode}", e)
                val errorMessage = when (e.statusCode) {
                    10 -> "Developer error - Check OAuth configuration"
                    12500 -> "Sign-In cancelled"
                    12501 -> "Sign-In failed"
                    else -> "Error code: ${e.statusCode}"
                }
                Toast.makeText(
                    this,
                    "Google Sign-In failed: $errorMessage",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else if (result.resultCode == RESULT_CANCELED) {
            android.util.Log.w("MainActivity", "Google Sign-In cancelled by user")
            Toast.makeText(this, "Sign-In cancelled", Toast.LENGTH_SHORT).show()
        } else {
            android.util.Log.e("MainActivity", "Google Sign-In unknown result code: ${result.resultCode}")
            Toast.makeText(this, "Sign-In error: result code ${result.resultCode}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        s3Uploader = CognitoS3Uploader()

        setupListeners()
        requestPermissionsIfNeeded()
        promptForGoogleSignInIfNeeded()
        updateStatusWithAccount()
    }

    override fun onResume() {
        super.onResume()
        // Re-check sign-in status when returning from other activities (e.g., Settings after logout)
        if (!GoogleSignInHelper.isSignedIn(this)) {
            // User signed out, disable UI and show login dialog
            disableUI()
            promptForGoogleSignInIfNeeded()
        }
    }

    private fun promptForGoogleSignInIfNeeded() {
        if (!GoogleSignInHelper.isSignedIn(this)) {
            // Disable all UI until signed in
            disableUI()

            // Show mandatory Google Sign-In dialog (only if not already showing)
            if (!isSignInDialogShowing) {
                isSignInDialogShowing = true
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Sign in Required")
                    .setMessage("You must sign in with Google to use this app.")
                    .setPositiveButton("Sign In") { _, _ ->
                        isSignInDialogShowing = false
                        startGoogleSignIn()
                    }
                    .setOnDismissListener {
                        isSignInDialogShowing = false
                    }
                    .setCancelable(false)
                    .show()
            }
        } else {
            // User is signed in, restore Cognito session if needed
            restoreCognitoSession()
            // Enable UI
            enableUI()
        }
    }

    private fun restoreCognitoSession() {
        // Check if we have a saved Cognito identity
        val savedIdentityId = AccountHelper.getCognitoIdentityId(this)
        if (savedIdentityId != null) {
            android.util.Log.d("MainActivity", "Restoring Cognito session with identity: $savedIdentityId")

            // Get Google account to refresh ID token
            val googleAccount = GoogleSignInHelper.getSignedInAccount(this)
            android.util.Log.d("MainActivity", "Google account found: ${googleAccount?.email}")

            val idToken = googleAccount?.idToken
            android.util.Log.d("MainActivity", "ID token for restoration: ${if (idToken != null) "Yes (${idToken.length} chars)" else "No"}")

            if (idToken != null) {
                lifecycleScope.launch {
                    try {
                        val identityPoolId = getString(R.string.aws_identity_pool_id)
                        val regionStr = getString(R.string.aws_region)
                        val region = Regions.fromName(regionStr)

                        // Re-authenticate with Cognito
                        withContext(Dispatchers.IO) {
                            CognitoAuthManager.authenticateWithGoogle(
                                this@MainActivity,
                                idToken,
                                identityPoolId,
                                region
                            )
                        }

                        android.util.Log.d("MainActivity", "Cognito session restored successfully")
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Failed to restore Cognito session", e)
                    }
                }
            } else {
                android.util.Log.w("MainActivity", "Cannot restore Cognito session: No ID token available")
            }
        } else {
            android.util.Log.d("MainActivity", "No saved Cognito identity to restore")
        }
    }

    private fun disableUI() {
        binding.selectPhotoButton.isEnabled = false
        binding.uploadButton.isEnabled = false
        binding.retryButton.isEnabled = false
        binding.modeToggleGroup.isEnabled = false
        binding.manualModeButton.isEnabled = false
        binding.dateRangeModeButton.isEnabled = false
        binding.startDateButton.isEnabled = false
        binding.endDateButton.isEnabled = false
        binding.statusTextView.text = "Please sign in with Google to continue"
    }

    private fun enableUI() {
        binding.modeToggleGroup.isEnabled = true
        binding.manualModeButton.isEnabled = true
        binding.dateRangeModeButton.isEnabled = true
        // Other buttons will be enabled based on selection state
        updateUIForMode()
    }

    private fun startGoogleSignIn() {
        val signInIntent = GoogleSignInHelper.getGoogleSignInClient(this).signInIntent
        googleSignInLauncher.launch(signInIntent)
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        when {
            !allGranted -> {
                startupPermissionLauncher.launch(permissions)
            }
        }
    }

    private fun updateStatusWithAccount() {
        val googleAccount = GoogleSignInHelper.getSignedInAccount(this)
        if (googleAccount != null) {
            binding.statusTextView.text = "Uploading to: ${googleAccount.email}"
        }
    }


    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupListeners() {
        // Mode toggle listener
        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.manualModeButton -> {
                        isDateRangeMode = false
                        updateUIForMode()
                    }
                    R.id.dateRangeModeButton -> {
                        isDateRangeMode = true
                        updateUIForMode()
                    }
                }
            }
        }

        binding.selectPhotoButton.setOnClickListener {
            checkPermissionAndPickImage()
        }

        binding.startDateButton.setOnClickListener {
            showDatePicker(isStartDate = true)
        }

        binding.endDateButton.setOnClickListener {
            showDatePicker(isStartDate = false)
        }

        binding.uploadButton.setOnClickListener {
            // Check WiFi and prompt if needed
            if (!isWifiConnected()) {
                showCellularDataConfirmation {
                    if (isDateRangeMode) {
                        startDateRangeUpload()
                    } else {
                        uploadImages(selectedImageUris)
                    }
                }
            } else {
                if (isDateRangeMode) {
                    startDateRangeUpload()
                } else {
                    uploadImages(selectedImageUris)
                }
            }
        }

        binding.retryButton.setOnClickListener {
            // Check WiFi and prompt if needed
            if (!isWifiConnected()) {
                showCellularDataConfirmation {
                    uploadImages(failedImageUris)
                }
            } else {
                uploadImages(failedImageUris)
            }
        }

        binding.cancelButton.setOnClickListener {
            cancelUpload()
        }
    }

    private fun updateUIForMode() {
        if (isDateRangeMode) {
            // Date range mode
            binding.selectPhotoButton.visibility = View.GONE
            binding.datePickerSection.visibility = View.VISIBLE
            binding.imagePreview.visibility = View.VISIBLE
            binding.imagePreview.setImageResource(R.drawable.ic_image_placeholder)
            binding.imagePreview.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE

            // Check if dates are selected and photos are queried
            if (startDateMillis != null && endDateMillis != null && dateRangePhotos.isNotEmpty()) {
                // Show first image as preview
                binding.imagePreview.setImageURI(dateRangePhotos[0])
                binding.imagePreview.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP

                binding.uploadButton.isEnabled = true
                binding.uploadButton.text = "Start Backup (${dateRangePhotos.size} items)"
                binding.statusTextView.text = "Found ${dateRangePhotos.size} item(s). Ready to backup."
            } else {
                binding.uploadButton.isEnabled = false
                binding.uploadButton.text = "Start Backup"
                binding.statusTextView.text = "Select date range to backup photos/videos"
            }
        } else {
            // Manual mode - clear date range photos
            dateRangePhotos.clear()

            binding.selectPhotoButton.visibility = View.VISIBLE
            binding.datePickerSection.visibility = View.GONE
            binding.imagePreview.visibility = View.VISIBLE

            binding.uploadButton.isEnabled = selectedImageUris.isNotEmpty()
            binding.uploadButton.text = "Upload Media"

            if (selectedImageUris.isEmpty()) {
                binding.imagePreview.setImageResource(R.drawable.ic_image_placeholder)
                binding.imagePreview.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                binding.statusTextView.text = "Select photos/videos to upload"
            } else {
                binding.statusTextView.text = "${selectedImageUris.size} item(s) selected. Ready to upload."
            }
        }
    }

    private fun showDatePicker(isStartDate: Boolean) {
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(if (isStartDate) "Select Start Date" else "Select End Date")
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .build()

        datePicker.addOnPositiveButtonClickListener { selection ->
            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
            val date = Date(selection)
            val dateString = dateFormat.format(date)

            if (isStartDate) {
                startDateMillis = selection
                binding.startDateButton.text = dateString
            } else {
                endDateMillis = selection
                binding.endDateButton.text = dateString
            }

            // If both dates are selected, query photos
            if (startDateMillis != null && endDateMillis != null) {
                queryPhotosForDateRange()
            }
        }

        datePicker.show(supportFragmentManager, "DATE_PICKER")
    }

    private fun queryPhotosForDateRange() {
        val start = startDateMillis
        val end = endDateMillis

        if (start == null || end == null) {
            return
        }

        if (start > end) {
            Toast.makeText(this, "Start date must be before end date", Toast.LENGTH_SHORT).show()
            binding.uploadButton.isEnabled = false
            binding.uploadButton.text = "Start Backup"
            return
        }

        // Query photos in background
        binding.uploadButton.isEnabled = false
        binding.uploadButton.text = "Finding media..."
        binding.statusTextView.text = "Finding media..."

        lifecycleScope.launch {
            try {
                val photoUris = queryPhotosByDateRange(start, end)

                dateRangePhotos.clear()
                dateRangePhotos.addAll(photoUris)

                if (photoUris.isEmpty()) {
                    binding.imagePreview.setImageResource(R.drawable.ic_image_placeholder)
                    binding.imagePreview.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    binding.statusTextView.text = "No photos/videos found in date range"
                    binding.uploadButton.isEnabled = false
                    binding.uploadButton.text = "Start Backup (0 items)"
                } else {
                    // Show first image as preview
                    binding.imagePreview.setImageURI(photoUris[0])
                    binding.imagePreview.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP

                    binding.statusTextView.text = "Found ${photoUris.size} item(s). Ready to backup."
                    binding.uploadButton.isEnabled = true
                    binding.uploadButton.text = "Start Backup (${photoUris.size} items)"
                }

            } catch (e: Exception) {
                binding.statusTextView.text = "Error finding media: ${e.message}"
                binding.uploadButton.isEnabled = false
                binding.uploadButton.text = "Start Backup"
                Toast.makeText(
                    this@MainActivity,
                    "Error finding media: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
                android.util.Log.e("MainActivity", "Error querying media", e)
            }
        }
    }

    private fun startDateRangeUpload() {
        // Check if we have photos to upload
        if (dateRangePhotos.isEmpty()) {
            Toast.makeText(this, "No media to backup", Toast.LENGTH_SHORT).show()
            return
        }

        // Check if account is set
        val userEmail = AccountHelper.getUserEmail(this)
        if (userEmail == null) {
            Toast.makeText(
                this,
                "Please select an account first. Go to menu → Change Account",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Add photos to selectedImageUris and upload
        selectedImageUris.clear()
        selectedImageUris.addAll(dateRangePhotos)
        uploadImages(selectedImageUris)
    }

    private suspend fun queryPhotosByDateRange(startMillis: Long, endMillis: Long): List<Uri> = withContext(Dispatchers.IO) {
        val mediaUris = mutableListOf<Uri>()

        // Query photos
        val photoProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN
        )

        val photoSelection = "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} <= ?"
        val photoSelectionArgs = arrayOf(
            startMillis.toString(),
            (endMillis + 86400000).toString() // Add 1 day in milliseconds
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} ASC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            photoProjection,
            photoSelection,
            photoSelectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val contentUri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
                mediaUris.add(contentUri)
            }
        }

        // Query videos
        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_TAKEN
        )

        val videoSelection = "${MediaStore.Video.Media.DATE_TAKEN} >= ? AND ${MediaStore.Video.Media.DATE_TAKEN} <= ?"
        val videoSelectionArgs = arrayOf(
            startMillis.toString(),
            (endMillis + 86400000).toString() // Add 1 day in milliseconds
        )

        val videoSortOrder = "${MediaStore.Video.Media.DATE_TAKEN} ASC"

        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            videoSelection,
            videoSelectionArgs,
            videoSortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val contentUri = Uri.withAppendedPath(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
                mediaUris.add(contentUri)
            }
        }

        android.util.Log.d("MainActivity", "Found ${mediaUris.size} photos and videos")
        return@withContext mediaUris
    }

    private fun isWifiConnected(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return networkInfo.type == ConnectivityManager.TYPE_WIFI
        }
    }

    private fun showCellularDataConfirmation(onConfirm: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Upload via Cellular Data?")
            .setMessage("You are not connected to WiFi. Uploading photos will use cellular data. Do you want to continue?")
            .setPositiveButton("Continue") { _, _ ->
                onConfirm()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun cancelUpload() {
        uploadJob?.cancel()
        binding.statusTextView.text = "Upload cancelled"
        Toast.makeText(this, "Upload cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun extractMediaStoreUri(uri: Uri): Uri? {
        try {
            val authority = uri.authority
            val uriString = uri.toString()

            // Check if this is a Google Photos content provider URI
            if (authority == "com.google.android.apps.photos.contentprovider") {
                android.util.Log.d("MainActivity", "Detected Google Photos URI, extracting MediaStore URI")

                // Extract the encoded MediaStore URI from the path
                // Pattern: content://com.google.android.apps.photos.contentprovider/.../content%3A%2F%2Fmedia%2F.../...
                // Match the full encoded MediaStore URI (everything from content%3A up to the next path segment)
                val contentMatch = Regex("content%3A%2F%2Fmedia%2F[^/]+%2F[^/]+%2F[^/]+%2F\\d+").find(uriString)
                if (contentMatch != null) {
                    val encodedUri = contentMatch.value
                    val decodedUri = java.net.URLDecoder.decode(encodedUri, "UTF-8")
                    android.util.Log.d("MainActivity", "Extracted and decoded MediaStore URI: $decodedUri")
                    return Uri.parse(decodedUri)
                } else {
                    android.util.Log.w("MainActivity", "Could not extract MediaStore URI pattern from: $uriString")
                }
            }

            // If it's already a MediaStore URI, return it as-is
            if (authority == "media" || authority?.startsWith("com.android.providers.media") == true) {
                return uri
            }

            return null
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error extracting MediaStore URI from $uri", e)
            return null
        }
    }

    private fun validateAndFilterMediaStoreUris(uris: List<Uri>): List<Uri> {
        val validUris = mutableListOf<Uri>()

        for (uri in uris) {
            try {
                android.util.Log.d("MainActivity", "Processing URI: $uri")

                // Try to extract the real MediaStore URI
                val mediaStoreUri = extractMediaStoreUri(uri)

                if (mediaStoreUri == null) {
                    android.util.Log.w("MainActivity", "Could not extract MediaStore URI from: $uri")
                    continue
                }

                android.util.Log.d("MainActivity", "Using MediaStore URI: $mediaStoreUri")

                // Try to query the URI to make sure it's accessible
                contentResolver.query(mediaStoreUri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { cursor ->
                    if (cursor.count > 0) {
                        validUris.add(mediaStoreUri)
                        android.util.Log.d("MainActivity", "URI is valid and accessible: $mediaStoreUri")
                    } else {
                        android.util.Log.w("MainActivity", "URI exists but no data: $mediaStoreUri")
                    }
                } ?: run {
                    android.util.Log.w("MainActivity", "Cannot query URI: $mediaStoreUri")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error validating URI $uri", e)
            }
        }

        android.util.Log.d("MainActivity", "Validated ${validUris.size} of ${uris.size} URIs")
        return validUris
    }

    private fun requestDeletePhotos(urisToDelete: List<Uri>) {
        if (urisToDelete.isEmpty()) {
            android.util.Log.w("MainActivity", "requestDeletePhotos called with empty list")
            return
        }

        android.util.Log.d("MainActivity", "Requesting deletion of ${urisToDelete.size} photos")
        android.util.Log.d("MainActivity", "URIs to delete: ${urisToDelete.joinToString()}")
        android.util.Log.d("MainActivity", "Android SDK: ${Build.VERSION.SDK_INT}")

        // Validate and filter URIs to only include valid MediaStore URIs
        val validUris = validateAndFilterMediaStoreUris(urisToDelete)

        if (validUris.isEmpty()) {
            android.util.Log.e("MainActivity", "No valid MediaStore URIs found for deletion")
            Toast.makeText(
                this,
                "Cannot delete: No valid URIs found (${urisToDelete.size} invalid)",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (validUris.size < urisToDelete.size) {
            android.util.Log.w("MainActivity", "Filtered out ${urisToDelete.size - validUris.size} invalid URIs")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ (API 30+): Use MediaStore delete request
                android.util.Log.d("MainActivity", "Using MediaStore.createDeleteRequest (Android 11+)")
                val deleteRequest = MediaStore.createDeleteRequest(
                    contentResolver,
                    validUris
                )
                android.util.Log.d("MainActivity", "Delete request created, launching intent sender")
                deleteRequestLauncher.launch(
                    IntentSenderRequest.Builder(deleteRequest.intentSender).build()
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 (API 29): Try to delete directly, may fail if we don't own the files
                var successCount = 0
                for (uri in validUris) {
                    try {
                        val deleted = contentResolver.delete(uri, null, null)
                        if (deleted > 0) successCount++
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error deleting $uri: ${e.message}")
                    }
                }
                if (successCount > 0) {
                    Toast.makeText(
                        this,
                        "Deleted $successCount of ${validUris.size} item(s) from device",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "Could not delete items (permission denied)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                // Android 9 and below: Direct deletion should work
                var successCount = 0
                for (uri in validUris) {
                    try {
                        val deleted = contentResolver.delete(uri, null, null)
                        if (deleted > 0) successCount++
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error deleting $uri: ${e.message}")
                    }
                }
                Toast.makeText(
                    this,
                    "Deleted $successCount of ${validUris.size} item(s) from device",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: SecurityException) {
            android.util.Log.e("MainActivity", "SecurityException requesting photo deletion", e)
            Toast.makeText(
                this,
                "Permission denied: Cannot delete items (SecurityException)",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: IllegalArgumentException) {
            android.util.Log.e("MainActivity", "IllegalArgumentException - Invalid URIs", e)
            Toast.makeText(
                this,
                "Error: Invalid URIs for deletion",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Unexpected error requesting photo deletion", e)
            Toast.makeText(
                this,
                "Error deleting items: ${e.javaClass.simpleName} - ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun checkPermissionAndPickImage() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED -> {
                openImagePicker()
            }
            else -> {
                permissionLauncher.launch(permission)
            }
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        val mimeTypes = arrayOf("image/*", "video/*")
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        imagePickerLauncher.launch(intent)
    }

    private fun uploadImages(imagesToUpload: MutableList<Uri>) {
        // Validate inputs
        if (imagesToUpload.isEmpty()) {
            Toast.makeText(this, getString(R.string.select_image_first), Toast.LENGTH_SHORT).show()
            return
        }

        // Check if account is set
        val userEmail = AccountHelper.getUserEmail(this)
        if (userEmail == null) {
            Toast.makeText(
                this,
                "Please select an account first. Go to menu → Change Account",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Get secure upload prefix (email_guid)
        val securePrefix = AccountHelper.getSecureUploadPrefix(this)
        if (securePrefix == null) {
            Toast.makeText(
                this,
                "Error: Could not generate secure upload prefix",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Get AWS config from strings.xml
        val bucketName = getString(R.string.aws_bucket_name)
        val regionStr = getString(R.string.aws_region)
        val identityPoolId = getString(R.string.aws_identity_pool_id)

        // Convert region string to Regions enum
        val region = Regions.fromName(regionStr)

        // Show progress
        binding.progressBar.visibility = View.VISIBLE
        binding.uploadButton.isEnabled = false
        binding.retryButton.isEnabled = false
        binding.retryButton.visibility = View.GONE
        binding.selectPhotoButton.isEnabled = false
        binding.cancelButton.visibility = View.VISIBLE
        binding.cancelButton.isEnabled = true

        val totalImages = imagesToUpload.size
        val uploadedUrls = mutableListOf<String>()
        var uploadedCount = 0
        var skippedCount = 0
        val newFailedUris = mutableListOf<Uri>()
        val successfullyUploadedUris = mutableListOf<Uri>()

        // Check if delete after upload is enabled
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val deleteAfterUpload = prefs.getBoolean("delete_after_upload", false)

        // Upload in background
        uploadJob = lifecycleScope.launch {
            try {
                for ((index, imageUri) in imagesToUpload.withIndex()) {
                    binding.statusTextView.text = "Uploading ${index + 1} of $totalImages item(s) to $userEmail..."

                    try {
                        // Add 2 minute timeout per image upload
                        val result = withTimeout(120000) {
                            withContext(Dispatchers.IO) {
                                s3Uploader.uploadImage(
                                    context = this@MainActivity,
                                    imageUri = imageUri,
                                    bucketName = bucketName,
                                    region = region,
                                    identityPoolId = identityPoolId,
                                    securePrefix = securePrefix
                                )
                            }
                        }

                        uploadedUrls.add(result)

                        // Check if this was actually uploaded or skipped (already exists)
                        if (result.contains("already uploaded")) {
                            skippedCount++
                        } else {
                            uploadedCount++
                            // Collect URI for batch deletion later
                            if (deleteAfterUpload) {
                                successfullyUploadedUris.add(imageUri)
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        // Upload timed out
                        newFailedUris.add(imageUri)
                        android.util.Log.e("MainActivity", "Upload timeout for image", e)
                    } catch (e: Exception) {
                        // Track this failed upload
                        newFailedUris.add(imageUri)
                        android.util.Log.e("MainActivity", "Failed to upload image: ${e.message}", e)
                    }
                }

                // Update failed URIs list
                if (imagesToUpload === failedImageUris) {
                    // We were retrying, clear the old failures and add new ones
                    failedImageUris.clear()
                    failedImageUris.addAll(newFailedUris)
                } else {
                    // First attempt, just set the failures
                    failedImageUris.clear()
                    failedImageUris.addAll(newFailedUris)
                }

                // Hide progress
                binding.progressBar.visibility = View.GONE
                binding.selectPhotoButton.isEnabled = true
                binding.cancelButton.visibility = View.GONE

                // Request batch deletion if enabled and we have photos to delete
                if (deleteAfterUpload && successfullyUploadedUris.isNotEmpty()) {
                    photosToDelete.clear()
                    photosToDelete.addAll(successfullyUploadedUris)
                    requestDeletePhotos(photosToDelete)
                }

                if (newFailedUris.isEmpty()) {
                    // All uploads successful
                    val statusMessage = if (skippedCount > 0) {
                        "Uploaded $uploadedCount, skipped $skippedCount (already uploaded)"
                    } else {
                        "Successfully uploaded $uploadedCount item(s)"
                    }

                    val deleteMessage = if (deleteAfterUpload && successfullyUploadedUris.isNotEmpty()) {
                        ". Requesting deletion of ${successfullyUploadedUris.size} item(s)..."
                    } else {
                        ""
                    }

                    binding.statusTextView.text = "$statusMessage$deleteMessage!"
                    Toast.makeText(
                        this@MainActivity,
                        "$statusMessage$deleteMessage",
                        Toast.LENGTH_LONG
                    ).show()

                    // Signal that cloud stats should be refreshed
                    if (uploadedCount > 0) {
                        prefs.edit().putBoolean("should_refresh_cloud_stats", true).apply()
                    }

                    // Clear selection and preview
                    selectedImageUris.clear()
                    binding.imagePreview.setImageResource(R.drawable.ic_image_placeholder)
                    binding.imagePreview.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    binding.uploadButton.isEnabled = false
                    binding.retryButton.visibility = View.GONE
                } else {
                    // Some uploads failed
                    val failedCount = newFailedUris.size
                    val statusMessage = if (skippedCount > 0) {
                        "Uploaded $uploadedCount, skipped $skippedCount. $failedCount failed."
                    } else {
                        "Uploaded $uploadedCount of $totalImages. $failedCount failed."
                    }

                    val deleteMessage = if (deleteAfterUpload && successfullyUploadedUris.isNotEmpty()) {
                        " Requesting deletion of ${successfullyUploadedUris.size} item(s)..."
                    } else {
                        ""
                    }

                    binding.statusTextView.text = "$statusMessage$deleteMessage"
                    Toast.makeText(
                        this@MainActivity,
                        "$statusMessage$deleteMessage Tap Retry to try again.",
                        Toast.LENGTH_LONG
                    ).show()

                    // Signal that cloud stats should be refreshed if some uploads succeeded
                    if (uploadedCount > 0) {
                        prefs.edit().putBoolean("should_refresh_cloud_stats", true).apply()
                    }

                    binding.uploadButton.isEnabled = selectedImageUris.isNotEmpty()
                    binding.retryButton.visibility = View.VISIBLE
                    binding.retryButton.isEnabled = true
                }

            } catch (e: CancellationException) {
                // Upload was cancelled by user
                binding.progressBar.visibility = View.GONE
                binding.uploadButton.isEnabled = selectedImageUris.isNotEmpty()
                binding.retryButton.isEnabled = failedImageUris.isNotEmpty()
                binding.selectPhotoButton.isEnabled = true
                binding.cancelButton.visibility = View.GONE
                binding.statusTextView.text = "Upload cancelled"
                // Don't show toast - already shown in cancelUpload()
                throw e // Re-throw to properly cancel the coroutine
            } catch (e: Exception) {
                // Unexpected error
                binding.progressBar.visibility = View.GONE
                binding.uploadButton.isEnabled = true
                binding.retryButton.isEnabled = true
                binding.selectPhotoButton.isEnabled = true
                binding.cancelButton.visibility = View.GONE
                binding.statusTextView.text = "Upload error: ${e.message}"
                Toast.makeText(
                    this@MainActivity,
                    "Upload error: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
                e.printStackTrace()
            }
        }
    }
}
