package com.example.galerinio.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey
    val id: Long,
    val name: String,
    val path: String,
    val coverMediaId: Long = 0L,
    val mediaCount: Int = 0,
    val dateAdded: Long = System.currentTimeMillis()
)

