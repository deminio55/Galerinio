package com.example.galerinio.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cloud_accounts")
data class CloudAccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "provider_type")
    val providerType: String,           // GOOGLE_DRIVE, WEBDAV, SMB, SFTP

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "sync_mode")
    val syncMode: String = "BACKUP",    // MIRROR or BACKUP

    @ColumnInfo(name = "sync_only_wifi")
    val syncOnlyWifi: Boolean = true,

    @ColumnInfo(name = "sync_only_charging")
    val syncOnlyCharging: Boolean = false,

    @ColumnInfo(name = "remote_folder_path")
    val remoteFolderPath: String = "/Galerinio",

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "last_sync_timestamp")
    val lastSyncTimestamp: Long = 0L,

    @ColumnInfo(name = "credentials_json")
    val credentialsJson: String = ""
)

