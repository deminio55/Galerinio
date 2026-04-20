package com.example.galerinio.domain.model

data class AlbumModel(
    val id: Long,
    val name: String,
    val path: String,
    val coverMediaId: Long = 0L,
    val mediaCount: Int = 0,
    val dateAdded: Long = System.currentTimeMillis()
)

