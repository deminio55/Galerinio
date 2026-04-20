package com.example.galerinio.data.util

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

class ThumbnailCache(private val context: Context) {
    
    private val cacheDir by lazy {
        File(context.cacheDir, "thumbnails").apply {
            if (!exists()) mkdirs()
        }
    }
    
    fun saveThumbnail(mediaId: Long, bitmap: Bitmap): File? {
        return try {
            val file = File(cacheDir, "thumb_${mediaId}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    fun getThumbnail(mediaId: Long): File? {
        val file = File(cacheDir, "thumb_${mediaId}.jpg")
        return if (file.exists()) file else null
    }
    
    fun deleteThumbnail(mediaId: Long): Boolean {
        return try {
            val file = File(cacheDir, "thumb_${mediaId}.jpg")
            file.delete()
        } catch (e: Exception) {
            false
        }
    }
    
    fun clearCache(): Boolean {
        return try {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun getCacheSize(): Long {
        return try {
            var size = 0L
            cacheDir.listFiles()?.forEach { file ->
                size += file.length()
            }
            size
        } catch (e: Exception) {
            0L
        }
    }
}

