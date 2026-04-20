package com.example.galerinio.data.repository

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.galerinio.data.local.dao.TrashDao
import com.example.galerinio.data.local.entity.TrashEntity
import com.example.galerinio.domain.model.MediaModel
import com.example.galerinio.domain.model.TrashModel
import com.example.galerinio.domain.repository.TrashRepository
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Exception thrown when user permission is required to delete a file on Android 11+
 */
class UserPermissionRequiredException(
    val pendingIntent: PendingIntent,
    val media: MediaModel
) : Exception("User permission required to delete file")

/**
 * Result of trash operation
 */
private enum class TrashResult {
    SUCCESS,         // Файл успешно перемещен в корзину
    NEED_PERMISSION, // Требуется разрешение пользователя
    FAILED           // Операция не удалась
}

class TrashRepositoryImpl(
    private val trashDao: TrashDao,
    private val trashRootDir: File,
    private val context: Context? = null
) : TrashRepository {

    override fun getTrashItems(): Flow<List<TrashModel>> {
        return trashDao.getAllFlow().map { entities -> entities.map { it.toModel() } }
    }

    override suspend fun moveToTrash(media: MediaModel): Boolean {
        Log.d(TAG, "moveToTrash: Called for file: ${media.fileName}, path: ${media.filePath}")

        // ВАЖНО: На кастомных прошивках (nubia, xiaomi и др.) MediaStore.IS_TRASHED часто не работает
        // Поэтому ВСЕГДА используем локальную корзину для надежности


        // Для Android 10 и ниже используем старый метод с копированием в локальную корзину
        // Проверка: если путь является URI, получаем реальный путь из MediaStore
        val realPath = if (media.filePath.startsWith("content://")) {
            Log.w(TAG, "moveToTrash: Detected URI instead of file path: ${media.filePath}")
            Log.w(TAG, "moveToTrash: Attempting to resolve real path from MediaStore")

            val mediaUri = if (media.isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            // Извлекаем ID из URI (последний сегмент)
            val id = media.filePath.substringAfterLast("/").toLongOrNull()

            if (id == null) {
                Log.e(TAG, "moveToTrash: Failed to extract ID from URI: ${media.filePath}")
                return false
            }

            // Получаем реальный путь из MediaStore
            getRealPathFromMediaStore(id, mediaUri, context) ?: run {
                Log.e(TAG, "moveToTrash: Failed to get real path for URI: ${media.filePath}")
                return false
            }
        } else {
            media.filePath
        }

        Log.d(TAG, "moveToTrash: Using path: $realPath")
        val source = File(realPath)

        // Проверяем существование файла
        if (!source.exists()) {
            Log.e(TAG, "moveToTrash: Source file does not exist: $realPath")
            return false
        }

        if (!source.isFile) {
            Log.e(TAG, "moveToTrash: Source is not a file: $realPath")
            return false
        }

        ensureTrashDir()
        val destination = File(trashRootDir, uniqueTrashName(source.name))

        return try {
            // Копируем файл в корзину
            Log.d(TAG, "moveToTrash: Copying file to trash: ${source.absolutePath} -> ${destination.absolutePath}")
            source.copyTo(destination, overwrite = false)
            
            // Сначала сохраняем информацию в БД корзины (ДО удаления оригинала)
            trashDao.deleteByMediaId(media.id)
            trashDao.insert(
                TrashEntity(
                    mediaId = media.id,
                    fileName = media.fileName,
                    originalPath = media.filePath,
                    trashPath = destination.absolutePath,
                    mimeType = media.mimeType,
                    size = media.size,
                    dateDeleted = System.currentTimeMillis(),
                    isVideo = media.isVideo,
                    originalDateAdded = media.dateAdded,
                    originalDateModified = media.dateModified
                )
            )
            Log.d(TAG, "moveToTrash: File copied to trash and saved to DB, now attempting to delete original")

            // Удаляем оригинал используя правильный API в зависимости от версии Android
            val deleted = if (context != null) {
                // Пытаемся удалить через MediaStore API
                Log.d(TAG, "moveToTrash: Using MediaStore API for deletion (API ${Build.VERSION.SDK_INT})")
                val mediaStoreDeleted = deleteViaMediaStore(media, context)

                if (mediaStoreDeleted) {
                    true
                } else {
                    // Если MediaStore не сработал, пробуем прямое удаление (для Android 9 и ниже)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        Log.w(TAG, "moveToTrash: MediaStore deletion failed, trying direct deletion (API < 29)")
                        val directDeleted = source.delete()
                        if (!directDeleted) {
                            Log.e(TAG, "moveToTrash: Direct deletion also failed for: ${source.absolutePath}")
                        }
                        directDeleted
                    } else {
                        // На Android 10+ прямое удаление не сработает из-за Scoped Storage
                        Log.e(TAG, "moveToTrash: MediaStore deletion failed on API ${Build.VERSION.SDK_INT}, cannot use direct deletion")
                        false
                    }
                }
            } else {
                // Fallback: прямое удаление файла (только для старых Android)
                Log.d(TAG, "moveToTrash: Context is null, using direct file deletion")
                val result = source.delete()
                if (!result) {
                    Log.e(TAG, "moveToTrash: Failed to delete file directly: ${source.absolutePath}")
                }
                result
            }

            if (!deleted) {
                // Проверяем, есть ли pending permission request
                val hasPendingPermission = Companion.pendingUserPermissionRequest != null
                
                if (hasPendingPermission) {
                    // Если требуется разрешение пользователя, НЕ откатываем - файл уже в корзине и БД
                    Log.w(TAG, "moveToTrash: Deletion requires user permission, file kept in trash, returning false to trigger permission request")
                    return false
                } else {
                    // Если не удалось удалить оригинал и нет pending request, откатываем всё
                    Log.e(TAG, "moveToTrash: Failed to delete original file and no permission request, rolling back")
                    destination.delete()
                    trashDao.deleteByMediaId(media.id)
                    return false
                }
            }

            Log.d(TAG, "moveToTrash: Successfully moved to trash: ${media.fileName}")
            android.util.Log.e("TRASH_DEBUG", "=== STEP 6: Success - Saved to DB ===")
            android.util.Log.e("TRASH_DEBUG", "=== RETURNING TRUE - File successfully moved to trash ===")
            true
        } catch (e: Exception) {
            Log.e(TAG, "moveToTrash: Exception occurred", e)
            android.util.Log.e("TRASH_DEBUG", "=== EXCEPTION occurred ===", e)
            android.util.Log.e("TRASH_DEBUG", "Exception: ${e.message}")
            android.util.Log.e("TRASH_DEBUG", "Destination exists: ${destination.exists()}")
            val rollbackSuccess = destination.delete()
            android.util.Log.e("TRASH_DEBUG", "Rollback delete() returned: $rollbackSuccess")
            android.util.Log.e("TRASH_DEBUG", "=== RETURNING FALSE - Exception ===")
            false
        }
    }

    /**
     * Завершает удаление оригинала после подтверждения пользователем системного запроса.
     * Используется в UI-слое после ActivityResult(RESULT_OK).
     */
    suspend fun completePendingDeleteAfterPermission(media: MediaModel): Boolean {
        if (context == null) return false

        val deletedNow = deleteViaMediaStore(media, context)
        if (deletedNow) return true

        // Для некоторых потоков (например createDeleteRequest) файл уже может быть удален системой.
        val mediaUri = if (media.isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val stillExistsInMediaStore = findMediaStoreIdByPath(media.filePath, mediaUri, context) != null
        return !stillExistsInMediaStore
    }

    /**
     * Перемещает файл в системную корзину MediaStore (для Android 11+)
     * Возвращает TrashResult: SUCCESS, NEED_PERMISSION или FAILED
     */
    private fun moveToTrashViaMediaStore(media: MediaModel, context: Context): TrashResult {
        return try {
            val mediaUri = if (media.isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val mediaStoreId = findMediaStoreIdByPath(media.filePath, mediaUri, context)
            if (mediaStoreId == null) {
                Log.e(TAG, "moveToTrashViaMediaStore: Could not find MediaStore ID for: ${media.filePath}")
                return TrashResult.FAILED
            }

            val contentUri = ContentUris.withAppendedId(mediaUri, mediaStoreId)
            Log.d(TAG, "moveToTrashViaMediaStore: Attempting to trash URI: $contentUri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    // Пытаемся напрямую переместить в корзину (работает для файлов, созданных приложением)
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_TRASHED, 1)
                    }
                    Log.d(TAG, "moveToTrashViaMediaStore: Trying direct IS_TRASHED update")
                    val updated = context.contentResolver.update(contentUri, values, null, null)
                    
                    if (updated > 0) {
                        Log.d(TAG, "moveToTrashViaMediaStore: Successfully moved to trash without user permission")
                        return TrashResult.SUCCESS
                    } else {
                        Log.w(TAG, "moveToTrashViaMediaStore: Direct update returned 0 rows, may need permission")
                    }
                } catch (e: RecoverableSecurityException) {
                    // Файл не принадлежит приложению - нужно разрешение пользователя
                    Log.d(TAG, "moveToTrashViaMediaStore: RecoverableSecurityException caught - storing PendingIntent", e)
                    pendingUserPermissionRequest = e.userAction.actionIntent to media
                    Log.d(TAG, "moveToTrashViaMediaStore: pendingUserPermissionRequest set, returning NEED_PERMISSION")
                    return TrashResult.NEED_PERMISSION
                } catch (e: SecurityException) {
                    Log.d(TAG, "moveToTrashViaMediaStore: SecurityException - trying createTrashRequest", e)
                } catch (e: IllegalArgumentException) {
                    // IS_TRASHED column не поддерживается на этом устройстве
                    Log.w(TAG, "moveToTrashViaMediaStore: IS_TRASHED not supported on this device", e)
                    return TrashResult.FAILED
                } catch (e: Exception) {
                    // Другие ошибки (например, SQLiteException если колонка не существует)
                    Log.w(TAG, "moveToTrashViaMediaStore: IS_TRASHED update failed, possibly not supported", e)
                    return TrashResult.FAILED
                }
                
                // Если не удалось переместить напрямую, запрашиваем разрешение через createTrashRequest
                try {
                    Log.d(TAG, "moveToTrashViaMediaStore: Creating trash request via MediaStore.createTrashRequest")
                    val pendingIntent = MediaStore.createTrashRequest(
                        context.contentResolver,
                        listOf(contentUri),
                        true
                    )
                    // UI слой подхватит этот pending intent и покажет системный диалог.
                    pendingUserPermissionRequest = pendingIntent to media
                    Log.d(TAG, "moveToTrashViaMediaStore: pendingUserPermissionRequest set via createTrashRequest, returning NEED_PERMISSION")
                    return TrashResult.NEED_PERMISSION
                } catch (e: UnsupportedOperationException) {
                    // createTrashRequest не поддерживается на этом устройстве
                    Log.w(TAG, "moveToTrashViaMediaStore: createTrashRequest not supported on this device", e)
                    return TrashResult.FAILED
                } catch (e: Exception) {
                    Log.w(TAG, "moveToTrashViaMediaStore: createTrashRequest failed", e)
                    return TrashResult.FAILED
                }
            }

            Log.w(TAG, "moveToTrashViaMediaStore: Android version < R, cannot use system trash")
            TrashResult.FAILED
        } catch (e: Exception) {
            Log.e(TAG, "moveToTrashViaMediaStore: Unexpected error", e)
            TrashResult.FAILED
        }
    }
    
    /**
     * Сохраняет информацию о файле в базе данных корзины
     * (для файлов, перемещенных в системную корзину на Android 11+)
     */
    private suspend fun saveToTrashDatabase(media: MediaModel, trashPath: String) {
        try {
            trashDao.insert(
                TrashEntity(
                    mediaId = media.id,
                    fileName = media.fileName,
                    originalPath = media.filePath,
                    trashPath = trashPath,  // Для системной корзины используем специальный маркер
                    mimeType = media.mimeType,
                    size = media.size,
                    dateDeleted = System.currentTimeMillis(),
                    isVideo = media.isVideo,
                    originalDateAdded = media.dateAdded,
                    originalDateModified = media.dateModified
                )
            )
            Log.d(TAG, "saveToTrashDatabase: Saved to trash DB: ${media.fileName}")
        } catch (e: Exception) {
            Log.e(TAG, "saveToTrashDatabase: Failed to save to DB", e)
        }
    }

    /**
     * Удаляет файл через MediaStore API (для Android 10+)
     * 
     * В MediaScanner ID кодируется так:
     * - Для изображений: положительный ID (storeId)
     * - Для видео: отрицательный ID (-storeId)
     */
    private fun deleteViaMediaStore(media: MediaModel, context: Context): Boolean {
        return try {
            val mediaUri = if (media.isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            Log.d(TAG, "deleteViaMediaStore: Attempting to delete ${if (media.isVideo) "video" else "image"}")
            Log.d(TAG, "deleteViaMediaStore: File path: ${media.filePath}")
            
            // Находим правильный MediaStore ID по пути файла
            val mediaStoreId = findMediaStoreIdByPath(media.filePath, mediaUri, context)

            if (mediaStoreId == null) {
                Log.e(TAG, "deleteViaMediaStore: Could not find MediaStore ID for path: ${media.filePath}")
                return false
            }

            Log.d(TAG, "deleteViaMediaStore: Found MediaStore ID: $mediaStoreId")
            val contentUri = ContentUris.withAppendedId(mediaUri, mediaStoreId)
            Log.d(TAG, "deleteViaMediaStore: Content URI: $contentUri")

            // Для Android 11+ (API 30+) используем batch deletion через createDeleteRequest
            // Но это требует Activity, поэтому пробуем прямое удаление с обработкой SecurityException
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Log.d(TAG, "deleteViaMediaStore: Android 11+ detected, trying direct deletion (may require user permission)")

                try {
                    val deleted = context.contentResolver.delete(contentUri, null, null)

                    if (deleted > 0) {
                        Log.d(TAG, "deleteViaMediaStore: Successfully deleted $deleted row(s)")
                        return true
                    } else {
                        Log.e(TAG, "deleteViaMediaStore: Delete returned 0 rows")
                        return false
                    }
                } catch (e: RecoverableSecurityException) {
                    // На Android 11+, если файл не принадлежит приложению, нужно использовать createDeleteRequest
                    Log.e(TAG, "deleteViaMediaStore: RecoverableSecurityException on Android 11+ - file not owned by app", e)
                    Log.e(TAG, "deleteViaMediaStore: This file requires user permission via createDeleteRequest")
                    
                    // Сохраняем PendingIntent для запроса разрешения пользователя
                    Companion.pendingUserPermissionRequest = e.userAction.actionIntent to media
                    return false
                } catch (e: SecurityException) {
                    // Обычная SecurityException без возможности восстановления
                    Log.e(TAG, "deleteViaMediaStore: SecurityException on Android 11+ - file not owned by app", e)
                    Log.e(TAG, "deleteViaMediaStore: This file requires user permission via createDeleteRequest")
                    return false
                }
            } else {
                // Для Android 10 (API 29) пробуем прямое удаление
                val deleted = context.contentResolver.delete(contentUri, null, null)

                if (deleted > 0) {
                    Log.d(TAG, "deleteViaMediaStore: Successfully deleted $deleted row(s)")
                    true
                } else {
                    Log.e(TAG, "deleteViaMediaStore: Delete returned 0 rows")
                    false
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "deleteViaMediaStore: SecurityException - need permission", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "deleteViaMediaStore: Exception occurred", e)
            false
        }
    }

    /**
     * Получает реальный путь к файлу из MediaStore по ID
     */
    private fun getRealPathFromMediaStore(mediaStoreId: Long, mediaUri: android.net.Uri, context: Context?): String? {
        if (context == null) {
            Log.e(TAG, "getRealPathFromMediaStore: Context is null")
            return null
        }
        
        return try {
            val contentUri = ContentUris.withAppendedId(mediaUri, mediaStoreId)
            Log.d(TAG, "getRealPathFromMediaStore: Querying path for ID=$mediaStoreId, URI=$contentUri")
            
            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DATA
                )
            } else {
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATA
                )
            }
            
            context.contentResolver.query(
                contentUri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    // Сначала пробуем получить полный путь из DATA
                    val dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (dataColumn >= 0) {
                        val dataPath = cursor.getString(dataColumn)
                        if (!dataPath.isNullOrBlank()) {
                            Log.d(TAG, "getRealPathFromMediaStore: Found path from DATA: $dataPath")
                            return dataPath
                        }
                    }
                    
                    // Для Android 10+ строим путь из RELATIVE_PATH + DISPLAY_NAME
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val relativePathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                        val displayNameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        
                        if (relativePathColumn >= 0 && displayNameColumn >= 0) {
                            val relativePath = cursor.getString(relativePathColumn)
                            val displayName = cursor.getString(displayNameColumn)
                            
                            if (!relativePath.isNullOrBlank() && !displayName.isNullOrBlank()) {
                                val fullPath = "/storage/emulated/0/" + relativePath + displayName
                                Log.d(TAG, "getRealPathFromMediaStore: Constructed path: $fullPath")
                                return fullPath
                            }
                        }
                    }
                }
            }
            
            Log.e(TAG, "getRealPathFromMediaStore: Could not get path for ID=$mediaStoreId")
            null
        } catch (e: Exception) {
            Log.e(TAG, "getRealPathFromMediaStore: Error getting path", e)
            null
        }
    }

    /**
     * Находит реальный путь файла по его имени в MediaStore
     * Используется как fallback когда не удается получить путь по ID
     */
    private fun findPathByFilename(fileName: String, mediaUri: android.net.Uri, context: Context?): String? {
        if (context == null) {
            Log.e(TAG, "findPathByFilename: Context is null")
            return null
        }
        
        return try {
            Log.d(TAG, "findPathByFilename: Searching for: $fileName")
            
            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DATA
                )
            } else {
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATA
                )
            }
            
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(fileName)
            
            context.contentResolver.query(
                mediaUri,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC" // Берем самый свежий
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    // Сначала пробуем получить путь из DATA
                    val dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (dataColumn >= 0) {
                        val dataPath = cursor.getString(dataColumn)
                        if (!dataPath.isNullOrBlank()) {
                            Log.d(TAG, "findPathByFilename: Found path from DATA: $dataPath")
                            return dataPath
                        }
                    }
                    
                    // Для Android 10+ строим путь из RELATIVE_PATH + DISPLAY_NAME
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val relativePathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                        val displayNameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        
                        if (relativePathColumn >= 0 && displayNameColumn >= 0) {
                            val relativePath = cursor.getString(relativePathColumn)
                            val displayName = cursor.getString(displayNameColumn)
                            
                            if (!relativePath.isNullOrBlank() && !displayName.isNullOrBlank()) {
                                val fullPath = "/storage/emulated/0/$relativePath$displayName"
                                Log.d(TAG, "findPathByFilename: Constructed path: $fullPath")
                                return fullPath
                            }
                        }
                    }
                }
            }
            
            Log.w(TAG, "findPathByFilename: File not found in MediaStore")
            null
        } catch (e: Exception) {
            Log.e(TAG, "findPathByFilename: Error finding path", e)
            null
        }
    }

    /**
     * Находит MediaStore ID по пути файла
     */
    private fun findMediaStoreIdByPath(filePath: String, mediaUri: android.net.Uri, context: Context): Long? {
        return try {
            val file = File(filePath)
            val fileName = file.name

            Log.d(TAG, "findMediaStoreIdByPath: Searching for file: $filePath")
            Log.d(TAG, "findMediaStoreIdByPath: File name: $fileName")
            Log.d(TAG, "findMediaStoreIdByPath: File exists: ${file.exists()}")

            // Сначала пробуем найти по полному пути (если доступен)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Для Android 9 и ниже ищем по полному пути
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATA
                )
                val selection = "${MediaStore.MediaColumns.DATA} = ?"
                val selectionArgs = arrayOf(filePath)

                Log.d(TAG, "findMediaStoreIdByPath: Searching by DATA (API < 29): $filePath")

                context.contentResolver.query(
                    mediaUri,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        Log.d(TAG, "findMediaStoreIdByPath: Found by DATA with ID=$id")
                        return id
                    }
                }
            } else {
                // Для Android 10+ пытаемся найти по DATA если доступен
                val projectionWithData = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.DISPLAY_NAME
                )

                // Сначала пробуем найти по имени файла
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(fileName)

                Log.d(TAG, "findMediaStoreIdByPath: Searching by DISPLAY_NAME (API >= 29): $fileName")

                context.contentResolver.query(
                    mediaUri,
                    projectionWithData,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)

                        if (dataColumn >= 0) {
                            val dataPath = cursor.getString(dataColumn)
                            Log.d(TAG, "findMediaStoreIdByPath: Checking ID=$id, path=$dataPath")

                            if (dataPath == filePath) {
                                Log.d(TAG, "findMediaStoreIdByPath: Found exact match with ID=$id")
                                return id
                            }
                        } else {
                            // DATA не доступен, возвращаем первый найденный файл с таким именем
                            Log.d(TAG, "findMediaStoreIdByPath: DATA column not available, using first match ID=$id")
                            return id
                        }
                    }

                    Log.w(TAG, "findMediaStoreIdByPath: Found files with name $fileName but none matched path")
                }
            }

            Log.w(TAG, "findMediaStoreIdByPath: File not found in MediaStore")
            null
        } catch (e: Exception) {
            Log.e(TAG, "findMediaStoreIdByPath: Error finding MediaStore ID", e)
            e.printStackTrace()
            null
        }
    }

    override suspend fun restore(itemId: Long): Boolean {
        val item = trashDao.getById(itemId) ?: return false
        
        Log.d(TAG, "restore: Restoring file: ${item.fileName}")
        Log.d(TAG, "restore: trashPath: ${item.trashPath}")
        Log.d(TAG, "restore: originalPath: ${item.originalPath}")

        // Для файлов в системной корзине (Android 11+)
        if (item.trashPath == "[SYSTEM_TRASH]" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && context != null) {
            return try {
                val mediaUri = if (item.isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                
                val mediaStoreId = findMediaStoreIdByPath(item.originalPath, mediaUri, context)
                if (mediaStoreId == null) {
                    Log.e(TAG, "restore: Could not find MediaStore ID for: ${item.originalPath}")
                    trashDao.deleteById(item.id)
                    return false
                }
                
                val contentUri = ContentUris.withAppendedId(mediaUri, mediaStoreId)
                
                // Восстанавливаем из системной корзины
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_TRASHED, 0)
                }
                val updated = context.contentResolver.update(contentUri, values, null, null)
                
                if (updated > 0) {
                    trashDao.deleteById(item.id)
                    Log.d(TAG, "restore: Successfully restored from system trash: ${item.fileName}")
                    true
                } else {
                    Log.e(TAG, "restore: Failed to restore from system trash")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "restore: Error restoring from system trash", e)
                false
            }
        }
        
        // Для файлов в локальной корзине
        val trashedFile = File(item.trashPath)
        if (!trashedFile.exists()) {
            Log.e(TAG, "restore: Trashed file does not exist: ${item.trashPath}")
            trashDao.deleteById(item.id)
            return false
        }

        // Получаем реальный путь для восстановления
        var realOriginalPath: String? = null
        var isFromExternalApp = false // Флаг для файлов из внешних приложений

        if (item.originalPath.startsWith("content://")) {
            // Если сохранён URI, получаем реальный путь
            Log.d(TAG, "restore: originalPath is URI: ${item.originalPath}")

            val mediaUri = if (item.isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            // Пытаемся извлечь ID из URI
            val id = item.originalPath.substringAfterLast("/").toLongOrNull()
            Log.d(TAG, "restore: Extracted ID from URI: $id")

            if (id != null && context != null) {
                realOriginalPath = getRealPathFromMediaStore(id, mediaUri, context)
                Log.d(TAG, "restore: Real path from MediaStore: $realOriginalPath")
            }

            // Если не удалось получить путь из MediaStore, попробуем найти по имени файла
            if (realOriginalPath == null && context != null) {
                Log.d(TAG, "restore: Trying to find path by filename: ${item.fileName}")
                realOriginalPath = findPathByFilename(item.fileName, mediaUri, context)
                Log.d(TAG, "restore: Path found by filename: $realOriginalPath")
            }
        } else {
            realOriginalPath = item.originalPath
            Log.d(TAG, "restore: Using original path as is: $realOriginalPath")
        }

        // Проверяем, является ли файл из внешнего приложения (WhatsApp, Telegram и т.д.)
        if (realOriginalPath != null) {
            val lowerPath = realOriginalPath.lowercase()
            isFromExternalApp = lowerPath.contains("/whatsapp/") ||
                               lowerPath.contains("/telegram/") ||
                               lowerPath.contains("/viber/") ||
                               lowerPath.contains("/signal/") ||
                               lowerPath.contains("/messenger/") ||
                               lowerPath.contains("/download/")
            Log.d(TAG, "restore: File is from external app: $isFromExternalApp")
        }

        // Проверяем, можем ли восстановить в оригинальное место
        val canRestoreToOriginal = realOriginalPath != null &&
                                    File(realOriginalPath).parentFile?.exists() == true &&
                                    File(realOriginalPath).parentFile?.canWrite() == true &&
                                    !isFromExternalApp // Файлы из внешних приложений восстанавливаем в Restored

        Log.d(TAG, "restore: Can restore to original location: $canRestoreToOriginal (path: $realOriginalPath)")

        if (!canRestoreToOriginal) {
            val reason = when {
                realOriginalPath == null -> "path is null"
                File(realOriginalPath).parentFile?.exists() != true -> "parent directory doesn't exist"
                File(realOriginalPath).parentFile?.canWrite() != true -> "parent directory not writable"
                isFromExternalApp -> "file from external app (WhatsApp, Telegram, etc.)"
                else -> "unknown"
            }
            Log.w(TAG, "restore: Cannot restore to original location, reason: $reason")
            Log.w(TAG, "restore: Original path: $realOriginalPath")
            Log.w(TAG, "restore: Parent exists: ${realOriginalPath?.let { File(it).parentFile?.exists() }}")
            Log.w(TAG, "restore: Parent writable: ${realOriginalPath?.let { File(it).parentFile?.canWrite() }}")

            // Восстанавливаем в DCIM/Restored как fallback
            val restoredDir = File("/storage/emulated/0/DCIM/Restored")
            if (!restoredDir.exists()) {
                val created = restoredDir.mkdirs()
                Log.d(TAG, "restore: Created Restored directory: $created")
            }

            val destination = File(restoredDir, "restored_${System.currentTimeMillis()}_${item.fileName}")
            Log.d(TAG, "restore: Restoring to fallback location: ${destination.absolutePath}")

            return try {
                trashedFile.copyTo(destination, overwrite = false)
                Log.d(TAG, "restore: File copied successfully to fallback location")

                if (!trashedFile.delete()) {
                    Log.w(TAG, "restore: Could not delete trashed file after restore")
                }

                trashDao.deleteById(item.id)
                Log.d(TAG, "restore: Deleted from trash database")

                context?.let {
                    if (item.originalDateModified > 0L) {
                        destination.setLastModified(item.originalDateModified)
                    }
                    scanFileWithOriginalDates(it, destination.absolutePath, item.originalDateAdded, item.originalDateModified)
                    Log.d(TAG, "restore: File scanned by MediaStore with original dates")
                }

                Log.i(TAG, "restore: ✅ Successfully restored to fallback location: ${destination.absolutePath}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "restore: ❌ Failed to restore to fallback location", e)
                e.printStackTrace()
                false
            }
        }

        // Восстановление в оригинальное место
        val originalTarget = File(realOriginalPath!!)
        val destination = if (originalTarget.exists()) {
            val newDest = File(originalTarget.parentFile, "restored_${System.currentTimeMillis()}_${originalTarget.name}")
            Log.d(TAG, "restore: Original file exists, using new name: ${newDest.absolutePath}")
            newDest
        } else {
            Log.d(TAG, "restore: Original file doesn't exist, restoring to: ${originalTarget.absolutePath}")
            originalTarget
        }

        val parentCreated = destination.parentFile?.mkdirs()
        Log.d(TAG, "restore: Parent directory created/exists: $parentCreated")

        return try {
            trashedFile.copyTo(destination, overwrite = false)
            Log.d(TAG, "restore: File copied successfully to original location")

            if (!trashedFile.delete()) {
                Log.w(TAG, "restore: Could not delete trashed file after restore")
            }

            trashDao.deleteById(item.id)
            Log.d(TAG, "restore: Deleted from trash database")

            context?.let {
                if (item.originalDateModified > 0L) {
                    destination.setLastModified(item.originalDateModified)
                }
                scanFileWithOriginalDates(it, destination.absolutePath, item.originalDateAdded, item.originalDateModified)
                Log.d(TAG, "restore: File scanned by MediaStore with original dates")
            }

            Log.i(TAG, "restore: ✅ Successfully restored to original location: ${destination.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "restore: ❌ Failed to restore file to original location", e)
            e.printStackTrace()
            false
        }
    }

    override suspend fun restoreBatch(ids: List<Long>): Pair<Int, Int> {
        var restored = 0
        var failed = 0
        ids.forEach { id ->
            if (restore(id)) restored++ else failed++
        }
        return Pair(restored, failed)
    }

    override suspend fun deleteForever(itemId: Long): Boolean {
        val item = trashDao.getById(itemId) ?: return false

        // Для файлов в системной корзине (Android 11+)
        if (item.trashPath == "[SYSTEM_TRASH]" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && context != null) {
            return try {
                val mediaUri = if (item.isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                val mediaStoreId = findMediaStoreIdByPath(item.originalPath, mediaUri, context)
                if (mediaStoreId == null) {
                    Log.w(TAG, "deleteForever: Could not find MediaStore ID, removing from trash DB: ${item.originalPath}")
                    trashDao.deleteById(item.id)
                    return true
                }

                val contentUri = ContentUris.withAppendedId(mediaUri, mediaStoreId)

                // Удаляем файл из системной корзины (навсегда)
                val deleted = context.contentResolver.delete(contentUri, null, null)

                trashDao.deleteById(item.id)
                if (deleted > 0) {
                    Log.d(TAG, "deleteForever: Successfully deleted from system trash: ${item.fileName}")
                    true
                } else {
                    Log.w(TAG, "deleteForever: File already deleted or not found in system trash")
                    true  // Все равно удаляем из БД
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteForever: Error deleting from system trash", e)
                trashDao.deleteById(item.id)
                true  // Удаляем из БД даже если не получилось удалить файл
            }
        }

        // Для файлов в локальной корзине (Android 9 и ниже)
        val trashedFile = File(item.trashPath)
        if (trashedFile.exists()) {
            trashedFile.delete()
        }
        trashDao.deleteById(item.id)
        return true
    }

    override suspend fun deleteForeverBatch(ids: List<Long>): Int {
        var deleted = 0
        ids.forEach { id ->
            val item = trashDao.getById(id) ?: return@forEach
            val file = File(item.trashPath)
            if (file.exists()) file.delete()
            deleted++
        }
        trashDao.deleteByIds(ids)
        return deleted
    }

    override suspend fun emptyTrash(): Int {
        val items = trashDao.getAllOnce()
        
        // Разделяем на системную корзину и локальную
        val (systemTrashItems, localTrashItems) = items.partition { it.trashPath == "[SYSTEM_TRASH]" }
        
        // Удаляем файлы из системной корзины (Android 11+)
        if (systemTrashItems.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && context != null) {
            systemTrashItems.forEach { item ->
                try {
                    val mediaUri = if (item.isVideo) {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                    
                    val mediaStoreId = findMediaStoreIdByPath(item.originalPath, mediaUri, context)
                    if (mediaStoreId != null) {
                        val contentUri = ContentUris.withAppendedId(mediaUri, mediaStoreId)
                        context.contentResolver.delete(contentUri, null, null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "emptyTrash: Error deleting from system trash: ${item.fileName}", e)
                }
            }
        }
        
        // Удаляем файлы из локальной корзины
        localTrashItems.forEach { item ->
            val file = File(item.trashPath)
            if (file.exists()) {
                file.delete()
            }
        }
        
        trashDao.clearAll()
        return items.size
    }

    override suspend fun cleanupOlderThan(days: Int): Int {
        val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000L
        val oldItems = trashDao.getOlderThan(cutoff)
        oldItems.forEach { item ->
            val file = File(item.trashPath)
            if (file.exists()) file.delete()
        }
        if (oldItems.isNotEmpty()) {
            trashDao.deleteByIds(oldItems.map { it.id })
        }
        return oldItems.size
    }

    // Suspend wrapper for MediaScannerConnection callback
    private suspend fun scanFile(ctx: Context, path: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            MediaScannerConnection.scanFile(ctx, arrayOf(path), null) { _, _ ->
                if (cont.isActive) cont.resume(Unit)
            }
        }

    // Сканирует файл и восстанавливает оригинальные даты
    private suspend fun scanFileWithOriginalDates(
        ctx: Context,
        path: String,
        originalDateAdded: Long,
        originalDateModified: Long
    ) {
        val file = File(path)
        if (!file.exists()) return

        try {
            if (originalDateModified > 0L) {
                file.setLastModified(originalDateModified)
            }

            scanFile(ctx, path)
            kotlinx.coroutines.delay(300)

            val isVideo = file.extension.lowercase() in listOf("mp4", "avi", "mkv", "mov", "3gp", "webm")
            val collection = if (isVideo) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
            }

            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DATA} = ?"
            val selectionArgs = arrayOf(path)

            ctx.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val contentUri = ContentUris.withAppendedId(collection, id)

                    val updateValues = android.content.ContentValues().apply {
                        if (originalDateAdded > 0L) {
                            put(MediaStore.MediaColumns.DATE_ADDED, originalDateAdded / 1000)
                        }
                        if (originalDateModified > 0L) {
                            put(MediaStore.MediaColumns.DATE_MODIFIED, originalDateModified / 1000)
                        }
                    }
                    if (updateValues.size() > 0) {
                        ctx.contentResolver.update(contentUri, updateValues, null, null)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanFileWithOriginalDates: Failed to preserve original dates", e)
            runCatching { if (file.exists()) scanFile(ctx, path) }
        }
    }

    // Вспомогательная функция для определения MIME типа
    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "mp4" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
    }

    private fun ensureTrashDir() {
        if (!trashRootDir.exists()) {
            trashRootDir.mkdirs()
        }
    }

    private fun uniqueTrashName(originalName: String): String {
        return "${System.currentTimeMillis()}_$originalName"
    }

    private fun TrashEntity.toModel() = TrashModel(
        id = id,
        mediaId = mediaId,
        fileName = fileName,
        originalPath = originalPath,
        trashPath = trashPath,
        mimeType = mimeType,
        size = size,
        dateDeleted = dateDeleted,
        isVideo = isVideo,
        originalDateAdded = originalDateAdded,
        originalDateModified = originalDateModified
    )
    
    companion object {
        private const val TAG = "TrashRepositoryImpl"

        // Хранит PendingIntent для запроса разрешения пользователя
        var pendingUserPermissionRequest: Pair<PendingIntent, MediaModel>? = null
    }
}
