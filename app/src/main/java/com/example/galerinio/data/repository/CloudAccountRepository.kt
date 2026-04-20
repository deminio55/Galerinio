package com.example.galerinio.data.repository

import com.example.galerinio.data.local.dao.CloudAccountDao
import com.example.galerinio.data.local.dao.SyncLogDao
import com.example.galerinio.data.local.entity.CloudAccountEntity
import com.example.galerinio.data.local.entity.SyncLogEntity
import com.example.galerinio.domain.model.CloudAccount
import com.example.galerinio.domain.model.CloudProviderType
import com.example.galerinio.domain.model.SyncMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CloudAccountRepository(
    private val cloudAccountDao: CloudAccountDao,
    private val syncLogDao: SyncLogDao
) {

    fun getAllAccountsFlow(): Flow<List<CloudAccount>> =
        cloudAccountDao.getAllFlow().map { list -> list.map { it.toDomain() } }

    suspend fun getAllAccounts(): List<CloudAccount> =
        cloudAccountDao.getAll().map { it.toDomain() }

    suspend fun getEnabledAccounts(): List<CloudAccount> =
        cloudAccountDao.getEnabled().map { it.toDomain() }

    suspend fun getAccountById(id: Long): CloudAccount? =
        cloudAccountDao.getById(id)?.toDomain()

    suspend fun saveAccount(account: CloudAccount): Long {
        val entity = account.toEntity()
        return if (account.id > 0L) {
            cloudAccountDao.update(entity)
            account.id
        } else {
            cloudAccountDao.insert(entity)
        }
    }

    suspend fun deleteAccount(id: Long) {
        syncLogDao.deleteByAccountId(id)
        cloudAccountDao.deleteById(id)
    }

    suspend fun updateLastSync(accountId: Long, timestamp: Long) {
        cloudAccountDao.updateLastSync(accountId, timestamp)
    }

    suspend fun getSyncedFilePaths(accountId: Long): List<String> =
        syncLogDao.getSyncedFilePathsByAccount(accountId)

    suspend fun markFileSynced(accountId: Long, localPath: String, remoteName: String, fileSize: Long) {
        syncLogDao.insert(
            SyncLogEntity(
                cloudAccountId = accountId,
                localFilePath = localPath,
                remoteFileName = remoteName,
                fileSize = fileSize
            )
        )
    }

    suspend fun removeSyncLog(accountId: Long, localPath: String) {
        syncLogDao.deleteByPath(accountId, localPath)
    }

    suspend fun getSyncLogs(accountId: Long): List<SyncLogEntity> =
        syncLogDao.getByAccountId(accountId)

    suspend fun getSyncedFileCount(accountId: Long): Int =
        syncLogDao.countByAccount(accountId)

    // ── Mappers ──

    private fun CloudAccountEntity.toDomain() = CloudAccount(
        id = id,
        providerType = runCatching { CloudProviderType.valueOf(providerType) }
            .getOrDefault(CloudProviderType.WEBDAV),
        displayName = displayName,
        syncMode = runCatching { SyncMode.valueOf(syncMode) }
            .getOrDefault(SyncMode.BACKUP),
        syncOnlyWifi = syncOnlyWifi,
        syncOnlyCharging = syncOnlyCharging,
        remoteFolderPath = remoteFolderPath,
        isEnabled = isEnabled,
        lastSyncTimestamp = lastSyncTimestamp,
        credentialsJson = credentialsJson
    )

    private fun CloudAccount.toEntity() = CloudAccountEntity(
        id = id,
        providerType = providerType.name,
        displayName = displayName,
        syncMode = syncMode.name,
        syncOnlyWifi = syncOnlyWifi,
        syncOnlyCharging = syncOnlyCharging,
        remoteFolderPath = remoteFolderPath,
        isEnabled = isEnabled,
        lastSyncTimestamp = lastSyncTimestamp,
        credentialsJson = credentialsJson
    )
}

