package com.example.galerinio.data.cloud

import android.util.Log
import com.example.galerinio.domain.cloud.CloudStorageProvider
import com.example.galerinio.domain.model.SftpCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.InputStream
import java.security.Security

class SftpProvider(private val credentials: SftpCredentials) : CloudStorageProvider {

    override val providerName: String = "SFTP"

    companion object {
        private const val TAG = "SftpProvider"
        private const val CONNECT_TIMEOUT_MS = 30_000

        init {
            // Register BouncyCastle provider for X25519 and other modern algorithms
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            } else {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    /** Persistent SSH/SFTP connection reused across operations */
    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null

    /** Cached absolute home directory path */
    private var cachedHomePath: String? = null

    @Synchronized
    private fun getConnection(): Pair<SSHClient, SFTPClient> {
        val ssh = sshClient
        val sftp = sftpClient
        if (ssh != null && ssh.isConnected && sftp != null) {
            return ssh to sftp
        }
        // Close stale connections
        try { sftpClient?.close() } catch (_: Exception) {}
        try { sshClient?.disconnect() } catch (_: Exception) {}

        val config = DefaultConfig()
        val newSsh = SSHClient(config)
        newSsh.addHostKeyVerifier(PromiscuousVerifier())
        newSsh.connectTimeout = CONNECT_TIMEOUT_MS
        newSsh.timeout = 120_000
        Log.d(TAG, "Connecting to ${credentials.host}:${credentials.port}")
        newSsh.connect(credentials.host, credentials.port)
        Log.d(TAG, "Connected, authenticating as ${credentials.login}")
        if (credentials.privateKey.isNotBlank()) {
            val keyProvider = newSsh.loadKeys(credentials.privateKey, null, null)
            newSsh.authPublickey(credentials.login, keyProvider)
        } else {
            newSsh.authPassword(credentials.login, credentials.password)
        }
        Log.d(TAG, "Authenticated successfully")

        // Detect real home directory via SSH exec before opening SFTP
        cachedHomePath = detectHomeDir(newSsh)
        Log.i(TAG, "Detected home directory: '${cachedHomePath}'")

        val newSftp = newSsh.newSFTPClient()
        sshClient = newSsh
        sftpClient = newSftp
        return newSsh to newSftp
    }

    /**
     * Detect the real home directory by executing `pwd` over SSH exec channel.
     */
    private fun detectHomeDir(ssh: SSHClient): String? {
        return try {
            val session = ssh.startSession()
            val cmd = session.exec("pwd")
            val output = cmd.inputStream.bufferedReader().readText().trim()
            cmd.join(5, java.util.concurrent.TimeUnit.SECONDS)
            session.close()
            Log.d(TAG, "detectHomeDir via SSH exec pwd: '$output'")
            if (output.isNotBlank() && output.startsWith("/")) output else null
        } catch (e: Exception) {
            Log.w(TAG, "detectHomeDir via exec failed: ${e.message}")
            null
        }
    }

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val (ssh, sftp) = getConnection()
            // Verify we can list the home directory
            val home = sftp.canonicalize(".")
            Log.i(TAG, "testConnection: success, canonicalize('.')='$home', cachedHome='$cachedHomePath'")

            // === DIAGNOSTIC: List SFTP root to understand the filesystem layout ===
            try {
                val rootItems = sftp.ls("/").map { "${if (it.isDirectory) "[DIR]" else "[FILE]"} ${it.name}" }
                Log.i(TAG, "SFTP ROOT (/) contents: $rootItems")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot list SFTP /: ${e.message}")
            }

            // Try to find the Galerinio folder at various paths
            for (probe in listOf("/Galerinio", "Galerinio", "/homes", "/volume1", "/volume1/homes")) {
                try {
                    val st = sftp.stat(probe)
                    Log.i(TAG, "PROBE stat('$probe'): exists, isDir=${st.type}")
                } catch (e: Exception) {
                    Log.w(TAG, "PROBE stat('$probe'): ${e.message}")
                }
            }

            // List home directory contents using SFTP root (since it may be chrooted)
            try {
                val items = sftp.ls(".").map { "${if (it.isDirectory) "[DIR]" else "[FILE]"} ${it.name}" }
                Log.i(TAG, "SFTP ls('.') contents: $items")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot list '.': ${e.message}")
            }
            // Try common NAS paths
            for (probe in listOf("/volume1", "/volume1/homes", "/home", "/data")) {
                try {
                    val items = sftp.ls(probe).map { "${if (it.isDirectory) "[DIR]" else "[FILE]"} ${it.name}" }
                    Log.i(TAG, "$probe contents: $items")
                } catch (_: Exception) { }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "testConnection failed: ${e.javaClass.simpleName}: ${e.message}", e)
            val userMessage = when {
                e.message?.contains("timeout", ignoreCase = true) == true ->
                    "Connection timed out. Check host/port."
                e.message?.contains("Auth fail", ignoreCase = true) == true ||
                e.message?.contains("authentication", ignoreCase = true) == true ->
                    "Authentication failed. Check login/password."
                e.message?.contains("refused", ignoreCase = true) == true ->
                    "Connection refused. SFTP may not be enabled or wrong port."
                e.message?.contains("resolve", ignoreCase = true) == true ||
                e.message?.contains("UnknownHost", ignoreCase = true) == true ->
                    "Cannot resolve hostname. Check the address."
                else -> e.message ?: e.javaClass.simpleName
            }
            throw RuntimeException(userMessage, e)
        }
    }

