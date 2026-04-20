package com.example.galerinio.domain.repository

import com.example.galerinio.domain.model.MediaModel
import kotlinx.coroutines.flow.Flow

interface MediaRepository {
    fun getAllMedia(): Flow<List<MediaModel>>
    fun getMediaByAlbum(albumId: Long): Flow<List<MediaModel>>
    fun getFavoriteMedia(): Flow<List<MediaModel>>
    fun getImages(): Flow<List<MediaModel>>
    fun getVideos(): Flow<List<MediaModel>>
    fun getMediaByIds(mediaIds: List<Long>): Flow<List<MediaModel>>
    fun searchMedia(query: String): Flow<List<MediaModel>>
    /** Filter by folder path prefix (Android < Q where filePath is an absolute path). */
    fun getMediaByFolderPath(folderPathLower: String): Flow<List<MediaModel>>
    fun getMediaCount(): Flow<Int>
    suspend fun addMedia(media: MediaModel)
    suspend fun addAllMedia(mediaList: List<MediaModel>)
    suspend fun updateMedia(media: MediaModel)
    suspend fun deleteMediaById(mediaId: Long)
    suspend fun deleteMediaByPath(filePath: String)
    suspend fun deleteMediaBeforeDate(beforeDate: Long)
    suspend fun toggleFavorite(mediaId: Long, isFavorite: Boolean)
}

