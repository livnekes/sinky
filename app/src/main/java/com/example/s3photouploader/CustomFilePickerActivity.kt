package com.example.s3photouploader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.s3photouploader.databinding.ActivityCustomFilePickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CustomFilePickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomFilePickerBinding
    private val photoItems = mutableListOf<MediaItem>()
    private val videoItems = mutableListOf<MediaItem>()
    private lateinit var photosAdapter: MediaFileAdapter
    private lateinit var videosAdapter: MediaFileAdapter

    private var photoOffset = 0
    private var videoOffset = 0
    private var hasMorePhotos = true
    private var hasMoreVideos = true
    private var videosLoaded = false

    companion object {
        const val EXTRA_SELECTED_URIS = "selected_uris"
        private const val MIN_FILE_SIZE = 10 * 1024 * 1024L // 10MB
        private const val ITEMS_PER_PAGE = 50 // Load 50 items at a time
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomFilePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerViews()
        loadLargeFiles()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerViews() {
        photosAdapter = MediaFileAdapter(photoItems) {
            updateSelectButton()
        }

        videosAdapter = MediaFileAdapter(videoItems) {
            updateSelectButton()
        }

        binding.photosRecyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.photosRecyclerView.adapter = photosAdapter

        binding.videosRecyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.videosRecyclerView.adapter = videosAdapter

        // Setup tabs
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Photos"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Videos"))

        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        // Show photos
                        binding.photosContainer.visibility = View.VISIBLE
                        binding.videosContainer.visibility = View.GONE
                    }
                    1 -> {
                        // Show videos
                        binding.photosContainer.visibility = View.GONE
                        binding.videosContainer.visibility = View.VISIBLE

                        // Load videos on first click
                        if (!videosLoaded) {
                            loadVideos()
                        }
                    }
                }
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        binding.loadMorePhotosButton.setOnClickListener {
            loadMorePhotos()
        }

        binding.loadMoreVideosButton.setOnClickListener {
            loadMoreVideos()
        }

        binding.selectButton.setOnClickListener {
            returnSelectedFiles()
        }
    }

    private fun loadLargeFiles() {
        binding.statusText.text = "Loading photos (10MB+)..."
        binding.photosContainer.visibility = View.GONE
        binding.videosContainer.visibility = View.GONE

        lifecycleScope.launch {
            try {
                // Only load photos initially
                val photos = withContext(Dispatchers.IO) {
                    queryPhotos(photoOffset)
                }

                photoItems.clear()
                photoItems.addAll(photos)
                photosAdapter.notifyDataSetChanged()

                photoOffset = photos.size
                hasMorePhotos = photos.size >= ITEMS_PER_PAGE

                if (photos.isEmpty()) {
                    binding.statusText.text = "No photos found larger than 10MB"
                    binding.tabLayout.visibility = View.GONE
                } else {
                    binding.statusText.text = "Tap to select files • ${photos.size} photos"
                    binding.tabLayout.visibility = View.VISIBLE

                    // Show photos tab
                    binding.tabLayout.selectTab(binding.tabLayout.getTabAt(0))
                    binding.photosContainer.visibility = View.VISIBLE

                    // Update tab text with counts
                    binding.tabLayout.getTabAt(0)?.text = "Photos (${photos.size})"
                    binding.tabLayout.getTabAt(1)?.text = "Videos"

                    // Update Load More button visibility
                    binding.loadMorePhotosButton.visibility = if (hasMorePhotos) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                android.util.Log.e("CustomFilePicker", "Error loading files", e)
                binding.statusText.text = "Error loading files: ${e.message}"
            }
        }
    }

    private fun loadVideos() {
        videosLoaded = true
        binding.statusText.text = "Loading videos..."

        lifecycleScope.launch {
            try {
                val videos = withContext(Dispatchers.IO) {
                    queryVideos(videoOffset)
                }

                videoItems.clear()
                videoItems.addAll(videos)
                videosAdapter.notifyDataSetChanged()

                videoOffset = videos.size
                hasMoreVideos = videos.size >= ITEMS_PER_PAGE

                // Update tab text and status
                binding.tabLayout.getTabAt(1)?.text = "Videos (${videos.size})"
                binding.statusText.text = "Tap to select files • ${photoItems.size} photos, ${videos.size} videos"

                // Update Load More button visibility
                binding.loadMoreVideosButton.visibility = if (hasMoreVideos) View.VISIBLE else View.GONE

                if (videos.isEmpty()) {
                    binding.statusText.text = "Tap to select files • ${photoItems.size} photos, 0 videos"
                }
            } catch (e: Exception) {
                android.util.Log.e("CustomFilePicker", "Error loading videos", e)
                binding.statusText.text = "Error loading videos: ${e.message}"
            }
        }
    }

    private fun loadMorePhotos() {
        if (!hasMorePhotos) return

        binding.loadMorePhotosButton.isEnabled = false
        binding.loadMorePhotosButton.text = "Loading..."

        lifecycleScope.launch {
            try {
                val photos = withContext(Dispatchers.IO) {
                    queryPhotos(photoOffset)
                }

                val startIndex = photoItems.size
                photoItems.addAll(photos)
                photosAdapter.notifyItemRangeInserted(startIndex, photos.size)

                photoOffset += photos.size
                hasMorePhotos = photos.size >= ITEMS_PER_PAGE

                // Update tab count
                binding.tabLayout.getTabAt(0)?.text = "Photos (${photoItems.size})"
                binding.statusText.text = "Tap to select files • ${photoItems.size} photos, ${videoItems.size} videos"

                // Update button visibility
                binding.loadMorePhotosButton.visibility = if (hasMorePhotos) View.VISIBLE else View.GONE
                binding.loadMorePhotosButton.isEnabled = true
                binding.loadMorePhotosButton.text = "Load More Photos"
            } catch (e: Exception) {
                android.util.Log.e("CustomFilePicker", "Error loading more photos", e)
                binding.loadMorePhotosButton.isEnabled = true
                binding.loadMorePhotosButton.text = "Load More Photos"
            }
        }
    }

    private fun loadMoreVideos() {
        if (!hasMoreVideos) return

        binding.loadMoreVideosButton.isEnabled = false
        binding.loadMoreVideosButton.text = "Loading..."

        lifecycleScope.launch {
            try {
                val videos = withContext(Dispatchers.IO) {
                    queryVideos(videoOffset)
                }

                val startIndex = videoItems.size
                videoItems.addAll(videos)
                videosAdapter.notifyItemRangeInserted(startIndex, videos.size)

                videoOffset += videos.size
                hasMoreVideos = videos.size >= ITEMS_PER_PAGE

                // Update tab count
                binding.tabLayout.getTabAt(1)?.text = "Videos (${videoItems.size})"
                binding.statusText.text = "Tap to select files • ${photoItems.size} photos, ${videoItems.size} videos"

                // Update button visibility
                binding.loadMoreVideosButton.visibility = if (hasMoreVideos) View.VISIBLE else View.GONE
                binding.loadMoreVideosButton.isEnabled = true
                binding.loadMoreVideosButton.text = "Load More Videos"
            } catch (e: Exception) {
                android.util.Log.e("CustomFilePicker", "Error loading more videos", e)
                binding.loadMoreVideosButton.isEnabled = true
                binding.loadMoreVideosButton.text = "Load More Videos"
            }
        }
    }

    private fun queryPhotos(offset: Int): List<MediaItem> {
        val photos = mutableListOf<MediaItem>()
        val imageProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.SIZE
        )

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            "${MediaStore.Images.Media.SIZE} >= ?",
            arrayOf(MIN_FILE_SIZE.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            var currentIndex = 0
            while (cursor.moveToNext()) {
                if (currentIndex >= offset && photos.size < ITEMS_PER_PAGE) {
                    val id = cursor.getLong(idColumn)
                    val size = cursor.getLong(sizeColumn)
                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )
                    photos.add(MediaItem(uri, size, isVideo = false))
                }
                currentIndex++
                if (photos.size >= ITEMS_PER_PAGE) break
            }
        }

        return photos.sortedByDescending { it.size }
    }

    private fun queryVideos(offset: Int): List<MediaItem> {
        val videos = mutableListOf<MediaItem>()
        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.SIZE
        )

        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            "${MediaStore.Video.Media.SIZE} >= ?",
            arrayOf(MIN_FILE_SIZE.toString()),
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            var currentIndex = 0
            while (cursor.moveToNext()) {
                if (currentIndex >= offset && videos.size < ITEMS_PER_PAGE) {
                    val id = cursor.getLong(idColumn)
                    val size = cursor.getLong(sizeColumn)
                    val uri = Uri.withAppendedPath(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )
                    videos.add(MediaItem(uri, size, isVideo = true))
                }
                currentIndex++
                if (videos.size >= ITEMS_PER_PAGE) break
            }
        }

        return videos.sortedByDescending { it.size }
    }

    private fun updateSelectButton() {
        val selectedPhotos = photosAdapter.getSelectedItems()
        val selectedVideos = videosAdapter.getSelectedItems()
        val selectedCount = selectedPhotos.size + selectedVideos.size

        binding.selectButton.isEnabled = selectedCount > 0
        binding.selectButton.text = if (selectedCount > 0) {
            "Select $selectedCount file(s)"
        } else {
            "Select Files"
        }
    }

    private fun returnSelectedFiles() {
        val selectedPhotos = photosAdapter.getSelectedItems()
        val selectedVideos = videosAdapter.getSelectedItems()
        val allSelected = selectedPhotos + selectedVideos
        val uris = ArrayList(allSelected.map { it.uri })

        android.util.Log.d("CustomFilePicker", "Returning ${uris.size} selected files")

        val resultIntent = Intent().apply {
            putParcelableArrayListExtra(EXTRA_SELECTED_URIS, uris)
        }

        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}