    override suspend fun ensureRemoteFolder(remoteFolderPath: String): Unit = withContext(Dispatchers.IO) {
        val (_, sftp) = getConnection()
        val basePath = resolveRemotePath(sftp, remoteFolderPath)
        Log.d(TAG, "ensureRemoteFolder: resolved path = $basePath")

        val isAbsolute = basePath.startsWith("/")
        val segments = basePath.trimStart('/').trimEnd('/').split('/')
        var currentPath = ""
        for (segment in segments) {
            if (segment.isBlank()) continue
            currentPath = if (currentPath.isEmpty()) {
                if (isAbsolute) "/$segment" else segment
            } else {
                "$currentPath/$segment"
            }
            try {
                sftp.stat(currentPath)
            } catch (_: Exception) {
                Log.d(TAG, "Creating folder: $currentPath")
                try {
                    sftp.mkdir(currentPath)
                    Log.i(TAG, "Created folder: $currentPath")
                } catch (e2: Exception) {
                    // May already exist due to race condition
                    Log.w(TAG, "mkdir failed for $currentPath: ${e2.message}")
                }
            }
        }
        // Verify the final folder exists
        try {
            sftp.stat(basePath)
            Log.i(TAG, "ensureRemoteFolder: verified folder exists: $basePath")
        } catch (e: Exception) {
            Log.e(TAG, "ensureRemoteFolder: FOLDER DOES NOT EXIST after creation: $basePath - ${e.message}")
            throw RuntimeException("Failed to create remote folder: $basePath", e)
        }
    }

    /**
     * Resolve remote folder path for SFTP operations.
     * Works universally with:
     * - Standard Linux servers (home = /home/user, no chroot)
     * - Synology NAS (chrooted SFTP with /home, /homes symlinks)
     * - Other NAS devices and custom SFTP setups
     */
    private fun resolveRemotePath(sftp: SFTPClient, remoteFolderPath: String): String {
        val trimmed = remoteFolderPath.trim().trimEnd('/')
        val folderName = trimmed.trimStart('/')

        if (folderName.isEmpty()) {
            return "/"
        }

        Log.d(TAG, "resolveRemotePath: input='$trimmed'")

        // 1. If user provided a full absolute path, check if it exists as-is
        if (trimmed.startsWith("/")) {
            try {
                sftp.stat(trimmed)
                Log.d(TAG, "resolveRemotePath: absolute path '$trimmed' exists")
                return trimmed
            } catch (_: Exception) { }
        }

        // 2. Get SFTP working directory (canonical home)
        val sftpHome = try { sftp.canonicalize(".").trimEnd('/') } catch (_: Exception) { "" }
        Log.d(TAG, "resolveRemotePath: sftpHome='$sftpHome'")

        // 3. Try relative to SFTP home first (works on standard Linux servers)
        if (sftpHome.isNotEmpty() && sftpHome != "/") {
            val homeBased = "$sftpHome/$folderName"
            try {
                sftp.stat(homeBased)
                Log.d(TAG, "resolveRemotePath: found at SFTP home: '$homeBased'")
                return homeBased
            } catch (_: Exception) {
                // Folder doesn't exist yet — but this is the best default path
                // Try to verify we can write to sftpHome
                Log.d(TAG, "resolveRemotePath: will use SFTP home-based path: '$homeBased'")
                return homeBased
            }
        }

        // 4. SFTP home is "/" — likely chrooted (Synology NAS, etc.)
        //    Probe known paths to find the folder or a writable base
        val probePaths = mutableListOf<String>()

        // Try the path as given
        probePaths.add("/$folderName")

        // Synology-specific: /home is symlink to user's home dir
        probePaths.add("/home/$folderName")
        probePaths.add("/homes/$folderName")

        // If we know SSH home, try relative username-based paths
        val sshUser = cachedHomePath?.substringAfterLast('/')
        if (!sshUser.isNullOrBlank()) {
            probePaths.add("/homes/$sshUser/$folderName")
            probePaths.add("/home/$sshUser/$folderName")
        }

        for (probe in probePaths) {
            try {
                sftp.stat(probe)
                Log.d(TAG, "resolveRemotePath: found folder at '$probe'")
                return probe
            } catch (_: Exception) { }
        }

        // 5. Folder doesn't exist anywhere — find a writable base to create it
        val writableBases = listOf("/home", "/homes", "/")
        for (base in writableBases) {
            try {
                sftp.stat(base)
                // Test if we can write here by trying to create and remove a temp dir
                val testDir = "$base/.galerinio_write_test_${System.currentTimeMillis()}"
                try {
                    sftp.mkdir(testDir)
                    sftp.rmdir(testDir)
                    val path = if (base == "/") "/$folderName" else "$base/$folderName"
                    Log.d(TAG, "resolveRemotePath: writable base '$base', will use '$path'")
                    return path
                } catch (_: Exception) { }
            } catch (_: Exception) { }
        }

        // 6. Absolute fallback
        val fallback = "/$folderName"
        Log.d(TAG, "resolveRemotePath: fallback='$fallback'")
        return fallback
    }

