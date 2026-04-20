package com.example.galerinio.domain.model

import android.app.PendingIntent

/**
 * Результат операции удаления файла
 */
sealed class DeleteResult {
    /**
     * Файл успешно удален
     */
    data object Success : DeleteResult()

    /**
     * Ошибка при удалении
     */
    data class Error(val message: String) : DeleteResult()

    /**
     * Требуется разрешение пользователя (для Android 11+)
     * @param pendingIntent Intent для запроса разрешения
     */
    data class RequiresPermission(val pendingIntent: PendingIntent) : DeleteResult()
}

