package com.example.galerinio.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.galerinio.data.local.entity.TrashEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrashDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TrashEntity): Long

    @Query("SELECT * FROM trash_items ORDER BY dateDeleted DESC")
    fun getAllFlow(): Flow<List<TrashEntity>>

    @Query("SELECT * FROM trash_items ORDER BY dateDeleted DESC")
    suspend fun getAllOnce(): List<TrashEntity>

    @Query("SELECT * FROM trash_items WHERE id = :itemId LIMIT 1")
    suspend fun getById(itemId: Long): TrashEntity?

    @Query("DELETE FROM trash_items WHERE id = :itemId")
    suspend fun deleteById(itemId: Long)

    @Query("DELETE FROM trash_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
    
    @Query("DELETE FROM trash_items WHERE mediaId = :mediaId")
    suspend fun deleteByMediaId(mediaId: Long)

    @Query("SELECT * FROM trash_items WHERE dateDeleted < :beforeTimestamp ORDER BY dateDeleted DESC")
    suspend fun getOlderThan(beforeTimestamp: Long): List<TrashEntity>

    @Query("DELETE FROM trash_items WHERE trashPath = :path")
    suspend fun deleteByTrashPath(path: String): Int

    @Query("DELETE FROM trash_items")
    suspend fun clearAll()
}

