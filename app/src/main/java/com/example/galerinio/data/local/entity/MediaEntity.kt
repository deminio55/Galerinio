package com.example.galerinio.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_items")
data class MediaEntity(
    @PrimaryKey
    val id: Long,
    val fileName: String,
    val filePath: String,
    val mimeType: String,
    val size: Long,
    val dateModified: Long,
    val dateAdded: Long,
    val width: Int = 0,
    val height: Int = 0,
    val duration: Long = 0L,
    val albumId: Long = 0L,
    val isFavorite: Boolean = false
)

