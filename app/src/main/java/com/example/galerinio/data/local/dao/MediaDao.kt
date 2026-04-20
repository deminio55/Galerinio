package com.example.galerinio.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.galerinio.data.local.entity.MediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedia(media: MediaEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllMedia(mediaList: List<MediaEntity>)
    
    @Update
    suspend fun updateMedia(media: MediaEntity)
    
    @Query("DELETE FROM media_items WHERE id = :mediaId")
    suspend fun deleteMediaById(mediaId: Long)
    
    @Query("DELETE FROM media_items WHERE filePath = :filePath")
    suspend fun deleteMediaByPath(filePath: String)
    
    @Query("SELECT * FROM media_items ORDER BY dateModified DESC")
    fun getAllMediaFlow(): Flow<List<MediaEntity>>
    
    @Query("SELECT * FROM media_items WHERE albumId = :albumId ORDER BY dateModified DESC")
    fun getMediaByAlbumFlow(albumId: Long): Flow<List<MediaEntity>>
    
    @Query("SELECT * FROM media_items WHERE isFavorite = 1 ORDER BY dateModified DESC")
    fun getFavoriteMediaFlow(): Flow<List<MediaEntity>>
    
    @Query("SELECT * FROM media_items WHERE mimeType LIKE 'image/%' ORDER BY dateModified DESC")
    fun getImagesFlow(): Flow<List<MediaEntity>>
    
    @Query("SELECT * FROM media_items WHERE mimeType LIKE 'video/%' ORDER BY dateModified DESC")
    fun getVideosFlow(): Flow<List<MediaEntity>>
    
    @Query("SELECT * FROM media_items WHERE fileName LIKE '%' || :query || '%' ORDER BY dateModified DESC")
    fun searchMediaFlow(query: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_items WHERE id IN (:mediaIds)")
    fun getMediaByIdsFlow(mediaIds: List<Long>): Flow<List<MediaEntity>>

    /**
     * Folder-path-based query used on Android < Q where filePath is an absolute path.
     * [pathPrefixLower] must already be lowercased (e.g. "/storage/emulated/0/dcim/camera").
     * SQLite lower() handles the stored mixed-case filePath.
     */
    @Query("SELECT * FROM media_items WHERE lower(filePath) LIKE :pathPrefixLower || '/%' ORDER BY dateModified DESC")
    fun getMediaByFolderPathFlow(pathPrefixLower: String): Flow<List<MediaEntity>>

    @Query("SELECT COUNT(*) FROM media_items")
    fun getMediaCount(): Flow<Int>
    
    @Query("DELETE FROM media_items WHERE dateModified < :beforeDate")
    suspend fun deleteMediaBeforeDate(beforeDate: Long)

    @Query("UPDATE media_items SET filePath = :newPath WHERE id = :mediaId")
    suspend fun updateMediaPath(mediaId: Long, newPath: String)
    
    @Query("UPDATE media_items SET isFavorite = :isFavorite WHERE id = :mediaId")
    suspend fun updateFavorite(mediaId: Long, isFavorite: Boolean)

    @Query("SELECT id FROM media_items WHERE isFavorite = 1")
    suspend fun getFavoriteIds(): List<Long>

    @Query("SELECT id FROM media_items")
    suspend fun getAllIds(): List<Long>

    @Query("DELETE FROM media_items WHERE id IN (:mediaIds)")
    suspend fun deleteMediaByIds(mediaIds: List<Long>)
}
