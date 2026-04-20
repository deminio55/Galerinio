package com.example.galerinio.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.galerinio.data.local.entity.AlbumEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbum(album: AlbumEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllAlbums(albums: List<AlbumEntity>)
    
    @Update
    suspend fun updateAlbum(album: AlbumEntity)
    
    @Query("DELETE FROM albums WHERE id = :albumId")
    suspend fun deleteAlbumById(albumId: Long)
    
    @Query("SELECT * FROM albums ORDER BY dateAdded DESC, name COLLATE NOCASE ASC")
    fun getAllAlbumsFlow(): Flow<List<AlbumEntity>>
    
    @Query("SELECT * FROM albums WHERE name LIKE '%' || :query || '%' ORDER BY dateAdded DESC, name COLLATE NOCASE ASC")
    fun searchAlbumsFlow(query: String): Flow<List<AlbumEntity>>
    
    @Query("SELECT COUNT(*) FROM albums")
    fun getAlbumCount(): Flow<Int>

    @Query("DELETE FROM albums")
    suspend fun clearAlbums()
}

