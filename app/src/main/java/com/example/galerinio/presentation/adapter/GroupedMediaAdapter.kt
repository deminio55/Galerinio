package com.example.galerinio.presentation.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.galerinio.R
import com.example.galerinio.domain.model.MediaListItem
import com.example.galerinio.domain.model.MediaModel

class GroupedMediaAdapter(
    private val onItemClick: (MediaModel, ImageView, String) -> Unit,
    private val onSelectionChanged: (selectedIds: Set<Long>) -> Unit = {},
    private val rotationProvider: (Long) -> Int = { 0 }
) : ListAdapter<MediaListItem, RecyclerView.ViewHolder>(GroupedMediaDiffCallback()) {

    enum class DisplayMode { GRID, LIST }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_MEDIA_GRID = 1
        private const val VIEW_TYPE_MEDIA_LIST = 2
    }

    private val selectedIds = mutableSetOf<Long>()
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
    private var displayMode: DisplayMode = DisplayMode.GRID
    private var blurProtectedEnabled = false
    private var protectedAlbumIds: Set<Long> = emptySet()

    fun getSelectedIds(): Set<Long> = selectedIds.toSet()

    fun setDisplayMode(mode: DisplayMode) {
        if (displayMode == mode) return
        displayMode = mode
        notifyDataSetChanged()
    }

    fun setProtectedMediaRendering(enabled: Boolean, albumIds: Set<Long>) {
        blurProtectedEnabled = enabled
        protectedAlbumIds = albumIds
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is MediaListItem.Header -> VIEW_TYPE_HEADER
            is MediaListItem.MediaItem -> {
                if (displayMode == DisplayMode.LIST) VIEW_TYPE_MEDIA_LIST else VIEW_TYPE_MEDIA_GRID
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_media_header, parent, false)
                HeaderViewHolder(view)
            }
            else -> {
                val layout = if (viewType == VIEW_TYPE_MEDIA_LIST) R.layout.item_media_list else R.layout.item_media
                val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
                MediaViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is MediaListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is MediaListItem.MediaItem -> (holder as MediaViewHolder).bind(item.media)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is MediaViewHolder) {
            Glide.with(holder.itemView.context).clear(holder.imageView)
        }
        super.onViewRecycled(holder)
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.headerTitle)

        fun bind(header: MediaListItem.Header) {
            titleView.text = header.title
        }
    }

    inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.imageView)
        private val videoOverlay: View = itemView.findViewById(R.id.videoOverlay)
        private val selectionOverlay: View = itemView.findViewById(R.id.selectionOverlay)
        private val selectionCheck: ImageView = itemView.findViewById(R.id.selectionCheck)
        private val protectedOverlay: View = itemView.findViewById(R.id.protectedOverlay)
        private val protectedIcon: ImageView = itemView.findViewById(R.id.protectedIcon)
        private val fileNameText: TextView? = itemView.findViewById(R.id.fileNameText)

        fun bind(media: MediaModel) {
            val transitionName = "media_thumb_${media.id}"
            ViewCompat.setTransitionName(imageView, transitionName)

            fileNameText?.text = media.fileName

            Glide.with(itemView.context)
                .load(media.filePath)
                .override(360, 360)
                .centerCrop()
                .placeholder(android.R.color.darker_gray)
                .error(android.R.color.darker_gray)
                .dontAnimate()
                .into(imageView)

            videoOverlay.alpha = if (media.isVideo) 1f else 0f
            imageView.rotation = if (media.isVideo) 0f else rotationProvider(media.id).toFloat()

            val isProtectedMedia = blurProtectedEnabled && media.albumId in protectedAlbumIds
            protectedOverlay.visibility = if (isProtectedMedia) View.VISIBLE else View.GONE
            protectedIcon.visibility = if (isProtectedMedia) View.VISIBLE else View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                imageView.setRenderEffect(
                    if (isProtectedMedia) RenderEffect.createBlurEffect(14f, 14f, Shader.TileMode.CLAMP) else null
                )
            } else {
                imageView.alpha = if (isProtectedMedia) 0.72f else 1f
            }

            val selected = media.id in selectedIds
            selectionOverlay.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            selectionCheck.visibility = if (isSelectionMode) View.VISIBLE else View.INVISIBLE
            selectionCheck.setImageResource(
                if (selected) R.drawable.ic_check_filled else R.drawable.ic_check_empty
            )

            itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(media.id)
                } else {
                    onItemClick(media, imageView, transitionName)
                }
            }

            itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    toggleSelection(media.id)
                }
                true
            }
        }
    }

    private fun toggleSelection(mediaId: Long) {
        if (mediaId in selectedIds) {
            selectedIds.remove(mediaId)
        } else {
            selectedIds.add(mediaId)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedIds)
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(selectedIds)
    }

    fun selectAll() {
        currentList.forEach { item ->
            if (item is MediaListItem.MediaItem) {
                selectedIds.add(item.media.id)
            }
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedIds)
    }

    private class GroupedMediaDiffCallback : DiffUtil.ItemCallback<MediaListItem>() {
        override fun areItemsTheSame(oldItem: MediaListItem, newItem: MediaListItem): Boolean {
            return when {
                oldItem is MediaListItem.Header && newItem is MediaListItem.Header ->
                    oldItem.title == newItem.title
                oldItem is MediaListItem.MediaItem && newItem is MediaListItem.MediaItem ->
                    oldItem.media.id == newItem.media.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: MediaListItem, newItem: MediaListItem): Boolean {
            return oldItem == newItem
        }
    }
}

