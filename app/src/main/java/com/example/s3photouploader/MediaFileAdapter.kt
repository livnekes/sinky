package com.example.s3photouploader

import android.content.ContentUris
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class MediaFileAdapter(
    private val items: List<MediaItem>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<MediaFileAdapter.MediaViewHolder>() {

    class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: MaterialCardView = view.findViewById(R.id.cardView)
        val thumbnail: ImageView = view.findViewById(R.id.thumbnail)
        val checkbox: CheckBox = view.findViewById(R.id.checkbox)
        val selectionOverlay: View = view.findViewById(R.id.selectionOverlay)
        val sizeText: TextView = view.findViewById(R.id.sizeText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media_file, parent, false)
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val item = items[position]

        // Load thumbnail properly
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use modern thumbnail API for both photos and videos
                val thumbnail: Bitmap = holder.itemView.context.contentResolver.loadThumbnail(
                    item.uri,
                    Size(400, 400),
                    null
                )
                holder.thumbnail.setImageBitmap(thumbnail)
                val type = if (item.isVideo) "video" else "photo"
                android.util.Log.d("MediaFileAdapter", "Loaded $type thumbnail for: ${item.uri}")
            } else {
                // For older Android versions, use different approach for videos
                if (item.isVideo) {
                    // Use ThumbnailUtils for videos on older Android
                    @Suppress("DEPRECATION")
                    val thumbnail = android.media.ThumbnailUtils.createVideoThumbnail(
                        item.uri.path ?: "",
                        android.provider.MediaStore.Images.Thumbnails.MINI_KIND
                    )
                    if (thumbnail != null) {
                        holder.thumbnail.setImageBitmap(thumbnail)
                        android.util.Log.d("MediaFileAdapter", "Loaded video thumbnail for: ${item.uri}")
                    } else {
                        android.util.Log.w("MediaFileAdapter", "Failed to load video thumbnail for: ${item.uri}")
                        holder.thumbnail.setImageResource(R.drawable.ic_image_placeholder)
                    }
                } else {
                    // For photos, setImageURI works fine
                    holder.thumbnail.setImageURI(item.uri)
                    android.util.Log.d("MediaFileAdapter", "Loaded photo thumbnail for: ${item.uri}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaFileAdapter", "Error loading thumbnail for ${item.uri} (isVideo: ${item.isVideo})", e)
            // Set placeholder if loading fails
            holder.thumbnail.setImageResource(R.drawable.ic_image_placeholder)
        }

        // Show file size
        holder.sizeText.text = item.getFormattedSize()

        // Show/hide selection UI
        holder.checkbox.visibility = if (item.isSelected) View.VISIBLE else View.GONE
        holder.selectionOverlay.visibility = if (item.isSelected) View.VISIBLE else View.GONE
        holder.checkbox.isChecked = item.isSelected

        // Handle click
        holder.cardView.setOnClickListener {
            item.isSelected = !item.isSelected
            notifyItemChanged(position)
            onSelectionChanged()
        }
    }

    override fun getItemCount() = items.size

    fun getSelectedItems(): List<MediaItem> {
        return items.filter { it.isSelected }
    }
}
