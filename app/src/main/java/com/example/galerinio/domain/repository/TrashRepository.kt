package com.example.galerinio.domain.repository

import com.example.galerinio.domain.model.MediaModel
import com.example.galerinio.domain.model.TrashModel
import kotlinx.coroutines.flow.Flow

interface TrashRepository {
    fun getTrashItems(): Flow<List<TrashModel>>
    suspend fun moveToTrash(media: MediaModel): Boolean
    suspend fun restore(itemId: Long): Boolean
    suspend fun restoreBatch(ids: List<Long>): Pair<Int, Int>  // restored, failed
    suspend fun deleteForever(itemId: Long): Boolean
    suspend fun deleteForeverBatch(ids: List<Long>): Int
    suspend fun emptyTrash(): Int
    suspend fun cleanupOlderThan(days: Int): Int
}

