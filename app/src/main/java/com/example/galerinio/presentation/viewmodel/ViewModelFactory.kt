package com.example.galerinio.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.galerinio.domain.repository.AlbumRepository
import com.example.galerinio.domain.repository.MediaRepository

class ViewModelFactory(
    private val mediaRepository: MediaRepository,
    private val albumRepository: AlbumRepository
) : ViewModelProvider.Factory {
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(GalleryViewModel::class.java) -> {
                GalleryViewModel(mediaRepository) as T
            }
            modelClass.isAssignableFrom(AlbumViewModel::class.java) -> {
                AlbumViewModel(albumRepository) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

