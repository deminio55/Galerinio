package com.example.galerinio.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_log",
    foreignKeys = [
        ForeignKey(
            entity = CloudAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["cloud_account_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("cloud_account_id"),
        Index(value = ["cloud_account_id", "local_file_path"], unique = true)
    ]
)
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "cloud_account_id")
    val cloudAccountId: Long,

    @ColumnInfo(name = "local_file_path")
    val localFilePath: String,

    @ColumnInfo(name = "remote_file_name")
    val remoteFileName: String,

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "file_size")
    val fileSize: Long = 0L
)

