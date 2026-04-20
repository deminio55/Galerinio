package com.example.galerinio.data.cloud

import android.content.Context
import com.example.galerinio.domain.cloud.CloudStorageProvider
import com.example.galerinio.domain.model.*
import com.google.gson.Gson

/**
 * Factory that creates the appropriate CloudStorageProvider based on provider type.
 */
object CloudProviderFactory {

    private val gson = Gson()

    fun create(
        providerType: CloudProviderType,
        credentialsJson: String,
        context: Context? = null
    ): CloudStorageProvider {
        return when (providerType) {
            CloudProviderType.WEBDAV -> {
                val cred = gson.fromJson(credentialsJson, WebDavCredentials::class.java)
                WebDavProvider(cred)
            }
            CloudProviderType.SMB -> {
                val cred = gson.fromJson(credentialsJson, SmbCredentials::class.java)
                SmbProvider(cred)
            }
            CloudProviderType.SFTP -> {
                val cred = gson.fromJson(credentialsJson, SftpCredentials::class.java)
                SftpProvider(cred)
            }
            CloudProviderType.GOOGLE_DRIVE -> {
                val cred = gson.fromJson(credentialsJson, GoogleDriveCredentials::class.java)
                GoogleDriveProvider(cred, context)
            }
        }
    }

    fun serializeCredentials(credentials: Any): String = gson.toJson(credentials)
}
