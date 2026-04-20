package com.example.galerinio.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.galerinio.domain.model.AlbumModel
import com.example.galerinio.domain.repository.AlbumRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class AlbumViewModel(private val albumRepository: AlbumRepository) : ViewModel() {
    
    private val _uiState = MutableStateFlow<UiState<List<AlbumModel>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<AlbumModel>>> = _uiState.asStateFlow()

    private var albumsLoadJob: Job? = null
    
    init {
        loadAllAlbums()
    }
    
    fun loadAllAlbums() {
        observeAlbums(albumRepository.getAllAlbums())
    }

    fun searchAlbums(query: String) {
        observeAlbums(albumRepository.searchAlbums(query))
    }

    private fun observeAlbums(source: Flow<List<AlbumModel>>) {
        albumsLoadJob?.cancel()
        _uiState.value = UiState.Loading
        albumsLoadJob = source
            .onEach { albums ->
                _uiState.value = if (albums.isEmpty()) {
                    UiState.Empty
                } else {
                    UiState.Success(albums)
                }
            }
            .catch { exception ->
                _uiState.value = UiState.Error(exception)
            }
            .launchIn(viewModelScope)
    }
}

