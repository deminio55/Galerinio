package com.example.galerinio.data.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File

class FileManager(private val context: Context) {
    
    fun deleteFile(filePath: String): Boolean {
        return try {
            File(filePath).delete()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun getFileUri(filePath: String): Uri? {
        return try {
            val file = File(filePath)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    fun shareFile(filePath: String) {
        try {
            val uri = getFileUri(filePath) ?: return
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Media"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun openFile(filePath: String) {
        try {
            val uri = getFileUri(filePath) ?: return
            val file = File(filePath)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file))
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun getFileSize(filePath: String): Long {
        return try {
            File(filePath).length()
        } catch (e: Exception) {
            0L
        }
    }
    
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
    
    fun getExternalStoragePath(): File? {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    }
    
    fun createAlbumDirectory(albumName: String): File? {
        return try {
            val albumDir = File(
                getExternalStoragePath(),
                albumName
            )
            if (!albumDir.exists()) {
                albumDir.mkdirs()
            }
            albumDir
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "3gp" -> "video/3gpp"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
    }
}

