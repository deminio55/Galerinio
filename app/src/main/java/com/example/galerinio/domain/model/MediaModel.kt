package com.example.galerinio.domain.model

data class MediaModel(
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
    val isFavorite: Boolean = false,
    val isImage: Boolean = mimeType.startsWith("image/"),
    val isVideo: Boolean = mimeType.startsWith("video/")
)

