package com.example.galerinio.data.util

import com.example.galerinio.domain.model.*
import java.text.SimpleDateFormat
import java.util.*

object MediaSorter {
    // Старые записи в БД могли храниться в секундах, новые — в миллисекундах.
    // Нормализуем к миллисекундам для стабильной сортировки/группировки.
    private fun normalizeEpochMillis(value: Long): Long {
        return if (value in 1..9_999_999_999L) value * 1000L else value
    }

    fun sortAndGroupMedia(
        mediaList: List<MediaModel>,
        options: SortOptions
    ): List<MediaListItem> {
        // 1. Sort the list
        val sorted = sortMedia(mediaList, options.sortType, options.isDescending)
        
        // 2. Group if needed
        return if (options.groupingType == GroupingType.NONE) {
            sorted.map { MediaListItem.MediaItem(it) }
        } else {
            groupMedia(sorted, options.groupingType, options.sortType)
        }
    }
    
    private fun sortMedia(
        mediaList: List<MediaModel>,
        sortType: SortType,
        isDescending: Boolean
    ): List<MediaModel> {
        val comparator: Comparator<MediaModel> = when (sortType) {
            SortType.NAME -> compareBy { it.fileName.lowercase() }
            SortType.DATE_TAKEN -> compareBy { normalizeEpochMillis(it.dateAdded) }
            SortType.DATE_MODIFIED -> compareBy { normalizeEpochMillis(it.dateModified) }
            SortType.SIZE -> compareBy { it.size }
            SortType.CUSTOM -> compareBy { normalizeEpochMillis(it.dateAdded) }
        }
        
        return if (isDescending) {
            mediaList.sortedWith(comparator.reversed())
        } else {
            mediaList.sortedWith(comparator)
        }
    }
    
    private fun groupMedia(
        mediaList: List<MediaModel>,
        groupingType: GroupingType,
        sortType: SortType
    ): List<MediaListItem> {
        val result = mutableListOf<MediaListItem>()
        val calendar = Calendar.getInstance()
        
        val groups = mediaList.groupBy { media ->
            calendar.timeInMillis = groupingTimestamp(media, sortType)
            when (groupingType) {
                GroupingType.DAY -> {
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    calendar.timeInMillis
                }
                GroupingType.WEEK -> {
                    calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    calendar.timeInMillis
                }
                GroupingType.MONTH -> {
                    calendar.set(Calendar.DAY_OF_MONTH, 1)
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    calendar.timeInMillis
                }
                GroupingType.YEAR -> {
                    calendar.set(Calendar.MONTH, 0)
                    calendar.set(Calendar.DAY_OF_MONTH, 1)
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    calendar.timeInMillis
                }
                GroupingType.NONE -> 0L
            }
        }.toSortedMap(reverseOrder())
        
        groups.forEach { (timestamp, items) ->
            result.add(MediaListItem.Header(getGroupTitle(timestamp, groupingType), timestamp))
            items.forEach { media ->
                result.add(MediaListItem.MediaItem(media))
            }
        }
        
        return result
    }

    private fun groupingTimestamp(media: MediaModel, sortType: SortType): Long {
        val added = normalizeEpochMillis(media.dateAdded)
        val modified = normalizeEpochMillis(media.dateModified)
        return when (sortType) {
            SortType.DATE_MODIFIED -> if (modified > 0L) modified else added
            SortType.DATE_TAKEN, SortType.CUSTOM, SortType.NAME, SortType.SIZE -> if (added > 0L) added else modified
        }
    }

    private fun getGroupTitle(timestamp: Long, groupingType: GroupingType): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        
        val now = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        
        return when (groupingType) {
            GroupingType.DAY -> {
                when {
                    isSameDay(calendar, now) -> "Today"
                    isSameDay(calendar, yesterday) -> "Yesterday"
                    else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(calendar.time)
                }
            }
            GroupingType.WEEK -> {
                val weekStart = SimpleDateFormat("MMM d", Locale.getDefault()).format(calendar.time)
                calendar.add(Calendar.DAY_OF_YEAR, 6)
                val weekEnd = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(calendar.time)
                "$weekStart - $weekEnd"
            }
            GroupingType.MONTH -> SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(calendar.time)
            GroupingType.YEAR -> SimpleDateFormat("yyyy", Locale.getDefault()).format(calendar.time)
            GroupingType.NONE -> ""
        }
    }
    
    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
    
    fun sortAlbums(
        albums: List<AlbumModel>,
        options: SortOptions
    ): List<AlbumModel> {
        if (options.sortType == SortType.CUSTOM) return albums

        val comparator: Comparator<AlbumModel> = when (options.sortType) {
            SortType.NAME -> compareBy { it.name.lowercase() }
            SortType.DATE_TAKEN, SortType.DATE_MODIFIED -> compareBy { it.dateAdded }
            SortType.SIZE -> compareBy { it.mediaCount }
            SortType.CUSTOM -> compareBy { it.name.lowercase() }
        }
        
        return if (options.isDescending) {
            albums.sortedWith(comparator.reversed())
        } else {
            albums.sortedWith(comparator)
        }
    }
}

