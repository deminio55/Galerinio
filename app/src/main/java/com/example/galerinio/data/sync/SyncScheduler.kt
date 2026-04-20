package com.example.galerinio.data.sync

import android.content.Context
import androidx.work.*
import com.example.galerinio.data.local.GalerioDatabase
import com.example.galerinio.data.repository.CloudAccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Manages scheduling of sync work via WorkManager.
 * Each enabled cloud account gets its own periodic sync job.
 */
object SyncScheduler {

    private const val PERIODIC_SYNC_INTERVAL_HOURS = 6L
    private const val TAG_PERIODIC_PREFIX = "cloud_sync_periodic_"
    private const val TAG_MANUAL_PREFIX = "cloud_sync_manual_"

    /**
     * Schedule periodic sync for all enabled accounts.
     * Call this from MainActivity.onCreate() or after account changes.
     */
    suspend fun scheduleAll(context: Context) = withContext(Dispatchers.IO) {
        val db = GalerioDatabase.getInstance(context)
        val repository = CloudAccountRepository(db.cloudAccountDao(), db.syncLogDao())
        val accounts = repository.getEnabledAccounts()

        val workManager = WorkManager.getInstance(context)

        for (account in accounts) {
            val constraints = Constraints.Builder().apply {
                if (account.syncOnlyWifi) {
                    setRequiredNetworkType(NetworkType.UNMETERED)
                } else {
                    setRequiredNetworkType(NetworkType.CONNECTED)
                }
                setRequiresCharging(account.syncOnlyCharging)
            }.build()

            val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                PERIODIC_SYNC_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setInputData(workDataOf(SyncWorker.KEY_ACCOUNT_ID to account.id))
                .addTag("${TAG_PERIODIC_PREFIX}${account.id}")
                .build()

            workManager.enqueueUniquePeriodicWork(
                "${TAG_PERIODIC_PREFIX}${account.id}",
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }
    }

    /**
     * Cancel periodic sync for a specific account.
     */
    fun cancelForAccount(context: Context, accountId: Long) {
        WorkManager.getInstance(context)
            .cancelUniqueWork("${TAG_PERIODIC_PREFIX}$accountId")
    }

    /**
     * Trigger an immediate one-time sync for a specific account.
     */
    fun syncNow(context: Context, accountId: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_ACCOUNT_ID to accountId))
            .addTag("${TAG_MANUAL_PREFIX}$accountId")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "${TAG_MANUAL_PREFIX}$accountId",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
    }

    /**
     * Trigger sync for all enabled accounts immediately.
     */
    suspend fun syncAllNow(context: Context) = withContext(Dispatchers.IO) {
        val db = GalerioDatabase.getInstance(context)
        val repository = CloudAccountRepository(db.cloudAccountDao(), db.syncLogDao())
        val accounts = repository.getEnabledAccounts()
        for (account in accounts) {
            syncNow(context, account.id)
        }
    }
}

