package com.example.galerinio.data.cloud

import android.util.Log
import com.example.galerinio.domain.cloud.CloudStorageProvider
import com.example.galerinio.domain.model.WebDavCredentials
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class WebDavProvider(private val credentials: WebDavCredentials) : CloudStorageProvider {

    override val providerName: String = "WebDAV"

    companion object {
        private const val TAG = "WebDavProvider"
        private const val CONNECT_TIMEOUT_SEC = 30L
        private const val READ_TIMEOUT_SEC = 120L
        private const val WRITE_TIMEOUT_SEC = 180L
    }

    /**
     * Trust-all manager for self-signed certificates (NAS, Synology, etc.)
     */
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    /** Shared OkHttpClient with extended timeouts, SSL trust, and redirect following */
    private val okHttpClient: OkHttpClient by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())

        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /** Single reusable Sardine client backed by the shared OkHttpClient */
    private val sardine: OkHttpSardine by lazy {
        OkHttpSardine(okHttpClient).apply {
            setCredentials(credentials.login, credentials.password)
        }
    }

    /** Cached resolved base path for the remote folder (e.g. "/home/Galerinio") */
    private var resolvedBasePath: String? = null

    private fun createClient(): OkHttpSardine = sardine

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = normalizeUrl(credentials.url)
        Log.i(TAG, "testConnection: URL=$baseUrl, login=${credentials.login}")

        // Step 1: Try a raw HTTP OPTIONS/HEAD request first to verify basic connectivity
        try {
            val request = Request.Builder()
                .url(baseUrl)
                .method("OPTIONS", null)
                .header("Authorization", Credentials.basic(credentials.login, credentials.password))
                .build()
            val response = okHttpClient.newCall(request).execute()
            val code = response.code
            val dav = response.header("DAV")
            val allow = response.header("Allow")
            response.close()
            Log.i(TAG, "OPTIONS response: HTTP $code, DAV=$dav, Allow=$allow")

            if (code == 401) {
                Log.e(TAG, "Authentication failed (401). Check login/password.")
                throw RuntimeException("Authentication failed (HTTP 401). Check login/password.")
            }
            if (code == 403) {
                Log.e(TAG, "Access forbidden (403).")
                throw RuntimeException("Access forbidden (HTTP 403). WebDAV may not be enabled for this URL/user.")
            }
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "OPTIONS request failed: ${e.javaClass.simpleName}: ${e.message}", e)
        }

        // Step 2: List WebDAV root to discover available paths
        try {
            val client = createClient()
            Log.d(TAG, "testConnection: listing WebDAV root: $baseUrl")
            val rootItems = client.list(baseUrl)
            val names = rootItems.map { "${if (it.isDirectory) "[DIR]" else "[FILE]"} ${it.name} (${it.href})" }
            Log.i(TAG, "WebDAV ROOT contents: $names")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot list WebDAV root: ${e.message}")
        }

        // Step 3: Try Sardine PROPFIND
        try {
            val client = createClient()
            client.list(baseUrl)
            Log.i(TAG, "testConnection: PROPFIND success")
            true
        } catch (e: SardineException) {
            if (e.statusCode == 207) true
            else {
                Log.e(TAG, "testConnection WebDAV error (HTTP ${e.statusCode}): ${e.message}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "testConnection PROPFIND failed: ${e.message}", e)
            try {
                val client = createClient()
                val exists = client.exists(baseUrl)
                Log.i(TAG, "testConnection: exists() fallback = $exists")
                if (exists) true
                else throw RuntimeException("WebDAV is not available at this URL. ${e.message}")
            } catch (e2: RuntimeException) {
                throw e2
            } catch (e2: Exception) {
                throw RuntimeException(e.message ?: e2.message ?: "Unknown error", e2)
            }
        }
    }

    /**
     * Resolve the actual WebDAV path for the remote folder by probing various locations.
     * Caches the result for subsequent calls.
     */
    private fun resolveRemoteUrl(client: OkHttpSardine, remoteFolderPath: String): String {
        // Return cached result if available
        resolvedBasePath?.let { cached ->
            val base = normalizeUrl(credentials.url).trimEnd('/')
            val url = "$base$cached/"
            Log.d(TAG, "resolveRemoteUrl: using cached path '$cached' -> $url")
            return url
        }

        val base = normalizeUrl(credentials.url).trimEnd('/')
        val folderName = remoteFolderPath.trim('/')

        if (folderName.isEmpty()) {
            resolvedBasePath = ""
            return "$base/"
        }

        // 1. Try the path as given
        val directUrl = "$base/$folderName/"
        try {
            client.list(directUrl)
            Log.d(TAG, "resolveRemoteUrl: found at direct path: $directUrl")
            resolvedBasePath = "/$folderName"
            return directUrl
        } catch (_: Exception) { }

        // 2. Probe common NAS paths
        val probePaths = listOf(
            "/home/$folderName",
            "/homes/$folderName",
        )

        for (probe in probePaths) {
            val probeUrl = "$base$probe/"
            try {
                client.list(probeUrl)
                Log.d(TAG, "resolveRemoteUrl: found at '$probeUrl'")
                resolvedBasePath = probe
                return probeUrl
            } catch (_: Exception) { }
        }

        // 3. List root to discover available shares, then look for folder inside them
        try {
            val rootItems = client.list("$base/")
                .filter { it.isDirectory }
                .map { it.name }
                .filter { it.isNotBlank() && it != "/" }
            Log.d(TAG, "resolveRemoteUrl: root shares: $rootItems")

            for (share in rootItems) {
                val shareFolderUrl = "$base/$share/$folderName/"
                try {
                    client.list(shareFolderUrl)
                    Log.d(TAG, "resolveRemoteUrl: found at '$shareFolderUrl'")
                    resolvedBasePath = "/$share/$folderName"
                    return shareFolderUrl
                } catch (_: Exception) { }
            }

            // 4. Folder doesn't exist — try to create it inside writable shares
            for (share in listOf("home", "homes") + rootItems) {
                val newFolderUrl = "$base/$share/$folderName/"
                try {
                    client.createDirectory(newFolderUrl)
                    Log.i(TAG, "resolveRemoteUrl: created folder at '$newFolderUrl'")
                    resolvedBasePath = "/$share/$folderName"
                    return newFolderUrl
                } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveRemoteUrl: failed to list root: ${e.message}")
        }

        // 5. Fallback — use direct path
        Log.w(TAG, "resolveRemoteUrl: fallback to direct path: $directUrl")
        resolvedBasePath = "/$folderName"
        return directUrl
    }

    override suspend fun ensureRemoteFolder(remoteFolderPath: String): Unit = withContext(Dispatchers.IO) {
        val client = createClient()
        // resolveRemoteUrl already handles finding/creating the folder
        val resolvedUrl = resolveRemoteUrl(client, remoteFolderPath)
        Log.d(TAG, "ensureRemoteFolder: resolved URL = $resolvedUrl")

        // Verify the folder exists
        try {
            client.list(resolvedUrl)
            Log.i(TAG, "ensureRemoteFolder: verified folder exists: $resolvedUrl")
        } catch (e: SardineException) {
            if (e.statusCode == 404 || e.statusCode == 405) {
                // Try to create it
                Log.d(TAG, "ensureRemoteFolder: folder not found, creating: $resolvedUrl")
                try {
                    client.createDirectory(resolvedUrl)
                    Log.i(TAG, "ensureRemoteFolder: created $resolvedUrl")
                } catch (ce: SardineException) {
                    if (ce.statusCode == 405 || ce.statusCode == 301) {
                        Log.d(TAG, "ensureRemoteFolder: already exists (${ce.statusCode})")
                    } else {
                        Log.e(TAG, "ensureRemoteFolder: failed to create: ${ce.message}")
                        throw ce
                    }
                }
            } else {
                throw e
            }
        }
    }

    override suspend fun listRemoteFiles(remoteFolderPath: String): Set<String> = withContext(Dispatchers.IO) {
        val client = createClient()
        val fullUrl = resolveRemoteUrl(client, remoteFolderPath)
        Log.d(TAG, "listRemoteFiles: $fullUrl")
        try {
            val files = client.list(fullUrl)
                .drop(1)
                .filter { !it.isDirectory }
                .map { it.name }
                .toSet()
            Log.d(TAG, "listRemoteFiles($fullUrl): ${files.size} files")
            files
        } catch (e: SardineException) {
            Log.e(TAG, "listRemoteFiles failed (HTTP ${e.statusCode}): ${e.message}")
            if (e.statusCode == 404) emptySet() else throw e
        }
    }

    override suspend fun uploadFile(
        remoteFolderPath: String,
        fileName: String,
        mimeType: String,
        inputStream: InputStream
    ): String? = withContext(Dispatchers.IO) {
        try {
            val client = createClient()
            val folderUrl = resolveRemoteUrl(client, remoteFolderPath).trimEnd('/')
            val fileUrl = "$folderUrl/$fileName"
            val bytes = inputStream.readBytes()
            Log.d(TAG, "Uploading $fileName (${bytes.size} bytes) to $fileUrl")
            client.put(fileUrl, bytes, mimeType)
            Log.i(TAG, "Upload success: $fileName")
            fileUrl
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for $fileName: ${e.message}", e)
            null
        }
    }

    override suspend fun deleteFile(remoteFolderPath: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = createClient()
            val folderUrl = resolveRemoteUrl(client, remoteFolderPath).trimEnd('/')
            val fileUrl = "$folderUrl/$fileName"
            client.delete(fileUrl)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed for $fileName: ${e.message}", e)
            false
        }
    }

    /**
     * Normalize the user-provided URL: ensure scheme, trim trailing slash, add trailing slash for dirs.
     */
    private fun normalizeUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        // Add scheme if missing
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "https://$url"
        }
        return url.trimEnd('/') + "/"
    }

    /** Build URL for a directory — always ends with / */
    private fun buildDirUrl(remoteFolderPath: String): String {
        val base = normalizeUrl(credentials.url).trimEnd('/')
        val path = remoteFolderPath.trim('/')
        return if (path.isEmpty()) "$base/" else "$base/$path/"
    }

    /** Build URL for a file — no trailing slash */
    private fun buildFileUrl(remoteFolderPath: String, fileName: String): String {
        val base = normalizeUrl(credentials.url).trimEnd('/')
        val folder = remoteFolderPath.trim('/')
        val file = fileName.trim('/')
        return if (folder.isEmpty()) "$base/$file" else "$base/$folder/$file"
    }

    override suspend fun close() {
        try {
            okHttpClient.dispatcher.executorService.shutdown()
            okHttpClient.connectionPool.evictAll()
            Log.d(TAG, "WebDavProvider closed")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebDavProvider: ${e.message}")
        }
    }
}
