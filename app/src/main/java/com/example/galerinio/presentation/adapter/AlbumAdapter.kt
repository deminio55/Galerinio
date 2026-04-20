package com.example.galerinio.presentation.adapter

import android.content.ContentUris
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.galerinio.R
import com.example.galerinio.domain.model.AlbumModel
import java.util.Collections

class AlbumAdapter(
    private val onItemClick: (AlbumModel) -> Unit,
    private val onItemLongClick: (AlbumModel) -> Unit,
    private val onProtectionClick: (AlbumModel) -> Unit
) : RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder>() {

    enum class DisplayMode { GRID, LIST }

    companion object {
        private const val VIEW_TYPE_GRID = 0
        private const val VIEW_TYPE_LIST = 1
    }

    private val items = mutableListOf<AlbumModel>()
    private var reorderEditingEnabled = false
    private var protectedAlbumIds: Set<Long> = emptySet()
    private var folderProtectionEnabled: Boolean = false
    private var displayMode: DisplayMode = DisplayMode.GRID

    override fun getItemViewType(position: Int): Int {
        return if (displayMode == DisplayMode.LIST) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val layout = if (viewType == VIEW_TYPE_LIST) R.layout.item_album_list else R.layout.item_album
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return AlbumViewHolder(view, onItemClick, onItemLongClick, onProtectionClick)
    }
    
    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        val album = items[position]
        val isProtected = folderProtectionEnabled && protectedAlbumIds.contains(album.id)
        holder.bind(album, reorderEditingEnabled, isProtected, folderProtectionEnabled)
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<AlbumModel>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setDisplayMode(mode: DisplayMode) {
        if (displayMode == mode) return
        displayMode = mode
        notifyDataSetChanged()
    }

    fun swapItems(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition !in items.indices || toPosition !in items.indices) return false
        Collections.swap(items, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    fun setReorderEditing(enabled: Boolean) {
        if (reorderEditingEnabled == enabled) return
        reorderEditingEnabled = enabled
        if (items.isNotEmpty()) {
            notifyItemRangeChanged(0, items.size)
        }
    }

    fun getCurrentItems(): List<AlbumModel> = items.toList()

    fun setProtectedAlbumIds(ids: Set<Long>) {
        protectedAlbumIds = ids
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    fun setFolderProtectionEnabled(enabled: Boolean) {
        if (folderProtectionEnabled == enabled) return
        folderProtectionEnabled = enabled
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    class AlbumViewHolder(
        itemView: View,
        private val onItemClick: (AlbumModel) -> Unit,
        private val onItemLongClick: (AlbumModel) -> Unit,
        private val onProtectionClick: (AlbumModel) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val albumCover: ImageView = itemView.findViewById(R.id.albumCover)
        private val albumName: TextView = itemView.findViewById(R.id.albumName)
        private val mediaCount: TextView = itemView.findViewById(R.id.mediaCount)
        private val editModeOverlay: View = itemView.findViewById(R.id.editModeOverlay)
        private val reorderHintIcon: ImageView = itemView.findViewById(R.id.reorderHintIcon)
        private val protectionIcon: ImageView = itemView.findViewById(R.id.protectionIcon)
        private val blurOverlay: View = itemView.findViewById(R.id.blurOverlay)

        fun bind(
            album: AlbumModel,
            isReorderEditing: Boolean,
            isProtected: Boolean,
            folderProtectionEnabled: Boolean
        ) {
            albumName.text = album.name
            mediaCount.text = "${album.mediaCount} files"
            editModeOverlay.visibility = if (isReorderEditing) View.VISIBLE else View.GONE
            reorderHintIcon.visibility = if (isReorderEditing) View.VISIBLE else View.GONE
            protectionIcon.visibility = if (folderProtectionEnabled) View.VISIBLE else View.GONE
            if (folderProtectionEnabled) {
                protectionIcon.setImageResource(
                    if (isProtected) R.drawable.ic_lock_24
                    else R.drawable.ic_lock_open_24
                )
                val lockTint = if (isProtected) R.color.lock_icon_closed else android.R.color.white
                protectionIcon.setColorFilter(
                    ContextCompat.getColor(itemView.context, lockTint)
                )
            }
            blurOverlay.visibility = if (isProtected) View.VISIBLE else View.GONE

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                albumCover.setRenderEffect(
                    if (isProtected) RenderEffect.createBlurEffect(16f, 16f, Shader.TileMode.CLAMP) else null
                )
            } else {
                albumCover.alpha = if (isProtected) 0.72f else 1f
            }

            if (album.coverMediaId != 0L) {
                val coverUri = ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri("external"),
                    album.coverMediaId
                )
                Glide.with(itemView.context)
                    .load(coverUri)
                    .centerCrop()
                    .placeholder(android.R.color.darker_gray)
                    .into(albumCover)
            } else {
                albumCover.setImageResource(android.R.color.darker_gray)
            }

            itemView.setOnClickListener {
                onItemClick(album)
            }
            itemView.setOnLongClickListener {
                onItemLongClick(album)
                true
            }
            protectionIcon.setOnClickListener(if (folderProtectionEnabled) {
                View.OnClickListener { onProtectionClick(album) }
            } else {
                null
            })
        }
    }
    
}

