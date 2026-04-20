package com.example.galerinio.data.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "galerinio_preferences")

class PreferencesManager(private val context: Context) {

    enum class ProtectedMediaDisplayMode { HIDE, BLUR }
    enum class LockMethod { NONE, PIN, PATTERN, BIOMETRIC }
    enum class ThemeAccent { BLUE, GREEN, ORANGE, PURPLE, ROSE }
    enum class ThemePalette { DEFAULT, GRAPHITE, FOREST, SAND, LAVENDER }
    enum class PhotoEditorChoice { IN_APP, SYSTEM }
    enum class PhotoEditorSelectionMode { ASK_EACH_TIME, ALWAYS_USE_SAVED }

    companion object {
        private const val EMPTY_BOTTOM_FILTERS_SENTINEL = "__none__"
        private val GRID_COLUMNS = stringPreferencesKey("grid_columns")
        private val SORT_BY = stringPreferencesKey("sort_by")
        private val IS_DARK_MODE = booleanPreferencesKey("is_dark_mode")
        private val AUTO_DELETE_OLD_CACHE = booleanPreferencesKey("auto_delete_old_cache")
        private val SHOW_HIDDEN_FILES = booleanPreferencesKey("show_hidden_files")
        private val LAST_LIBRARY_SCAN_AT = longPreferencesKey("last_library_scan_at")
        private val SCANNER_SCHEMA_VERSION = intPreferencesKey("scanner_schema_version")
        private val GRID_COLUMNS_GALLERY_ALL = intPreferencesKey("grid_columns_gallery_all")
        private val GRID_COLUMNS_GALLERY_PHOTOS = intPreferencesKey("grid_columns_gallery_photos")
        private val GRID_COLUMNS_GALLERY_VIDEOS = intPreferencesKey("grid_columns_gallery_videos")
        private val GRID_COLUMNS_GALLERY_FAVORITES = intPreferencesKey("grid_columns_gallery_favorites")
        private val GRID_COLUMNS_ALBUMS_OVERVIEW = intPreferencesKey("grid_columns_albums_overview")
        private val ACTIVE_TOP_FILTER_ID = intPreferencesKey("active_top_filter_id")
        private val TRASH_AUTO_CLEANUP_ENABLED = booleanPreferencesKey("trash_auto_cleanup_enabled")
        private val TRASH_AUTO_CLEANUP_DAYS = intPreferencesKey("trash_auto_cleanup_days")
        private val ALBUMS_CUSTOM_ORDER = stringPreferencesKey("albums_custom_order")
        private val BOTTOM_FILTER_ORDER = stringPreferencesKey("bottom_filter_order")
        private val BOTTOM_FILTER_VISIBLE = stringPreferencesKey("bottom_filter_visible")
        private val GALLERY_VIEW_MODE = stringPreferencesKey("gallery_view_mode")
        private val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        private val APP_LOCK_PIN_ENABLED = booleanPreferencesKey("app_lock_pin_enabled")
        private val APP_LOCK_PATTERN_ENABLED = booleanPreferencesKey("app_lock_pattern_enabled")
        private val APP_LOCK_BIOMETRIC_ENABLED = booleanPreferencesKey("app_lock_biometric_enabled")
        private val APP_LOCK_TIMEOUT_SECONDS = intPreferencesKey("app_lock_timeout_seconds")
        private val APP_LOCK_LAST_UNLOCK_ELAPSED = longPreferencesKey("app_lock_last_unlock_elapsed")
        private val APP_LOCK_LAST_BACKGROUND_ELAPSED = longPreferencesKey("app_lock_last_background_elapsed")
        private val APP_LOCK_METHOD = stringPreferencesKey("app_lock_method")
        private val FOLDER_PROTECTION_ENABLED = booleanPreferencesKey("folder_protection_enabled")
        private val FOLDER_LOCK_METHOD = stringPreferencesKey("folder_lock_method")
        private val PROTECTED_MEDIA_BLUR_IN_CATEGORIES = booleanPreferencesKey("protected_media_blur_in_categories")
        private val PROTECTED_MEDIA_DISPLAY_MODE = stringPreferencesKey("protected_media_display_mode")
        private val PROTECTED_ALBUM_IDS = stringPreferencesKey("protected_album_ids")
        private val THEME_ACCENT_LIGHT = stringPreferencesKey("theme_accent_light")
        private val THEME_ACCENT_DARK = stringPreferencesKey("theme_accent_dark")
        private val THEME_PALETTE_LIGHT = stringPreferencesKey("theme_palette_light")
        private val THEME_PALETTE_DARK = stringPreferencesKey("theme_palette_dark")
        private val PREFERRED_PHOTO_EDITOR = stringPreferencesKey("preferred_photo_editor")
        private val PHOTO_EDITOR_SELECTION_MODE = stringPreferencesKey("photo_editor_selection_mode")
    }
    
