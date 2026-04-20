package com.example.galerinio.domain.repository

import com.example.galerinio.domain.model.AlbumModel
import kotlinx.coroutines.flow.Flow

interface AlbumRepository {
    fun getAllAlbums(): Flow<List<AlbumModel>>
    fun searchAlbums(query: String): Flow<List<AlbumModel>>
    fun getAlbumCount(): Flow<Int>
    suspend fun addAlbum(album: AlbumModel)
    suspend fun addAllAlbums(albums: List<AlbumModel>)
    suspend fun updateAlbum(album: AlbumModel)
    suspend fun deleteAlbumById(albumId: Long)
}

