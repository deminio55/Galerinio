package com.example.galerinio.presentation.viewmodel

import androidx.lifecycle.ViewModel

/**
 * Stores per-photo rotation angle in memory so it survives configuration changes.
 */
class PhotoRotationViewModel : ViewModel() {

    private val rotationsByMediaId = mutableMapOf<Long, Int>()

    fun getRotation(mediaId: Long): Int = rotationsByMediaId[mediaId] ?: 0

    fun putRotation(mediaId: Long, degrees: Int) {
        rotationsByMediaId[mediaId] = normalizeDegrees(degrees)
    }

    fun putAll(values: Map<Long, Int>) {
        values.forEach { (mediaId, degrees) ->
            rotationsByMediaId[mediaId] = normalizeDegrees(degrees)
        }
    }

    private fun normalizeDegrees(value: Int): Int {
        val normalized = value % 360
        return if (normalized < 0) normalized + 360 else normalized
    }
}

