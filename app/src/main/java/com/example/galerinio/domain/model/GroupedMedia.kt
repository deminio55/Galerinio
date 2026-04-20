package com.example.galerinio.domain.model

sealed class MediaListItem {
    data class Header(val title: String, val timestamp: Long = 0L) : MediaListItem()
    data class MediaItem(val media: MediaModel) : MediaListItem()
}

data class GroupedMediaData(
    val items: List<MediaListItem>
)

