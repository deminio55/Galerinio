package com.example.galerinio.presentation.adapter

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.galerinio.R

class DrawerCategoryConfigAdapter(
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onCategoryClicked: (String) -> Unit,
    private val onVisibilityChanged: (String, Boolean) -> Unit,
    private val onOrderChanged: (List<String>) -> Unit
) : RecyclerView.Adapter<DrawerCategoryConfigAdapter.ViewHolder>() {

    data class Item(
        val key: String,
        val titleRes: Int,
        val iconRes: Int,
        val isVisibleInBottom: Boolean,
        val canToggleBottomVisibility: Boolean,
        val isDraggable: Boolean,
        val isSelected: Boolean
    )

    private val items = mutableListOf<Item>()

    fun submitItems(newItems: List<Item>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun canMove(fromPosition: Int, toPosition: Int): Boolean {
        val from = items.getOrNull(fromPosition) ?: return false
        val to = items.getOrNull(toPosition) ?: return false
        return from.isDraggable && to.isDraggable
    }

    fun moveItem(fromPosition: Int, toPosition: Int): Boolean {
        if (!canMove(fromPosition, toPosition)) return false
        val moved = items.removeAt(fromPosition)
        items.add(toPosition, moved)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    fun dispatchOrderChanged() {
        onOrderChanged(items.map { it.key })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_drawer_category_config, parent, false)
        return ViewHolder(view as ViewGroup)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.icon.setImageResource(item.iconRes)
        holder.title.setText(item.titleRes)
        holder.selectedMarker.alpha = if (item.isSelected) 1f else 0f

        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.visibility = if (item.canToggleBottomVisibility) android.view.View.VISIBLE else android.view.View.INVISIBLE
        holder.checkBox.isChecked = item.isVisibleInBottom
        holder.checkBox.isEnabled = item.canToggleBottomVisibility
        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            onVisibilityChanged(item.key, isChecked)
        }

        holder.dragHandle.visibility = if (item.isDraggable) android.view.View.VISIBLE else android.view.View.INVISIBLE
        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && item.isDraggable) {
                onStartDrag(holder)
                true
            } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                holder.dragHandle.performClick()
                true
            } else {
                false
            }
        }

        holder.itemView.setOnClickListener {
            onCategoryClicked(item.key)
        }
    }

    class ViewHolder(root: ViewGroup) : RecyclerView.ViewHolder(root) {
        val selectedMarker: android.view.View = root.findViewById(R.id.selectedMarker)
        val icon: ImageView = root.findViewById(R.id.categoryIcon)
        val title: TextView = root.findViewById(R.id.categoryTitle)
        val checkBox: CheckBox = root.findViewById(R.id.categoryToggle)
        val dragHandle: ImageView = root.findViewById(R.id.categoryDragHandle)
    }
}

