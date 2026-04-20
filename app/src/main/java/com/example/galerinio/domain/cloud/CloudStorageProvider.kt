package com.example.galerinio.domain.cloud

import java.io.InputStream

/**
 * Common interface for all cloud storage providers.
 * Each implementation handles a specific protocol (Google Drive, WebDAV, SMB, SFTP).
 */
interface CloudStorageProvider {

    /** Human-readable provider name. */
    val providerName: String

    /**
     * Test the connection with the current credentials.
     * @return true if the connection is successful.
     */
    suspend fun testConnection(): Boolean

    /**
     * Ensure the remote folder exists, creating it if necessary.
     * @param remoteFolderPath The path of the remote folder.
     */
    suspend fun ensureRemoteFolder(remoteFolderPath: String)

    /**
     * List file names in the remote folder.
     * @param remoteFolderPath The path of the remote folder.
     * @return Set of file names.
     */
    suspend fun listRemoteFiles(remoteFolderPath: String): Set<String>

    /**
     * Upload a file to the remote folder.
     * @param remoteFolderPath The remote folder path.
     * @param fileName The file name.
     * @param mimeType MIME type of the file.
     * @param inputStream The file content.
     * @return Remote file identifier (path or ID), or null if failed.
     */
    suspend fun uploadFile(
        remoteFolderPath: String,
        fileName: String,
        mimeType: String,
        inputStream: InputStream
    ): String?

    /**
     * Delete a file from the remote folder.
     * @param remoteFolderPath The remote folder path.
     * @param fileName The file name.
     * @return true if deleted successfully.
     */
    suspend fun deleteFile(remoteFolderPath: String, fileName: String): Boolean

    /**
     * Release any resources held by this provider (connections, etc.).
     */
    suspend fun close() {}
}

