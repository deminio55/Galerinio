package com.example.galerinio.domain.model

data class TrashModel(
    val id: Long,
    val mediaId: Long,
    val fileName: String,
    val originalPath: String,
    val trashPath: String,
    val mimeType: String,
    val size: Long,
    val dateDeleted: Long,
    val isVideo: Boolean,
    val originalDateAdded: Long = 0L,
    val originalDateModified: Long = 0L
)

