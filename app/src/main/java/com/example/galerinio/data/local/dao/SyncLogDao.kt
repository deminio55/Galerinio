package com.example.galerinio.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.galerinio.data.local.entity.SyncLogEntity

@Dao
interface SyncLogDao {

    @Query("SELECT * FROM sync_log WHERE cloud_account_id = :accountId")
    suspend fun getByAccountId(accountId: Long): List<SyncLogEntity>

    @Query("SELECT local_file_path FROM sync_log WHERE cloud_account_id = :accountId")
    suspend fun getSyncedFilePathsByAccount(accountId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: SyncLogEntity): Long

    @Query("DELETE FROM sync_log WHERE cloud_account_id = :accountId AND local_file_path = :filePath")
    suspend fun deleteByPath(accountId: Long, filePath: String)

    @Query("DELETE FROM sync_log WHERE cloud_account_id = :accountId")
    suspend fun deleteByAccountId(accountId: Long)

    @Query("SELECT COUNT(*) FROM sync_log WHERE cloud_account_id = :accountId")
    suspend fun countByAccount(accountId: Long): Int
}