    override suspend fun listRemoteFiles(remoteFolderPath: String): Set<String> = withContext(Dispatchers.IO) {
        try {
            val (_, sftp) = getConnection()
            val path = resolveRemotePath(sftp, remoteFolderPath)
            Log.d(TAG, "listRemoteFiles: $path")
            sftp.ls(path)
                .filter { !it.isDirectory }
                .map { it.name }
                .toSet()
        } catch (e: Exception) {
            Log.e(TAG, "listRemoteFiles failed: ${e.message}", e)
            emptySet()
        }
    }

    override suspend fun uploadFile(
        remoteFolderPath: String,
        fileName: String,
        mimeType: String,
        inputStream: InputStream
    ): String? = withContext(Dispatchers.IO) {
        try {
            val (_, sftp) = getConnection()
            val folder = resolveRemotePath(sftp, remoteFolderPath)

            // Ensure the target folder exists before uploading
            try {
                sftp.stat(folder)
            } catch (_: Exception) {
                Log.d(TAG, "Target folder does not exist, creating recursively: $folder")
                val isAbsolute = folder.startsWith("/")
                val parts = folder.trimStart('/').trimEnd('/').split('/')
                var cur = ""
                for (part in parts) {
                    if (part.isBlank()) continue
                    cur = if (cur.isEmpty()) {
                        if (isAbsolute) "/$part" else part
                    } else "$cur/$part"
                    try { sftp.stat(cur) } catch (_: Exception) {
                        try {
                            sftp.mkdir(cur)
                            Log.i(TAG, "Created folder: $cur")
                        } catch (e2: Exception) {
                            Log.w(TAG, "mkdir failed for $cur: ${e2.message}")
                        }
                    }
                }
            }

            val remotePath = "$folder/$fileName"
            Log.d(TAG, "Uploading to $remotePath")
            val remoteFile = sftp.open(
                remotePath,
                java.util.EnumSet.of(
                    net.schmizz.sshj.sftp.OpenMode.WRITE,
                    net.schmizz.sshj.sftp.OpenMode.CREAT,
                    net.schmizz.sshj.sftp.OpenMode.TRUNC
                )
            )
            val out = remoteFile.RemoteFileOutputStream()
            out.use { inputStream.copyTo(it) }
            remoteFile.close()
            Log.i(TAG, "Upload success: $fileName")
            remotePath
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for $fileName: ${e.message}", e)
            null
        }
    }

    override suspend fun deleteFile(remoteFolderPath: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val (_, sftp) = getConnection()
            val folder = resolveRemotePath(sftp, remoteFolderPath)
            val remotePath = "$folder/$fileName"
            sftp.rm(remotePath)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed for $fileName: ${e.message}", e)
            false
        }
    }

    override suspend fun close() {
        try {
            sftpClient?.close()
            sshClient?.disconnect()
            sftpClient = null
            sshClient = null
            Log.d(TAG, "SftpProvider closed")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing SftpProvider: ${e.message}")
        }
    }
}
