package com.example.galerinio.presentation.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.galerinio.R

class PhotoEditorCategoryAdapter(
    private val items: List<Item>,
    private val onItemClick: (Item) -> Unit
) : RecyclerView.Adapter<PhotoEditorCategoryAdapter.CategoryViewHolder>() {

    data class Item(
        val id: String,
        val titleRes: Int,
        val iconRes: Int
    )

    private var selectedId: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo_editor_category, parent, false)
        return CategoryViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        val item = items[position]
        val selected = item.id == selectedId
        holder.icon.setImageResource(item.iconRes)
        holder.title.setText(item.titleRes)
        holder.icon.contentDescription = holder.itemView.context.getString(item.titleRes)

        holder.itemView.isSelected = selected
        holder.icon.isSelected = selected
        holder.title.isSelected = selected
        holder.itemView.alpha = if (selected) 1f else 0.82f

        holder.itemView.setOnClickListener {
            selectById(item.id)
            onItemClick(item)
        }
    }

    fun selectById(id: String) {
        if (selectedId == id) return
        val previousIndex = items.indexOfFirst { it.id == selectedId }
        val newIndex = items.indexOfFirst { it.id == id }
        selectedId = id
        if (previousIndex >= 0) notifyItemChanged(previousIndex)
        if (newIndex >= 0) notifyItemChanged(newIndex)
    }

    class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.imageCategoryIcon)
        val title: TextView = itemView.findViewById(R.id.textCategoryTitle)
    }
}

