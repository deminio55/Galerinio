package com.example.galerinio.presentation.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.galerinio.R
import com.example.galerinio.databinding.ItemTrashBinding
import com.example.galerinio.domain.model.TrashModel

class TrashAdapter(
    private val onRestoreClick: (TrashModel) -> Unit,
    private val onDeleteForeverClick: (TrashModel) -> Unit,
    private val onSelectionChanged: (selectedIds: Set<Long>) -> Unit = {}
) : ListAdapter<TrashModel, TrashAdapter.TrashViewHolder>(DiffCallback()) {

    private val selectedIds = mutableSetOf<Long>()
    var isSelectionMode: Boolean = false
        private set

    fun enterSelectionMode(id: Long) {
        isSelectionMode = true
        selectedIds.add(id)
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.toSet())
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(emptySet())
    }

    fun getSelectedIds(): Set<Long> = selectedIds.toSet()

    fun selectAll() {
        selectedIds.clear()
        currentList.forEach { selectedIds.add(it.id) }
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.toSet())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrashViewHolder {
        val binding = ItemTrashBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TrashViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TrashViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TrashViewHolder(private val binding: ItemTrashBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TrashModel) {
            binding.tvFileName.text = item.fileName
            binding.tvOriginalPath.text = item.originalPath
            Glide.with(binding.root.context)
                .load(item.trashPath)
                .centerCrop()
                .placeholder(android.R.color.darker_gray)
                .error(android.R.color.darker_gray)
                .into(binding.ivPreview)

            val isSelected = selectedIds.contains(item.id)
            binding.cbSelect.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            binding.cbSelect.isChecked = isSelected
            binding.btnRestore.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
            binding.btnDeleteForever.visibility = if (isSelectionMode) View.GONE else View.VISIBLE

            // Highlight selected row
            val bgColor = if (isSelected)
                ContextCompat.getColor(binding.root.context, R.color.trash_selected_bg)
            else
                ContextCompat.getColor(binding.root.context, android.R.color.transparent)
            binding.root.setBackgroundColor(bgColor)

            binding.root.setOnLongClickListener {
                if (!isSelectionMode) {
                    enterSelectionMode(item.id)
                }
                true
            }

            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    if (selectedIds.contains(item.id)) {
                        selectedIds.remove(item.id)
                    } else {
                        selectedIds.add(item.id)
                    }
                    notifyItemChanged(bindingAdapterPosition)
                    onSelectionChanged(selectedIds.toSet())
                }
            }

            binding.cbSelect.setOnClickListener {
                if (selectedIds.contains(item.id)) {
                    selectedIds.remove(item.id)
                } else {
                    selectedIds.add(item.id)
                }
                notifyItemChanged(bindingAdapterPosition)
                onSelectionChanged(selectedIds.toSet())
            }

            binding.btnRestore.setOnClickListener { onRestoreClick(item) }
            binding.btnDeleteForever.setOnClickListener { onDeleteForeverClick(item) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<TrashModel>() {
        override fun areItemsTheSame(oldItem: TrashModel, newItem: TrashModel) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TrashModel, newItem: TrashModel) = oldItem == newItem
    }
}