    // Sort options - category-based
    private fun sortTypeKey(category: String) = stringPreferencesKey("sort_type_$category")
    private fun sortDescendingKey(category: String) = booleanPreferencesKey("sort_descending_$category")
    private fun groupingTypeKey(category: String) = stringPreferencesKey("grouping_type_$category")
    private fun mediaRotationKey(mediaId: Long) = intPreferencesKey("media_rotation_$mediaId")

    val gridColumnsFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[GRID_COLUMNS]?.toIntOrNull() ?: 3
    }
    
    val sortByFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SORT_BY] ?: "date"
    }
    
    val isDarkModeFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[IS_DARK_MODE] ?: false
    }
    
    val autoDeleteOldCacheFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_DELETE_OLD_CACHE] ?: true
    }
    
    val showHiddenFilesFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SHOW_HIDDEN_FILES] ?: false
    }

    val lastLibraryScanAtFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[LAST_LIBRARY_SCAN_AT] ?: 0L
    }

    val folderProtectionEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[FOLDER_PROTECTION_ENABLED] ?: false
    }

    val protectedAlbumIdsFlow: Flow<Set<Long>> = context.dataStore.data.map { preferences ->
        val raw = preferences[PROTECTED_ALBUM_IDS].orEmpty()
        if (raw.isBlank()) emptySet() else raw.split(',').mapNotNull { it.toLongOrNull() }.toSet()
    }

    val scannerSchemaVersionFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[SCANNER_SCHEMA_VERSION] ?: 0
    }
    
    suspend fun setGridColumns(columns: Int) {
        context.dataStore.edit { preferences ->
            preferences[GRID_COLUMNS] = columns.toString()
        }
    }
    
    suspend fun setSortBy(sortBy: String) {
        context.dataStore.edit { preferences ->
            preferences[SORT_BY] = sortBy
        }
    }
    
    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_DARK_MODE] = enabled
        }
    }

    suspend fun getThemeAccent(isDarkMode: Boolean): ThemeAccent {
        val prefs = context.dataStore.data.first()
        val raw = if (isDarkMode) prefs[THEME_ACCENT_DARK] else prefs[THEME_ACCENT_LIGHT]
        return parseThemeAccent(raw)
    }

    suspend fun setThemeAccent(isDarkMode: Boolean, accent: ThemeAccent) {
        context.dataStore.edit { preferences ->
            if (isDarkMode) {
                preferences[THEME_ACCENT_DARK] = accent.name
            } else {
                preferences[THEME_ACCENT_LIGHT] = accent.name
            }
        }
    }

    suspend fun getThemePalette(isDarkMode: Boolean): ThemePalette {
        val prefs = context.dataStore.data.first()
        val raw = if (isDarkMode) prefs[THEME_PALETTE_DARK] else prefs[THEME_PALETTE_LIGHT]
        return parseThemePalette(raw)
    }

    suspend fun setThemePalette(isDarkMode: Boolean, palette: ThemePalette) {
        context.dataStore.edit { preferences ->
            if (isDarkMode) {
                preferences[THEME_PALETTE_DARK] = palette.name
            } else {
                preferences[THEME_PALETTE_LIGHT] = palette.name
            }
        }
    }

    private fun parseThemeAccent(raw: String?): ThemeAccent {
        return runCatching { ThemeAccent.valueOf(raw.orEmpty()) }
            .getOrDefault(ThemeAccent.BLUE)
    }

    private fun parseThemePalette(raw: String?): ThemePalette {
        return runCatching { ThemePalette.valueOf(raw.orEmpty()) }
            .getOrDefault(ThemePalette.DEFAULT)
    }

    suspend fun isDarkModeEnabled(): Boolean {
        return context.dataStore.data.first()[IS_DARK_MODE] ?: false
    }

    suspend fun setAutoDeleteOldCache(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_DELETE_OLD_CACHE] = enabled
        }
    }
    
    suspend fun setShowHiddenFiles(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_HIDDEN_FILES] = show
        }
    }

    suspend fun setLastLibraryScanAt(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_LIBRARY_SCAN_AT] = timestamp
        }
    }

    suspend fun getScannerSchemaVersion(): Int {
        val preferences = context.dataStore.data.first()
        return preferences[SCANNER_SCHEMA_VERSION] ?: 0
    }

    suspend fun setScannerSchemaVersion(version: Int) {
        context.dataStore.edit { preferences ->
            preferences[SCANNER_SCHEMA_VERSION] = version
        }
    }

    suspend fun getGalleryGridColumns(filterName: String): Int {
        val preferences = context.dataStore.data.first()
        val legacyFallback = preferences[GRID_COLUMNS]?.toIntOrNull() ?: 3
        return when (filterName.uppercase()) {
            "PHOTOS" -> preferences[GRID_COLUMNS_GALLERY_PHOTOS] ?: legacyFallback
            "VIDEOS" -> preferences[GRID_COLUMNS_GALLERY_VIDEOS] ?: legacyFallback
            "FAVORITES" -> preferences[GRID_COLUMNS_GALLERY_FAVORITES] ?: legacyFallback
            else -> preferences[GRID_COLUMNS_GALLERY_ALL] ?: legacyFallback
        }
    }

    suspend fun setGalleryGridColumns(filterName: String, columns: Int) {
        context.dataStore.edit { preferences ->
            when (filterName.uppercase()) {
                "PHOTOS" -> preferences[GRID_COLUMNS_GALLERY_PHOTOS] = columns
                "VIDEOS" -> preferences[GRID_COLUMNS_GALLERY_VIDEOS] = columns
                "FAVORITES" -> preferences[GRID_COLUMNS_GALLERY_FAVORITES] = columns
                else -> preferences[GRID_COLUMNS_GALLERY_ALL] = columns
            }
        }
    }

    suspend fun getAlbumsGridColumns(): Int {
        val preferences = context.dataStore.data.first()
        return preferences[GRID_COLUMNS_ALBUMS_OVERVIEW] ?: 2
    }

    suspend fun setAlbumsGridColumns(columns: Int) {
        context.dataStore.edit { preferences ->
            preferences[GRID_COLUMNS_ALBUMS_OVERVIEW] = columns
        }
    }

    suspend fun getAlbumContentGridColumns(albumId: Long): Int {
        val preferences = context.dataStore.data.first()
        val key = intPreferencesKey("grid_columns_album_$albumId")
        val legacyFallback = preferences[GRID_COLUMNS]?.toIntOrNull() ?: 3
        return preferences[key] ?: legacyFallback
    }

    suspend fun setAlbumContentGridColumns(albumId: Long, columns: Int) {
        context.dataStore.edit { preferences ->
            val key = intPreferencesKey("grid_columns_album_$albumId")
            preferences[key] = columns
        }
    }

    suspend fun getActiveTopFilterId(defaultFilterId: Int): Int {
        val preferences = context.dataStore.data.first()
        return preferences[ACTIVE_TOP_FILTER_ID] ?: defaultFilterId
    }

    suspend fun setActiveTopFilterId(filterId: Int) {
        context.dataStore.edit { preferences ->
            preferences[ACTIVE_TOP_FILTER_ID] = filterId
        }
    }

    val trashAutoCleanupEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[TRASH_AUTO_CLEANUP_ENABLED] ?: false
    }

    val trashAutoCleanupDaysFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[TRASH_AUTO_CLEANUP_DAYS] ?: 30
    }

    suspend fun isTrashAutoCleanupEnabled(): Boolean {
        return context.dataStore.data.first()[TRASH_AUTO_CLEANUP_ENABLED] ?: false
    }

    suspend fun getTrashAutoCleanupDays(): Int {
        return context.dataStore.data.first()[TRASH_AUTO_CLEANUP_DAYS] ?: 30
    }

    suspend fun setTrashAutoCleanupEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[TRASH_AUTO_CLEANUP_ENABLED] = enabled }
    }

    suspend fun setTrashAutoCleanupDays(days: Int) {
        context.dataStore.edit { prefs -> prefs[TRASH_AUTO_CLEANUP_DAYS] = days }
    }

    suspend fun getAlbumsCustomOrder(): List<Long> {
        val raw = context.dataStore.data.first()[ALBUMS_CUSTOM_ORDER].orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { it.toLongOrNull() }
    }

    suspend fun setAlbumsCustomOrder(order: List<Long>) {
        val normalized = order.joinToString(",")
        context.dataStore.edit { prefs -> prefs[ALBUMS_CUSTOM_ORDER] = normalized }
    }

    suspend fun getBottomFilterOrder(defaultOrder: List<String>): List<String> {
        val raw = context.dataStore.data.first()[BOTTOM_FILTER_ORDER].orEmpty()
        if (raw.isBlank()) return defaultOrder
        val parsed = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return if (parsed.isEmpty()) defaultOrder else parsed
    }

    suspend fun setBottomFilterOrder(order: List<String>) {
        val normalized = order.joinToString(",")
        context.dataStore.edit { prefs -> prefs[BOTTOM_FILTER_ORDER] = normalized }
    }

    suspend fun getBottomFilterVisible(defaultVisible: Set<String>): Set<String> {
        val raw = context.dataStore.data.first()[BOTTOM_FILTER_VISIBLE].orEmpty()
        if (raw == EMPTY_BOTTOM_FILTERS_SENTINEL) return emptySet()
        if (raw.isBlank()) return defaultVisible
        val parsed = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return if (parsed.isEmpty()) defaultVisible else parsed
    }

    suspend fun setBottomFilterVisible(visible: Set<String>) {
        val normalized = if (visible.isEmpty()) {
            EMPTY_BOTTOM_FILTERS_SENTINEL
        } else {
            visible.sorted().joinToString(",")
        }
        context.dataStore.edit { prefs -> prefs[BOTTOM_FILTER_VISIBLE] = normalized }
    }

    suspend fun getGalleryViewMode(): String {
        return context.dataStore.data.first()[GALLERY_VIEW_MODE] ?: "GRID"
    }

    suspend fun setGalleryViewMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[GALLERY_VIEW_MODE] = mode }
    }

    private fun galleryViewModeKey(category: String) = stringPreferencesKey("gallery_view_mode_${category.lowercase()}")

    suspend fun getGalleryViewMode(category: String): String {
        val prefs = context.dataStore.data.first()
        return prefs[galleryViewModeKey(category)]
            ?: prefs[GALLERY_VIEW_MODE]
            ?: "GRID"
    }

    suspend fun setGalleryViewMode(category: String, mode: String) {
        context.dataStore.edit { prefs ->
            prefs[galleryViewModeKey(category)] = mode
            // Keep legacy key in sync for backward compatibility.
            prefs[GALLERY_VIEW_MODE] = mode
        }
    }

    suspend fun isAppLockEnabled(): Boolean {
        return context.dataStore.data.first()[APP_LOCK_ENABLED] ?: false
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[APP_LOCK_ENABLED] = enabled }
    }

    suspend fun isPinLockEnabled(): Boolean {
        return getAppLockMethod() == LockMethod.PIN
    }

    suspend fun setPinLockEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[APP_LOCK_PIN_ENABLED] = enabled
            if (enabled) {
                prefs[APP_LOCK_METHOD] = LockMethod.PIN.name
            } else if ((prefs[APP_LOCK_METHOD] ?: LockMethod.NONE.name) == LockMethod.PIN.name) {
                prefs[APP_LOCK_METHOD] = LockMethod.NONE.name
            }
        }
    }

    suspend fun isPatternLockEnabled(): Boolean {
        return getAppLockMethod() == LockMethod.PATTERN
    }

    suspend fun setPatternLockEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[APP_LOCK_PATTERN_ENABLED] = enabled
            if (enabled) {
                prefs[APP_LOCK_METHOD] = LockMethod.PATTERN.name
            } else if ((prefs[APP_LOCK_METHOD] ?: LockMethod.NONE.name) == LockMethod.PATTERN.name) {
                prefs[APP_LOCK_METHOD] = LockMethod.NONE.name
            }
        }
    }

    suspend fun isBiometricLockEnabled(): Boolean {
        return getAppLockMethod() == LockMethod.BIOMETRIC
    }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[APP_LOCK_BIOMETRIC_ENABLED] = enabled
            if (enabled) {
                prefs[APP_LOCK_METHOD] = LockMethod.BIOMETRIC.name
            } else if ((prefs[APP_LOCK_METHOD] ?: LockMethod.NONE.name) == LockMethod.BIOMETRIC.name) {
                prefs[APP_LOCK_METHOD] = LockMethod.NONE.name
            }
        }
    }

    suspend fun getAppLockMethod(): LockMethod {
        val prefs = context.dataStore.data.first()
        val raw = prefs[APP_LOCK_METHOD]
        if (raw != null) {
            return runCatching { LockMethod.valueOf(raw) }.getOrDefault(LockMethod.NONE)
        }
        // Legacy migration fallback from old booleans.
        return when {
            (prefs[APP_LOCK_PIN_ENABLED] ?: false) -> LockMethod.PIN
            (prefs[APP_LOCK_PATTERN_ENABLED] ?: false) -> LockMethod.PATTERN
            (prefs[APP_LOCK_BIOMETRIC_ENABLED] ?: false) -> LockMethod.BIOMETRIC
            else -> LockMethod.NONE
        }
    }

    suspend fun setAppLockMethod(method: LockMethod) {
        context.dataStore.edit { prefs ->
            prefs[APP_LOCK_METHOD] = method.name
            prefs[APP_LOCK_PIN_ENABLED] = method == LockMethod.PIN
            prefs[APP_LOCK_PATTERN_ENABLED] = method == LockMethod.PATTERN
            prefs[APP_LOCK_BIOMETRIC_ENABLED] = method == LockMethod.BIOMETRIC
        }
    }

    suspend fun getFolderLockMethod(): LockMethod {
        val prefs = context.dataStore.data.first()
        val raw = prefs[FOLDER_LOCK_METHOD] ?: return LockMethod.NONE
        return runCatching { LockMethod.valueOf(raw) }.getOrDefault(LockMethod.NONE)
    }

    suspend fun setFolderLockMethod(method: LockMethod) {
        context.dataStore.edit { prefs -> prefs[FOLDER_LOCK_METHOD] = method.name }
    }

    suspend fun getAppLockTimeoutSeconds(): Int {
        return context.dataStore.data.first()[APP_LOCK_TIMEOUT_SECONDS] ?: 0
    }

    suspend fun setAppLockTimeoutSeconds(seconds: Int) {
        context.dataStore.edit { prefs -> prefs[APP_LOCK_TIMEOUT_SECONDS] = seconds.coerceAtLeast(0) }
    }

    suspend fun getLastUnlockElapsedRealtime(): Long {
        return context.dataStore.data.first()[APP_LOCK_LAST_UNLOCK_ELAPSED] ?: 0L
    }

    suspend fun setLastUnlockElapsedRealtime(value: Long) {
        context.dataStore.edit { prefs -> prefs[APP_LOCK_LAST_UNLOCK_ELAPSED] = value }
    }

    suspend fun getLastBackgroundElapsedRealtime(): Long {
        return context.dataStore.data.first()[APP_LOCK_LAST_BACKGROUND_ELAPSED] ?: 0L
    }

    suspend fun setLastBackgroundElapsedRealtime(value: Long) {
        context.dataStore.edit { prefs -> prefs[APP_LOCK_LAST_BACKGROUND_ELAPSED] = value }
    }

    suspend fun isFolderProtectionEnabled(): Boolean {
        return context.dataStore.data.first()[FOLDER_PROTECTION_ENABLED] ?: false
    }

    suspend fun setFolderProtectionEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[FOLDER_PROTECTION_ENABLED] = enabled }
    }

    suspend fun isProtectedMediaBlurInCategoriesEnabled(): Boolean {
        return context.dataStore.data.first()[PROTECTED_MEDIA_BLUR_IN_CATEGORIES] ?: false
    }

    suspend fun setProtectedMediaBlurInCategoriesEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PROTECTED_MEDIA_BLUR_IN_CATEGORIES] = enabled }
    }

    suspend fun getProtectedMediaDisplayMode(): ProtectedMediaDisplayMode {
        val prefs = context.dataStore.data.first()
        val explicit = prefs[PROTECTED_MEDIA_DISPLAY_MODE]
        if (explicit != null) {
            return runCatching { ProtectedMediaDisplayMode.valueOf(explicit) }
                .getOrDefault(ProtectedMediaDisplayMode.HIDE)
        }
        // Backward compatibility with old boolean flag.
        val legacyBlur = prefs[PROTECTED_MEDIA_BLUR_IN_CATEGORIES] ?: false
        return if (legacyBlur) ProtectedMediaDisplayMode.BLUR else ProtectedMediaDisplayMode.HIDE
    }

    suspend fun setProtectedMediaDisplayMode(mode: ProtectedMediaDisplayMode) {
        context.dataStore.edit { prefs ->
            prefs[PROTECTED_MEDIA_DISPLAY_MODE] = mode.name
            prefs[PROTECTED_MEDIA_BLUR_IN_CATEGORIES] = (mode == ProtectedMediaDisplayMode.BLUR)
        }
    }

    suspend fun getProtectedAlbumIds(): Set<Long> {
        val raw = context.dataStore.data.first()[PROTECTED_ALBUM_IDS].orEmpty()
        if (raw.isBlank()) return emptySet()
        return raw.split(',').mapNotNull { it.toLongOrNull() }.toSet()
    }

    suspend fun setProtectedAlbumIds(ids: Set<Long>) {
        val raw = ids.sorted().joinToString(",")
        context.dataStore.edit { prefs -> prefs[PROTECTED_ALBUM_IDS] = raw }
    }

    suspend fun isAlbumProtected(albumId: Long): Boolean {
        return getProtectedAlbumIds().contains(albumId)
    }

    suspend fun toggleAlbumProtection(albumId: Long): Boolean {
        val current = getProtectedAlbumIds().toMutableSet()
        val enabled = if (current.contains(albumId)) {
            current.remove(albumId)
            false
        } else {
            current.add(albumId)
            true
        }
        setProtectedAlbumIds(current)
        return enabled
    }

    // Sort options - universal methods for any category
    suspend fun getSortType(category: String): String {
        val defaultValue = if (category == "albums") "NAME" else "DATE_TAKEN"
        return context.dataStore.data.first()[sortTypeKey(category)] ?: defaultValue
    }
    
    suspend fun setSortType(category: String, sortType: String) {
        context.dataStore.edit { prefs -> prefs[sortTypeKey(category)] = sortType }
    }
    
    suspend fun getSortDescending(category: String): Boolean {
        val defaultValue = category != "albums"
        return context.dataStore.data.first()[sortDescendingKey(category)] ?: defaultValue
    }
    
    suspend fun setSortDescending(category: String, descending: Boolean) {
        context.dataStore.edit { prefs -> prefs[sortDescendingKey(category)] = descending }
    }
    
    suspend fun getGroupingType(category: String): String {
        return context.dataStore.data.first()[groupingTypeKey(category)] ?: "NONE"
    }
    
    suspend fun setGroupingType(category: String, groupingType: String) {
        context.dataStore.edit { prefs -> prefs[groupingTypeKey(category)] = groupingType }
    }

    suspend fun getMediaRotation(mediaId: Long): Int {
        return context.dataStore.data.first()[mediaRotationKey(mediaId)] ?: 0
    }

    suspend fun getMediaRotations(mediaIds: Collection<Long>): Map<Long, Int> {
        if (mediaIds.isEmpty()) return emptyMap()
        val prefs = context.dataStore.data.first()
        return mediaIds.associateWith { mediaId ->
            prefs[mediaRotationKey(mediaId)] ?: 0
        }
    }

    suspend fun setMediaRotation(mediaId: Long, degrees: Int) {
        val normalized = ((degrees % 360) + 360) % 360
        context.dataStore.edit { prefs ->
            prefs[mediaRotationKey(mediaId)] = normalized
        }
    }

    suspend fun getPreferredPhotoEditor(): PhotoEditorChoice {
        val raw = context.dataStore.data.first()[PREFERRED_PHOTO_EDITOR]
        return runCatching { PhotoEditorChoice.valueOf(raw.orEmpty()) }
            .getOrDefault(PhotoEditorChoice.IN_APP)
    }

    suspend fun setPreferredPhotoEditor(choice: PhotoEditorChoice) {
        context.dataStore.edit { prefs ->
            prefs[PREFERRED_PHOTO_EDITOR] = choice.name
        }
    }

    suspend fun getPhotoEditorSelectionMode(): PhotoEditorSelectionMode {
        val raw = context.dataStore.data.first()[PHOTO_EDITOR_SELECTION_MODE]
        return runCatching { PhotoEditorSelectionMode.valueOf(raw.orEmpty()) }
            .getOrDefault(PhotoEditorSelectionMode.ASK_EACH_TIME)
    }

    suspend fun setPhotoEditorSelectionMode(mode: PhotoEditorSelectionMode) {
        context.dataStore.edit { prefs ->
            prefs[PHOTO_EDITOR_SELECTION_MODE] = mode.name
        }
    }
}
