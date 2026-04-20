package com.example.galerinio.data.repository

import com.example.galerinio.data.local.dao.AlbumDao
import com.example.galerinio.data.local.entity.AlbumEntity
import com.example.galerinio.domain.model.AlbumModel
import com.example.galerinio.domain.repository.AlbumRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AlbumRepositoryImpl(private val albumDao: AlbumDao) : AlbumRepository {
    
    override fun getAllAlbums(): Flow<List<AlbumModel>> {
        return albumDao.getAllAlbumsFlow().map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    override fun searchAlbums(query: String): Flow<List<AlbumModel>> {
        return albumDao.searchAlbumsFlow(query).map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    override fun getAlbumCount(): Flow<Int> {
        return albumDao.getAlbumCount()
    }
    
    override suspend fun addAlbum(album: AlbumModel) {
        albumDao.insertAlbum(album.toEntity())
    }
    
    override suspend fun addAllAlbums(albums: List<AlbumModel>) {
        albumDao.insertAllAlbums(albums.map { it.toEntity() })
    }
    
    override suspend fun updateAlbum(album: AlbumModel) {
        albumDao.updateAlbum(album.toEntity())
    }
    
    override suspend fun deleteAlbumById(albumId: Long) {
        albumDao.deleteAlbumById(albumId)
    }
    
    private fun AlbumEntity.toModel() = AlbumModel(
        id = id,
        name = name,
        path = path,
        coverMediaId = coverMediaId,
        mediaCount = mediaCount,
        dateAdded = dateAdded
    )
    
    private fun AlbumModel.toEntity() = AlbumEntity(
        id = id,
        name = name,
        path = path,
        coverMediaId = coverMediaId,
        mediaCount = mediaCount,
        dateAdded = dateAdded
    )
}

