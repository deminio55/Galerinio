package com.example.galerinio.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.galerinio.data.local.entity.CloudAccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudAccountDao {

    @Query("SELECT * FROM cloud_accounts ORDER BY display_name ASC")
    fun getAllFlow(): Flow<List<CloudAccountEntity>>

    @Query("SELECT * FROM cloud_accounts ORDER BY display_name ASC")
    suspend fun getAll(): List<CloudAccountEntity>

    @Query("SELECT * FROM cloud_accounts WHERE id = :id")
    suspend fun getById(id: Long): CloudAccountEntity?

    @Query("SELECT * FROM cloud_accounts WHERE is_enabled = 1")
    suspend fun getEnabled(): List<CloudAccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: CloudAccountEntity): Long

    @Update
    suspend fun update(account: CloudAccountEntity)

    @Delete
    suspend fun delete(account: CloudAccountEntity)

    @Query("DELETE FROM cloud_accounts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE cloud_accounts SET last_sync_timestamp = :timestamp WHERE id = :id")
    suspend fun updateLastSync(id: Long, timestamp: Long)
}

