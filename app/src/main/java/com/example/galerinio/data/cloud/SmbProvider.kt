package com.example.galerinio.data.cloud

import android.util.Log
import com.example.galerinio.domain.cloud.CloudStorageProvider
// ...existing imports...
import com.example.galerinio.domain.model.SmbCredentials
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.EnumSet

class SmbProvider(private val credentials: SmbCredentials) : CloudStorageProvider {

    override val providerName: String = "SMB"

    companion object {
        private const val TAG = "SmbProvider"
    }

    /**
     * Parse remoteFolderPath into share name and subfolder.
     * Examples:
     *   "homes/Galerinio" -> share="homes", subfolder="Galerinio"
     *   "Galerinio" -> share="Galerinio", subfolder=""
     *   "/homes/Demis/Galerinio" -> share="homes", subfolder="Demis/Galerinio"
     */
    private fun parseShareAndPath(remoteFolderPath: String): Pair<String, String> {
        val parts = remoteFolderPath.trim('/', '\\').split('/', '\\').filter { it.isNotBlank() }
        return if (parts.size >= 2) {
            parts[0] to parts.drop(1).joinToString("\\")
        } else if (parts.size == 1) {
            parts[0] to ""
        } else {
            "Galerinio" to ""
        }
    }

    private fun connect(remoteFolderPath: String = "Galerinio"): Triple<SMBClient, DiskShare, String> {
        val (shareName, subfolder) = parseShareAndPath(remoteFolderPath)
        Log.d(TAG, "Connecting to ${credentials.host}, share='$shareName', subfolder='$subfolder'")

        val client = SMBClient()
        val connection = client.connect(credentials.host)
        val authContext = AuthenticationContext(
            credentials.login,
            credentials.password.toCharArray(),
            credentials.workgroup
        )
        val session = connection.authenticate(authContext)

        // Try connecting to the share
        val share = try {
            session.connectShare(shareName) as DiskShare
        } catch (e: Exception) {
            Log.w(TAG, "Failed to connect to share '$shareName': ${e.message}")
            // Try common share names
            val fallbacks = listOf("homes", "home", "Galerinio", "data")
                .filter { it != shareName }
            var fallbackShare: DiskShare? = null
            for (fb in fallbacks) {
                try {
                    fallbackShare = session.connectShare(fb) as DiskShare
                    Log.i(TAG, "Connected to fallback share: $fb")
                    break
                } catch (_: Exception) { }
            }
            fallbackShare ?: throw e
        }
        return Triple(client, share, subfolder)
    }

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val (client, share, _) = connect()
            Log.i(TAG, "testConnection: success")
            share.close()
            client.close()
            true
        }.getOrElse { e ->
            Log.e(TAG, "testConnection failed: ${e.message}", e)
            false
        }
    }

    override suspend fun ensureRemoteFolder(remoteFolderPath: String): Unit = withContext(Dispatchers.IO) {
        val (client, share, subfolder) = connect(remoteFolderPath)
        try {
            if (subfolder.isNotEmpty() && !share.folderExists(subfolder)) {
                // Create recursively
                val parts = subfolder.split('\\').filter { it.isNotBlank() }
                var current = ""
                for (part in parts) {
                    current = if (current.isEmpty()) part else "$current\\$part"
                    if (!share.folderExists(current)) {
                        share.mkdir(current)
                        Log.i(TAG, "Created folder: $current")
                    }
                }
            }
        } finally {
            share.close()
            client.close()
        }
    }

    override suspend fun listRemoteFiles(remoteFolderPath: String): Set<String> = withContext(Dispatchers.IO) {
        val (client, share, subfolder) = connect(remoteFolderPath)
        try {
            val path = subfolder.ifEmpty { "" }
            if (path.isNotEmpty() && !share.folderExists(path)) return@withContext emptySet()
            val listPath = path.ifEmpty { "" }
            share.list(listPath)
                .filter { info ->
                    info.fileName != "." && info.fileName != ".." &&
                        (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) == 0L
                }
                .map { it.fileName }
                .filter { it != "." && it != ".." }
                .toSet()
        } finally {
            share.close()
            client.close()
        }
    }

    override suspend fun uploadFile(
        remoteFolderPath: String,
        fileName: String,
        mimeType: String,
        inputStream: InputStream
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val (client, share, subfolder) = connect(remoteFolderPath)
            try {
                val path = if (subfolder.isEmpty()) fileName else "$subfolder\\$fileName"
                val file = share.openFile(
                    path,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                )
                file.outputStream.use { out ->
                    inputStream.copyTo(out)
                }
                file.close()
                path
            } finally {
                share.close()
                client.close()
            }
        }.getOrNull()
    }

    override suspend fun deleteFile(remoteFolderPath: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val (client, share, subfolder) = connect(remoteFolderPath)
            try {
                val path = if (subfolder.isEmpty()) fileName else "$subfolder\\$fileName"
                share.rm(path)
                true
            } finally {
                share.close()
                client.close()
            }
        }.getOrDefault(false)
    }
}

