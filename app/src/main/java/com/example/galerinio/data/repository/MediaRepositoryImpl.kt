package com.example.galerinio.data.repository

import com.example.galerinio.data.local.dao.MediaDao
import com.example.galerinio.data.local.entity.MediaEntity
import com.example.galerinio.domain.model.MediaModel
import com.example.galerinio.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class MediaRepositoryImpl(private val mediaDao: MediaDao) : MediaRepository {
    
    override fun getAllMedia(): Flow<List<MediaModel>> {
        return mediaDao.getAllMediaFlow().map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    override fun getMediaByAlbum(albumId: Long): Flow<List<MediaModel>> {
        return mediaDao.getMediaByAlbumFlow(albumId).map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    override fun getFavoriteMedia(): Flow<List<MediaModel>> {
        return mediaDao.getFavoriteMediaFlow().map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    override fun getImages(): Flow<List<MediaModel>> {
        return mediaDao.getImagesFlow().map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    override fun getVideos(): Flow<List<MediaModel>> {
        return mediaDao.getVideosFlow().map { entities ->
            entities.map { it.toModel() }
        }
    }

    override fun getMediaByIds(mediaIds: List<Long>): Flow<List<MediaModel>> {
        if (mediaIds.isEmpty()) return flowOf(emptyList())
        return mediaDao.getMediaByIdsFlow(mediaIds).map { entities ->
            val byId = entities.associateBy { it.id }
            mediaIds.mapNotNull { id -> byId[id]?.toModel() }
        }
    }
    
    override fun searchMedia(query: String): Flow<List<MediaModel>> {
        return mediaDao.searchMediaFlow(query).map { entities ->
            entities.map { it.toModel() }
        }
    }

    override fun getMediaByFolderPath(folderPathLower: String): Flow<List<MediaModel>> {
        return mediaDao.getMediaByFolderPathFlow(folderPathLower).map { entities ->
            entities.map { it.toModel() }
        }
    }

    override fun getMediaCount(): Flow<Int> {
        return mediaDao.getMediaCount()
    }
    
    override suspend fun addMedia(media: MediaModel) {
        mediaDao.insertMedia(media.toEntity())
    }
    
    override suspend fun addAllMedia(mediaList: List<MediaModel>) {
        mediaDao.insertAllMedia(mediaList.map { it.toEntity() })
    }
    
    override suspend fun updateMedia(media: MediaModel) {
        mediaDao.updateMedia(media.toEntity())
    }
    
    override suspend fun deleteMediaById(mediaId: Long) {
        mediaDao.deleteMediaById(mediaId)
    }
    
    override suspend fun deleteMediaByPath(filePath: String) {
        mediaDao.deleteMediaByPath(filePath)
    }
    
    override suspend fun deleteMediaBeforeDate(beforeDate: Long) {
        mediaDao.deleteMediaBeforeDate(beforeDate)
    }
    
    override suspend fun toggleFavorite(mediaId: Long, isFavorite: Boolean) {
        mediaDao.updateFavorite(mediaId, isFavorite)
    }
    
    private fun MediaEntity.toModel() = MediaModel(
        id = id,
        fileName = fileName,
        filePath = filePath,
        mimeType = mimeType,
        size = size,
        dateModified = dateModified,
        dateAdded = dateAdded,
        width = width,
        height = height,
        duration = duration,
        albumId = albumId,
        isFavorite = isFavorite
    )
    
    private fun MediaModel.toEntity() = MediaEntity(
        id = id,
        fileName = fileName,
        filePath = filePath,
        mimeType = mimeType,
        size = size,
        dateModified = dateModified,
        dateAdded = dateAdded,
        width = width,
        height = height,
        duration = duration,
        albumId = albumId,
        isFavorite = isFavorite
    )
}

