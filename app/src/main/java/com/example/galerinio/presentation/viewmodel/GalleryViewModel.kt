package com.example.galerinio.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.galerinio.domain.model.MediaModel
import com.example.galerinio.domain.repository.MediaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class GalleryViewModel(private val mediaRepository: MediaRepository) : ViewModel() {
    
    private val _uiState = MutableStateFlow<UiState<List<MediaModel>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<MediaModel>>> = _uiState.asStateFlow()
    
    private val _searchResults = MutableStateFlow<List<MediaModel>>(emptyList())
    val searchResults: StateFlow<List<MediaModel>> = _searchResults.asStateFlow()
    
    private val _mediaType = MutableStateFlow(MediaType.ALL)
    val mediaType: StateFlow<MediaType> = _mediaType.asStateFlow()
    private var selectedAlbumId: Long? = null
    private var selectedFolderPath: String? = null

    private var mediaLoadJob: Job? = null
    
    fun loadAllMedia() {
        _mediaType.value = MediaType.ALL
        selectedAlbumId = null
        selectedFolderPath = null
        observeMedia(mediaRepository.getAllMedia())
    }

    fun loadImages() {
        _mediaType.value = MediaType.IMAGES
        selectedAlbumId = null
        selectedFolderPath = null
        observeMedia(mediaRepository.getImages())
    }

    fun loadVideos() {
        _mediaType.value = MediaType.VIDEOS
        selectedAlbumId = null
        selectedFolderPath = null
        observeMedia(mediaRepository.getVideos())
    }

    fun loadFavorites() {
        _mediaType.value = MediaType.FAVORITES
        selectedAlbumId = null
        selectedFolderPath = null
        observeMedia(mediaRepository.getFavoriteMedia())
    }

    fun loadMediaByAlbum(albumId: Long) {
        _mediaType.value = MediaType.ALBUM
        selectedAlbumId = albumId
        selectedFolderPath = null
        observeMedia(mediaRepository.getMediaByAlbum(albumId))
    }

    fun loadMediaByFolderPath(folderPathLower: String) {
        _mediaType.value = MediaType.FOLDER_PATH
        selectedFolderPath = folderPathLower
        selectedAlbumId = null
        observeMedia(mediaRepository.getMediaByFolderPath(folderPathLower))
    }

    fun loadMediaByIds(mediaIds: List<Long>) {
        _mediaType.value = MediaType.ALL
        selectedAlbumId = null
        selectedFolderPath = null
        observeMedia(mediaRepository.getMediaByIds(mediaIds))
    }

    fun reloadCurrentFilter() {
        when (_mediaType.value) {
            MediaType.ALL -> loadAllMedia()
            MediaType.IMAGES -> loadImages()
            MediaType.VIDEOS -> loadVideos()
            MediaType.FAVORITES -> loadFavorites()
            MediaType.ALBUM -> selectedAlbumId?.let { loadMediaByAlbum(it) } ?: loadAllMedia()
            MediaType.FOLDER_PATH -> selectedFolderPath?.let { loadMediaByFolderPath(it) } ?: loadAllMedia()
        }
    }

    private fun observeMedia(source: Flow<List<MediaModel>>) {
        mediaLoadJob?.cancel()
        _uiState.value = UiState.Loading
        mediaLoadJob = source
            .onEach { mediaList ->
                _uiState.value = if (mediaList.isEmpty()) {
                    UiState.Empty
                } else {
                    UiState.Success(mediaList)
                }
            }
            .catch { exception ->
                _uiState.value = UiState.Error(exception)
            }
            .launchIn(viewModelScope)
    }
    
    fun searchMedia(query: String) {
        if (query.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        
        mediaRepository.searchMedia(query)
            .onEach { results ->
                _searchResults.value = results
            }
            .catch { exception ->
                exception.printStackTrace()
            }
            .launchIn(viewModelScope)
    }
    
    fun deleteMedia(mediaId: Long) {
        viewModelScope.launch {
            try {
                mediaRepository.deleteMediaById(mediaId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun deleteMediaByPath(filePath: String) {
        viewModelScope.launch {
            try {
                mediaRepository.deleteMediaByPath(filePath)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setFavorite(mediaId: Long, isFavorite: Boolean) {
        viewModelScope.launch {
            try {
                mediaRepository.toggleFavorite(mediaId, isFavorite)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteMultipleMedia(ids: List<Long>) {
        viewModelScope.launch {
            ids.forEach { id ->
                try { mediaRepository.deleteMediaById(id) } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    fun setFavoriteMultiple(ids: List<Long>, isFavorite: Boolean) {
        viewModelScope.launch {
            ids.forEach { id ->
                try { mediaRepository.toggleFavorite(id, isFavorite) } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    enum class MediaType {
        ALL, IMAGES, VIDEOS, FAVORITES, ALBUM, FOLDER_PATH
    }
}

