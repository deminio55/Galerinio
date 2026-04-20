package com.example.galerinio.data.cloud

import android.content.Context
import android.util.Log
import com.example.galerinio.domain.cloud.CloudStorageProvider
import com.example.galerinio.domain.model.GoogleDriveCredentials
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

class GoogleDriveProvider(
    private val credentials: GoogleDriveCredentials,
    private val context: Context? = null
) : CloudStorageProvider {

    override val providerName: String = "Google Drive"

    private fun createService(): Drive {
        val httpRequestInitializer = if (context != null && credentials.accountEmail.isNotBlank()) {
            // Use GoogleAccountCredential for automatic token management
            GoogleAccountCredential.usingOAuth2(
                context, listOf(DriveScopes.DRIVE_FILE)
            ).apply {
                selectedAccountName = credentials.accountEmail
            }
        } else if (credentials.accessToken.isNotBlank()) {
            @Suppress("DEPRECATION")
            GoogleCredential().setAccessToken(credentials.accessToken)
        } else {
            null
        }

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            httpRequestInitializer
        )
            .setApplicationName("Galerinio")
            .build()
    }

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val service = createService()
            Log.d("GoogleDriveProvider", "Testing connection for email=${credentials.accountEmail}, hasToken=${credentials.accessToken.isNotBlank()}, hasContext=${context != null}")
            service.files().list().setPageSize(1).execute()
            Log.i("GoogleDriveProvider", "Test connection SUCCESS")
            true
        } catch (e: UserRecoverableAuthIOException) {
            Log.w("GoogleDriveProvider", "UserRecoverableAuthIOException — consent needed", e)
            throw e
        } catch (e: Exception) {
            Log.e("GoogleDriveProvider", "testConnection FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            // Check if wrapped UserRecoverableAuthIOException
            var cause: Throwable? = e.cause
            repeat(5) {
                if (cause is UserRecoverableAuthIOException) {
                    throw cause as UserRecoverableAuthIOException
                }
                cause = cause?.cause
            }
            false
        }
    }

    override suspend fun ensureRemoteFolder(remoteFolderPath: String) = withContext(Dispatchers.IO) {
        val service = createService()
        val folderName = remoteFolderPath.trim('/')
        findOrCreateFolder(service, folderName)
        Unit
    }

    override suspend fun listRemoteFiles(remoteFolderPath: String): Set<String> = withContext(Dispatchers.IO) {
        val service = createService()
        val folderName = remoteFolderPath.trim('/')
        val folderId = findFolderId(service, folderName) ?: return@withContext emptySet()
        val result = service.files().list()
            .setQ("'$folderId' in parents and trashed = false")
            .setFields("files(name)")
            .setPageSize(1000)
            .execute()
        result.files.map { it.name }.toSet()
    }

    override suspend fun uploadFile(
        remoteFolderPath: String,
        fileName: String,
        mimeType: String,
        inputStream: InputStream
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val service = createService()
            val folderName = remoteFolderPath.trim('/')
            val folderId = findOrCreateFolder(service, folderName)
            val fileMetadata = com.google.api.services.drive.model.File().apply {
                name = fileName
                parents = listOf(folderId)
            }
            val mediaContent = InputStreamContent(mimeType, inputStream)
            val uploaded = service.files().create(fileMetadata, mediaContent)
                .setFields("id")
                .execute()
            uploaded.id
        }.getOrNull()
    }

    override suspend fun deleteFile(remoteFolderPath: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val service = createService()
            val folderName = remoteFolderPath.trim('/')
            val folderId = findFolderId(service, folderName) ?: return@runCatching false
            val result = service.files().list()
                .setQ("'$folderId' in parents and name = '$fileName' and trashed = false")
                .setFields("files(id)")
                .setPageSize(1)
                .execute()
            val fileId = result.files.firstOrNull()?.id ?: return@runCatching false
            service.files().delete(fileId).execute()
            true
        }.getOrDefault(false)
    }

    private fun findFolderId(service: Drive, folderName: String): String? {
        val result = service.files().list()
            .setQ("name = '$folderName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
            .setFields("files(id)")
            .setPageSize(1)
            .execute()
        return result.files.firstOrNull()?.id
    }

    private fun findOrCreateFolder(service: Drive, folderName: String): String {
        val existingId = findFolderId(service, folderName)
        if (existingId != null) return existingId
        val folderMetadata = com.google.api.services.drive.model.File().apply {
            name = folderName
            mimeType = "application/vnd.google-apps.folder"
        }
        val created = service.files().create(folderMetadata)
            .setFields("id")
            .execute()
        return created.id
    }
}
