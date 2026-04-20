package com.example.galerinio.data.util

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.galerinio.data.local.GalerioDatabase
import com.example.galerinio.data.repository.TrashRepositoryImpl

class TrashCleanupWorker(
    private val ctx: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(ctx, workerParams) {

    companion object {
        const val WORK_TAG = "trash_auto_cleanup"
    }

    override suspend fun doWork(): Result {
        return try {
            val prefsManager = PreferencesManager(ctx)
            val enabled = prefsManager.isTrashAutoCleanupEnabled()
            if (!enabled) {
                Log.d(WORK_TAG, "Auto-cleanup disabled, skipping")
                return Result.success()
            }
            val days = prefsManager.getTrashAutoCleanupDays()
            val db = GalerioDatabase.getInstance(ctx)
            val trashDir = ctx.filesDir.resolve("trash_media")
            val trashRepository = TrashRepositoryImpl(db.trashDao(), trashDir, ctx)
            val cleaned = trashRepository.cleanupOlderThan(days)
            Log.d(WORK_TAG, "Auto-cleaned $cleaned items older than $days days")
            Result.success()
        } catch (e: Exception) {
            Log.e(WORK_TAG, "Auto-cleanup failed", e)
            Result.failure()
        }
    }
}

