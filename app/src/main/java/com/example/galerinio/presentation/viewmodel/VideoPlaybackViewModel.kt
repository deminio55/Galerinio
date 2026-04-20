package com.example.galerinio.presentation.viewmodel

import androidx.lifecycle.ViewModel

/**
 * ViewModel для сохранения состояния воспроизведения видео
 * при изменениях конфигурации (ротация экрана и т.д.)
 */
class VideoPlaybackViewModel : ViewModel() {

    data class PlaybackState(
        val mediaId: Long,
        val positionMs: Long,
        val playWhenReady: Boolean
    )

    // Состояние воспроизведения переживает ротацию экрана
    var savedPlaybackState: PlaybackState? = null
        private set

    fun savePlaybackState(mediaId: Long, positionMs: Long, playWhenReady: Boolean) {
        savedPlaybackState = PlaybackState(mediaId, positionMs, playWhenReady)
    }

    fun clearPlaybackState() {
        savedPlaybackState = null
    }

    override fun onCleared() {
        super.onCleared()
        savedPlaybackState = null
    }
}

