package com.example.galerinio.data.sync

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.galerinio.data.cloud.CloudProviderFactory
import com.example.galerinio.data.local.GalerioDatabase
import com.example.galerinio.data.repository.CloudAccountRepository
import com.example.galerinio.domain.model.SyncMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * WorkManager worker that performs cloud sync for a single account.
 * Supports both Backup (one-way upload) and Mirror (two-way, with remote deletion) modes.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "SyncWorker"
        const val KEY_ACCOUNT_ID = "account_id"
        /** Maximum number of retries before giving up */
        private const val MAX_RETRIES = 3
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val accountId = inputData.getLong(KEY_ACCOUNT_ID, -1L)
        if (accountId <= 0L) {
            Log.e(TAG, "Invalid account ID: $accountId")
            return@withContext Result.failure()
        }

        val db = GalerioDatabase.getInstance(applicationContext)
        val repository = CloudAccountRepository(db.cloudAccountDao(), db.syncLogDao())
        val account = repository.getAccountById(accountId)
        if (account == null) {
            Log.e(TAG, "Account not found: $accountId")
            return@withContext Result.failure()
        }
        if (!account.isEnabled) {
            Log.i(TAG, "Account ${account.displayName} is disabled, skipping sync")
            return@withContext Result.success()
        }

        Log.i(TAG, "Starting sync for account: ${account.displayName} (${account.providerType}), attempt ${runAttemptCount + 1}/$MAX_RETRIES")

        val provider = try {
            CloudProviderFactory.create(account.providerType, account.credentialsJson, applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create provider for account ${account.displayName}", e)
            return@withContext retryOrFail("Failed to create provider")
        }

        try {
            // 1. Ensure remote folder exists
            Log.d(TAG, "Ensuring remote folder: ${account.remoteFolderPath}")
            try {
                provider.ensureRemoteFolder(account.remoteFolderPath)
            } catch (e: Exception) {
                Log.e(TAG, "ensureRemoteFolder failed: ${e.javaClass.simpleName}: ${e.message}", e)
                // Try to continue without creating folder — it may already exist
                Log.w(TAG, "Continuing sync despite folder creation failure...")
            }

            // 2. Get list of local media files
            val localFiles = queryLocalMediaFiles()
            Log.i(TAG, "Found ${localFiles.size} local media files")

            if (localFiles.isEmpty()) {
                Log.w(TAG, "No local media files found — check storage permissions")
                return@withContext Result.success()
            }

            // 3. Get list of already synced files
            val syncedPaths = repository.getSyncedFilePaths(accountId).toSet()
            Log.d(TAG, "Already synced: ${syncedPaths.size} files")

            // 4. Get list of remote files
            val remoteFiles = provider.listRemoteFiles(account.remoteFolderPath)
            Log.d(TAG, "Remote files: ${remoteFiles.size}")

            // 5. Upload new files (not yet synced)
            var uploadCount = 0
            var skipCount = 0
            var errorCount = 0
            for (mediaFile in localFiles) {
                if (mediaFile.localPath in syncedPaths) {
                    skipCount++
                    continue
                }
                if (mediaFile.name in remoteFiles) {
                    // Already on server, just record it
                    repository.markFileSynced(accountId, mediaFile.localPath, mediaFile.name, mediaFile.size)
                    skipCount++
                    continue
                }

                // Open InputStream via ContentResolver (scoped storage compatible)
                val inputStream = try {
                    applicationContext.contentResolver.openInputStream(mediaFile.contentUri)
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot open file: ${mediaFile.name} (${mediaFile.contentUri}): ${e.message}")
                    errorCount++
                    continue
                }

                if (inputStream == null) {
                    Log.e(TAG, "InputStream is null for: ${mediaFile.name}")
                    errorCount++
                    continue
                }

                val mimeType = mediaFile.mimeType ?: "application/octet-stream"
                val result = inputStream.use { stream ->
                    provider.uploadFile(account.remoteFolderPath, mediaFile.name, mimeType, stream)
                }
                if (result != null) {
                    repository.markFileSynced(accountId, mediaFile.localPath, mediaFile.name, mediaFile.size)
                    uploadCount++
                } else {
                    errorCount++
                }
            }

            // 6. Mirror mode: delete remote files that were deleted locally
            if (account.syncMode == SyncMode.MIRROR) {
                val localPathSet = localFiles.map { it.localPath }.toSet()
                val syncLogs = repository.getSyncLogs(accountId)
                var deleteCount = 0
                for (log in syncLogs) {
                    if (log.localFilePath !in localPathSet) {
                        val deleted = provider.deleteFile(account.remoteFolderPath, log.remoteFileName)
                        if (deleted) {
                            repository.removeSyncLog(accountId, log.localFilePath)
                            deleteCount++
                        }
                    }
                }
                if (deleteCount > 0) Log.i(TAG, "Mirror mode: deleted $deleteCount remote files")
            }

            // 7. Update last sync timestamp
            repository.updateLastSync(accountId, System.currentTimeMillis())

            Log.i(TAG, "Sync complete for ${account.displayName}: uploaded=$uploadCount, skipped=$skipCount, errors=$errorCount")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Sync failed for ${account.displayName}: ${e.javaClass.simpleName}: ${e.message}", e)
            when {
                isNetworkError(e) -> retryOrFail("Network error: ${e.message}")
                else -> Result.failure()
            }
        } finally {
            try { provider.close() } catch (_: Exception) {}
        }
    }

    /**
     * Returns Result.retry() if within retry limit, otherwise Result.failure().
     */
    private fun retryOrFail(reason: String): Result {
        return if (runAttemptCount < MAX_RETRIES) {
            Log.w(TAG, "Retrying ($runAttemptCount/$MAX_RETRIES): $reason")
            Result.retry()
        } else {
            Log.e(TAG, "Max retries ($MAX_RETRIES) reached, giving up: $reason")
            Result.failure()
        }
    }

    /**
     * Check whether the exception is a transient network error worth retrying.
     */
    private fun isNetworkError(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is SocketTimeoutException ||
                cause is UnknownHostException ||
                cause is java.net.ConnectException ||
                cause is java.io.IOException && cause.message?.contains("timeout", ignoreCase = true) == true
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun queryLocalMediaFiles(): List<LocalMediaFile> {
        val files = mutableListOf<LocalMediaFile>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATA
        )

        // Images
        try {
            applicationContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    files.add(
                        LocalMediaFile(
                            contentUri = contentUri,
                            localPath = cursor.getString(dataCol) ?: contentUri.toString(),
                            name = name,
                            mimeType = cursor.getString(mimeCol),
                            size = cursor.getLong(sizeCol)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query images: ${e.message}", e)
        }

        // Videos
        try {
            applicationContext.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                    )
                    files.add(
                        LocalMediaFile(
                            contentUri = contentUri,
                            localPath = cursor.getString(dataCol) ?: contentUri.toString(),
                            name = name,
                            mimeType = cursor.getString(mimeCol),
                            size = cursor.getLong(sizeCol)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query videos: ${e.message}", e)
        }

        return files
    }

    private data class LocalMediaFile(
        val contentUri: Uri,
        val localPath: String,
        val name: String,
        val mimeType: String?,
        val size: Long
    )
}
