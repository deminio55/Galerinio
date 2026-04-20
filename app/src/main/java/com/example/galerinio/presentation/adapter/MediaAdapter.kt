package com.example.galerinio.presentation.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.core.view.ViewCompat
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.galerinio.R
import com.example.galerinio.domain.model.MediaModel

class MediaAdapter(
    private val onItemClick: (MediaModel, ImageView, String) -> Unit,
    private val onSelectionChanged: (selectedIds: Set<Long>) -> Unit = {},
    private val rotationProvider: (Long) -> Int = { 0 }
) : ListAdapter<MediaModel, MediaAdapter.MediaViewHolder>(MediaDiffCallback()) {

    enum class DisplayMode { GRID, LIST }

    companion object {
        private const val VIEW_TYPE_GRID = 0
        private const val VIEW_TYPE_LIST = 1
    }

    private val selectedIds = mutableSetOf<Long>()
    private var displayMode: DisplayMode = DisplayMode.GRID
    private var blurProtectedEnabled = false
    private var protectedAlbumIds: Set<Long> = emptySet()

    val isSelectionMode: Boolean
        get() = selectedIds.isNotEmpty()

    fun getSelectedIds(): Set<Long> = selectedIds.toSet()

    fun getSelectedItems(): List<MediaModel> =
        currentList.filter { it.id in selectedIds }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(emptySet())
    }

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

    private fun enterSelectionMode(id: Long) {
        selectedIds.add(id)
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.toSet())
    }

    private fun toggleSelection(id: Long) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
        val pos = currentList.indexOfFirst { it.id == id }
        if (pos != -1) notifyItemChanged(pos)
        onSelectionChanged(selectedIds.toSet())
    }

    override fun getItemViewType(position: Int): Int {
        return if (displayMode == DisplayMode.LIST) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val layout = if (viewType == VIEW_TYPE_LIST) R.layout.item_media_list else R.layout.item_media
        val itemView = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return MediaViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: MediaViewHolder) {
        Glide.with(holder.itemView.context).clear(holder.imageView)
        super.onViewRecycled(holder)
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

            // Отображение состояния выбора
            val selected = media.id in selectedIds
            if (isSelectionMode) {
                selectionCheck.visibility = View.VISIBLE
                selectionCheck.setImageResource(
                    if (selected) R.drawable.ic_check_circle else R.drawable.ic_check_empty
                )
                selectionOverlay.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            } else {
                selectionCheck.visibility = View.INVISIBLE
                selectionOverlay.visibility = View.INVISIBLE
            }

            itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(media.id)
                } else {
                    onItemClick(media, imageView, transitionName)
                }
            }

            itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    enterSelectionMode(media.id)
                } else {
                    toggleSelection(media.id)
                }
                true
            }
        }
    }

    private class MediaDiffCallback : DiffUtil.ItemCallback<MediaModel>() {
        override fun areItemsTheSame(oldItem: MediaModel, newItem: MediaModel) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: MediaModel, newItem: MediaModel) =
            oldItem == newItem
    }
}
