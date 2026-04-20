package com.example.galerinio.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trash_items")
data class TrashEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val mediaId: Long,
    val fileName: String,
    val originalPath: String,
    val trashPath: String,
    val mimeType: String,
    val size: Long,
    val dateDeleted: Long,
    val isVideo: Boolean,
    // Сохраняем оригинальные метаданные для корректного восстановления
    val originalDateAdded: Long = 0L,
    val originalDateModified: Long = 0L
)

