package com.example.galerinio.domain.model

/**
 * Types of cloud storage providers.
 */
enum class CloudProviderType {
    GOOGLE_DRIVE,
    WEBDAV,
    SMB,
    SFTP
}

/**
 * Synchronization mode for a cloud account.
 */
enum class SyncMode {
    /** Mirror: deletions on phone are replicated to the server. */
    MIRROR,
    /** Backup: files are copied to the server and remain even if deleted locally. */
    BACKUP
}

/**
 * Domain model for a cloud storage account.
 */
data class CloudAccount(
    val id: Long = 0L,
    val providerType: CloudProviderType,
    val displayName: String,
    val syncMode: SyncMode = SyncMode.BACKUP,
    val syncOnlyWifi: Boolean = true,
    val syncOnlyCharging: Boolean = false,
    val remoteFolderPath: String = "/Galerinio",
    val isEnabled: Boolean = true,
    val lastSyncTimestamp: Long = 0L,
    /** Provider-specific credentials serialized as JSON. */
    val credentialsJson: String = ""
)

/**
 * Credentials for WebDAV provider.
 */
data class WebDavCredentials(
    val url: String,
    val login: String,
    val password: String
)

/**
 * Credentials for SMB provider.
 */
data class SmbCredentials(
    val host: String,
    val workgroup: String,
    val login: String,
    val password: String
)

/**
 * Credentials for SFTP provider.
 */
data class SftpCredentials(
    val host: String,
    val port: Int = 22,
    val login: String,
    val password: String = "",
    val privateKey: String = ""
)


/**
 * Credentials for Google Drive (OAuth token).
 */
data class GoogleDriveCredentials(
    val accountEmail: String,
    val accessToken: String = "",
    val refreshToken: String = ""
)

