package com.example.galerinio.domain.model

enum class SortType {
    NAME,
    DATE_TAKEN,
    DATE_MODIFIED,
    SIZE,
    CUSTOM
}

enum class GroupingType {
    NONE,
    DAY,
    WEEK,
    MONTH,
    YEAR
}

data class SortOptions(
    val sortType: SortType = SortType.DATE_TAKEN,
    val isDescending: Boolean = true,
    val groupingType: GroupingType = GroupingType.NONE
)

