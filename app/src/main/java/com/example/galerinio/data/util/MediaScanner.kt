package com.example.galerinio.data.util

import android.content.Context
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.galerinio.domain.model.AlbumModel
import com.example.galerinio.domain.model.MediaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MediaScanner(private val context: Context) {

    companion object {
        // Bump this when album identification logic changes and cached DB must be rebuilt.
        const val SCANNER_SCHEMA_VERSION = 6
        private const val TAG = "MediaScanner"
    }

    private fun normalizeToMillis(epoch: Long): Long {
        if (epoch <= 0L) return 0L
        return if (epoch < 10_000_000_000L) epoch * 1000L else epoch
    }

    private fun resolveFileLastModified(path: String): Long {
        if (path.startsWith("content://")) return 0L
        return runCatching {
            val file = File(path)
            if (file.exists()) file.lastModified() else 0L
        }.getOrDefault(0L)
    }

    suspend fun scanMediaFiles(sinceMs: Long? = null): List<MediaModel> = withContext(Dispatchers.IO) {
        val mediaList = mutableListOf<MediaModel>()
        mediaList.addAll(scanImages(sinceMs))
        mediaList.addAll(scanVideos(sinceMs))
        Log.d(TAG, "scanMediaFiles done: total=${mediaList.size}, sinceMs=$sinceMs")
        mediaList
    }

    suspend fun getCurrentMediaIds(): Set<Long> = withContext(Dispatchers.IO) {
        buildSet {
            addAll(scanIds(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media._ID, isVideo = false))
            addAll(scanIds(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media._ID, isVideo = true))
        }
    }

    suspend fun scanAlbums(): List<AlbumModel> = withContext(Dispatchers.IO) {
        val albums = linkedMapOf<Long, AlbumModel>()
        collectAlbums(
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            idColumnName = MediaStore.Images.Media._ID,
            bucketIdColumnName = MediaStore.Images.Media.BUCKET_ID,
            bucketNameColumnName = MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            dateAddedColumnName = MediaStore.Images.Media.DATE_ADDED,
            albums = albums
        )
        collectAlbums(
            uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            idColumnName = MediaStore.Video.Media._ID,
            bucketIdColumnName = MediaStore.Video.Media.BUCKET_ID,
            bucketNameColumnName = MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            dateAddedColumnName = MediaStore.Video.Media.DATE_ADDED,
            albums = albums
        )
        val result = albums.values.toList()
        Log.d(TAG, "scanAlbums done: count=${result.size}")
        result
    }

    private fun scanImages(sinceMs: Long?): List<MediaModel> {
        val images = mutableListOf<MediaModel>()
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.DATA)
            } else {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
            add(MediaStore.Images.Media.DATE_MODIFIED)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.SIZE)
            add(MediaStore.Images.Media.WIDTH)
            add(MediaStore.Images.Media.HEIGHT)
            add(MediaStore.Images.Media.MIME_TYPE)
            add(MediaStore.Images.Media.BUCKET_ID)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        }.toTypedArray()

        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                buildSelection(sinceMs),
                buildSelectionArgs(sinceMs),
                sortOrder
            )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            val relativePathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val dateModifiedColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
            val dateAddedColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val dateTakenColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val sizeColumn = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
            val widthColumn = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            val mimeTypeColumn = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            val bucketNameColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            
            while (cursor.moveToNext()) {
                val storeId = cursor.getLong(idColumn)
                val id = encodeMediaId(storeId, isVideo = false)
                val name = cursor.getString(nameColumn) ?: "image_$id"
                val pathFromData = if (dataColumn >= 0) cursor.getString(dataColumn) else null
                val relativePath = if (relativePathColumn >= 0) cursor.getString(relativePathColumn) else null
                val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, storeId)

                // Для Android 10+ (API 29+), если DATA недоступен, строим путь из RELATIVE_PATH и DISPLAY_NAME
                val path = if (!pathFromData.isNullOrBlank()) {
                    pathFromData
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !relativePath.isNullOrBlank()) {
                    // На Android 10+ строим путь из relative path
                    // RELATIVE_PATH обычно вида "DCIM/Camera/" (с завершающим слешем)
                    val storagePath = "/storage/emulated/0/"
                    val fullPath = storagePath + relativePath + name
                    Log.d(TAG, "scanImages: Constructed path from RELATIVE_PATH: $fullPath (storagePath=$storagePath, relativePath=$relativePath, name=$name)")
                    fullPath
                } else {
                    // Fallback: используем URI
                    // ВАЖНО: Этот путь не подходит для File операций!
                    Log.w(TAG, "scanImages: Could not get file path for $name (API=${Build.VERSION.SDK_INT}), using URI as fallback")
                    contentUri.toString()
                }

                val dateModified = normalizeToMillis(if (dateModifiedColumn >= 0) cursor.getLong(dateModifiedColumn) else 0L)
                val mediaStoreDateAdded = normalizeToMillis(if (dateAddedColumn >= 0) cursor.getLong(dateAddedColumn) else 0L)
                val dateTaken = normalizeToMillis(if (dateTakenColumn >= 0) cursor.getLong(dateTakenColumn) else 0L)
                val fileLastModified = resolveFileLastModified(path)
                val dateAdded = when {
                    dateTaken > 0L -> dateTaken
                    fileLastModified > 0L -> fileLastModified
                    mediaStoreDateAdded > 0L -> mediaStoreDateAdded
                    else -> dateModified
                }
                val size = if (sizeColumn >= 0) cursor.getLong(sizeColumn) else 0L
                val width = if (widthColumn >= 0) cursor.getInt(widthColumn) else 0
                val height = if (heightColumn >= 0) cursor.getInt(heightColumn) else 0
                val mimeType = if (mimeTypeColumn >= 0) cursor.getString(mimeTypeColumn) ?: "image/jpeg" else "image/jpeg"
                val bucketName = if (bucketNameColumn >= 0) cursor.getString(bucketNameColumn) ?: "Unknown" else "Unknown"
                val albumKey = resolveAlbumKey(pathFromData, relativePath, bucketName)
                val albumId = resolveAlbumId(albumKey)
                
                images.add(
                    MediaModel(
                        id = id,
                        fileName = name,
                        filePath = path,
                        mimeType = mimeType,
                        size = size,
                        dateModified = dateModified,
                        dateAdded = dateAdded,
                        width = width,
                        height = height,
                        duration = 0L,
                        albumId = albumId
                    )
                )
            }
            } ?: Log.w(TAG, "scanImages query returned null cursor")
        } catch (e: Exception) {
            Log.e(TAG, "scanImages failed", e)
        }

        Log.d(TAG, "scanImages done: count=${images.size}, sdk=${Build.VERSION.SDK_INT}, sinceMs=$sinceMs")
        
        return images
    }

    private fun scanVideos(sinceMs: Long?): List<MediaModel> {
        val videos = mutableListOf<MediaModel>()
        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(MediaStore.Video.Media.DATA)
            } else {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
            add(MediaStore.Video.Media.DATE_MODIFIED)
            add(MediaStore.Video.Media.DATE_ADDED)
            add(MediaStore.Video.Media.DATE_TAKEN)
            add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.WIDTH)
            add(MediaStore.Video.Media.HEIGHT)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.MIME_TYPE)
            add(MediaStore.Video.Media.BUCKET_ID)
            add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        }.toTypedArray()

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                buildSelection(sinceMs),
                buildSelectionArgs(sinceMs),
                sortOrder
            )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dataColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
            val relativePathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val dateModifiedColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
            val dateAddedColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
            val dateTakenColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
            val sizeColumn = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
            val widthColumn = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val heightColumn = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
            val durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
            val mimeTypeColumn = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
            val bucketNameColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            
            while (cursor.moveToNext()) {
                val storeId = cursor.getLong(idColumn)
                val id = encodeMediaId(storeId, isVideo = true)
                val name = cursor.getString(nameColumn) ?: "video_$id"
                val pathFromData = if (dataColumn >= 0) cursor.getString(dataColumn) else null
                val relativePath = if (relativePathColumn >= 0) cursor.getString(relativePathColumn) else null
                val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, storeId)

                // Для Android 10+ (API 29+), если DATA недоступен, строим путь из RELATIVE_PATH и DISPLAY_NAME
                val path = if (!pathFromData.isNullOrBlank()) {
                    pathFromData
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !relativePath.isNullOrBlank()) {
                    // На Android 10+ строим путь из relative path
                    // RELATIVE_PATH обычно вида "DCIM/Camera/" (с завершающим слешем)
                    val storagePath = "/storage/emulated/0/"
                    val fullPath = storagePath + relativePath + name
                    Log.d(TAG, "scanVideos: Constructed path from RELATIVE_PATH: $fullPath (storagePath=$storagePath, relativePath=$relativePath, name=$name)")
                    fullPath
                } else {
                    // Fallback: используем URI
                    // ВАЖНО: Этот путь не подходит для File операций!
                    Log.w(TAG, "scanVideos: Could not get file path for $name (API=${Build.VERSION.SDK_INT}), using URI as fallback")
                    contentUri.toString()
                }

                val dateModified = normalizeToMillis(if (dateModifiedColumn >= 0) cursor.getLong(dateModifiedColumn) else 0L)
                val mediaStoreDateAdded = normalizeToMillis(if (dateAddedColumn >= 0) cursor.getLong(dateAddedColumn) else 0L)
                val dateTaken = normalizeToMillis(if (dateTakenColumn >= 0) cursor.getLong(dateTakenColumn) else 0L)
                val fileLastModified = resolveFileLastModified(path)
                val dateAdded = when {
                    dateTaken > 0L -> dateTaken
                    fileLastModified > 0L -> fileLastModified
                    mediaStoreDateAdded > 0L -> mediaStoreDateAdded
                    else -> dateModified
                }
                val size = if (sizeColumn >= 0) cursor.getLong(sizeColumn) else 0L
                val width = if (widthColumn >= 0) cursor.getInt(widthColumn) else 0
                val height = if (heightColumn >= 0) cursor.getInt(heightColumn) else 0
                val duration = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L
                val mimeType = if (mimeTypeColumn >= 0) cursor.getString(mimeTypeColumn) ?: "video/mp4" else "video/mp4"
                val bucketName = if (bucketNameColumn >= 0) cursor.getString(bucketNameColumn) ?: "Unknown" else "Unknown"
                val albumKey = resolveAlbumKey(pathFromData, relativePath, bucketName)
                val albumId = resolveAlbumId(albumKey)
                
                videos.add(
                    MediaModel(
                        id = id,
                        fileName = name,
                        filePath = path,
                        mimeType = mimeType,
                        size = size,
                        dateModified = dateModified,
                        dateAdded = dateAdded,
                        width = width,
                        height = height,
                        duration = duration,
                        albumId = albumId
                    )
                )
            }
            } ?: Log.w(TAG, "scanVideos query returned null cursor")
        } catch (e: Exception) {
            Log.e(TAG, "scanVideos failed", e)
        }

        Log.d(TAG, "scanVideos done: count=${videos.size}, sdk=${Build.VERSION.SDK_INT}, sinceMs=$sinceMs")
        
        return videos
    }

    private fun buildSelection(sinceMs: Long?): String? {
        return if (sinceMs == null || sinceMs <= 0L) {
            null
        } else {
            "${MediaStore.MediaColumns.DATE_ADDED} >= ? OR ${MediaStore.MediaColumns.DATE_MODIFIED} >= ?"
        }
    }

    private fun buildSelectionArgs(sinceMs: Long?): Array<String>? {
        return if (sinceMs == null || sinceMs <= 0L) {
            null
        } else {
            val sinceSeconds = maxOf(0L, (sinceMs / 1000L) - 1L).toString()
            arrayOf(sinceSeconds, sinceSeconds)
        }
    }

    private fun scanIds(uri: android.net.Uri, idColumnName: String, isVideo: Boolean): Set<Long> {
        val ids = mutableSetOf<Long>()
        context.contentResolver.query(
            uri,
            arrayOf(idColumnName),
            null,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(idColumnName)
            while (cursor.moveToNext()) {
                ids.add(encodeMediaId(cursor.getLong(idColumn), isVideo))
            }
        }
        return ids
    }

    private fun encodeMediaId(storeId: Long, isVideo: Boolean): Long {
        return if (isVideo) -storeId else storeId
    }

    private fun collectAlbums(
        uri: android.net.Uri,
        idColumnName: String,
        bucketIdColumnName: String,
        bucketNameColumnName: String,
        dateAddedColumnName: String,
        albums: MutableMap<Long, AlbumModel>
    ) {
        try {
            context.contentResolver.query(
                uri,
                buildList {
                    add(idColumnName)
                    add(bucketIdColumnName)
                    add(bucketNameColumnName)
                    add(dateAddedColumnName)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        add(MediaStore.MediaColumns.DATA)
                    } else {
                        add(MediaStore.MediaColumns.RELATIVE_PATH)
                    }
                }.toTypedArray(),
                null,
                null,
                "$dateAddedColumnName DESC"
            )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(idColumnName)
            val bucketNameColumn = cursor.getColumnIndex(bucketNameColumnName)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(dateAddedColumnName)
            val dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            val relativePathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(idColumn)
                val bucketName = if (bucketNameColumn >= 0) cursor.getString(bucketNameColumn) ?: "Unknown" else "Unknown"
                val pathFromData = if (dataColumn >= 0) cursor.getString(dataColumn) else null
                val relativePath = if (relativePathColumn >= 0) cursor.getString(relativePathColumn) else null
                val albumKey = resolveAlbumKey(pathFromData, relativePath, bucketName)
                val albumId = resolveAlbumId(albumKey)
                val dateAdded = cursor.getLong(dateAddedColumn) * 1000L

                val existing = albums[albumId]
                albums[albumId] = if (existing == null) {
                    AlbumModel(
                        id = albumId,
                        name = bucketName,
                        path = albumKey,
                        coverMediaId = mediaId,
                        mediaCount = 1,
                        dateAdded = dateAdded
                    )
                } else {
                    val isNewerCover = dateAdded >= existing.dateAdded
                    existing.copy(
                        // Обложка должна соответствовать реально последнему добавленному файлу.
                        coverMediaId = if (isNewerCover) mediaId else existing.coverMediaId,
                        mediaCount = existing.mediaCount + 1,
                        dateAdded = maxOf(existing.dateAdded, dateAdded)
                    )
                }
            }
            }
        } catch (e: Exception) {
            Log.e(TAG, "collectAlbums failed for uri=$uri", e)
        }
    }

    private fun resolveAlbumId(albumKey: String): Long {
        // Use FNV-1a 64-bit hash of albumKey only.
        // We intentionally ignore bucketId: on some Android 9 OEM devices it is unstable
        // across re-indexing, while the folder path (albumKey) is always stable.
        // 64-bit FNV avoids the ~50% sign-flip problem of 32-bit String.hashCode().
        var h = -3750763034362895579L // FNV-1a 64-bit offset basis
        for (ch in albumKey) {
            h = h xor ch.code.toLong()
            h *= 1099511628211L // FNV-1a 64-bit prime
        }
        // Long.MIN_VALUE is reserved as "no album" sentinel in the UI layer – avoid it.
        return if (h == Long.MIN_VALUE) Long.MIN_VALUE + 1L else h
    }

    private fun resolveAlbumKey(pathFromData: String?, relativePath: String?, bucketName: String): String {
        val relative = relativePath?.trim()?.trimEnd('/')
        if (!relative.isNullOrBlank()) return relative.lowercase()

        val parentFromAbsolute = pathFromData
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it).parent }
            ?.takeIf { it.isNotBlank() }
        if (!parentFromAbsolute.isNullOrBlank()) return parentFromAbsolute.lowercase()

        return bucketName.lowercase()
    }
}
