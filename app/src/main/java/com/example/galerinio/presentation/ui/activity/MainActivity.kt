package com.example.galerinio.presentation.ui.activity

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.example.galerinio.R
import com.example.galerinio.data.util.PreferencesManager
import com.example.galerinio.data.util.TrashCleanupWorker
import com.example.galerinio.databinding.ActivityMainBinding
import com.example.galerinio.presentation.adapter.DrawerCategoryConfigAdapter
import com.example.galerinio.presentation.adapter.MainPagerAdapter
import com.example.galerinio.presentation.ui.fragment.AboutFragment
import com.example.galerinio.presentation.ui.fragment.AlbumsFragment
import com.example.galerinio.presentation.ui.fragment.GalleryFragment
import com.example.galerinio.presentation.ui.fragment.GeoMapFragment
import com.example.galerinio.presentation.ui.fragment.PhotoViewFragment
import com.example.galerinio.presentation.ui.fragment.SettingsFragment
import com.example.galerinio.presentation.ui.fragment.LanguageFragment
import com.example.galerinio.presentation.ui.fragment.ThemeFragment
import com.example.galerinio.presentation.ui.fragment.CloudSettingsFragment
import com.example.galerinio.presentation.ui.fragment.AddCloudAccountFragment
import com.example.galerinio.presentation.ui.fragment.TrashFragment
import com.example.galerinio.presentation.ui.util.DialogUi
import com.example.galerinio.presentation.ui.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private enum class GalleryViewMode { GRID, LIST }

    private enum class DrawerCategoryKey(
        val prefKey: String,
        val filterId: Int?,
        val titleRes: Int,
        val iconRes: Int,
        val canToggleBottomVisibility: Boolean,
        val isDraggable: Boolean
    ) {
        ALL("all", R.id.filterAllFiles, R.string.menu_all_files, R.drawable.ic_filter_all_files, true, true),
        PHOTOS("photos", R.id.filterPhotos, R.string.menu_images, R.drawable.ic_filter_photos, true, true),
        VIDEOS("videos", R.id.filterVideos, R.string.menu_videos, R.drawable.ic_filter_video_file, true, true),
        FOLDERS("folders", R.id.filterFolders, R.string.menu_folders, R.drawable.ic_filter_folders, true, true),
        FAVORITES("favorites", R.id.filterFavorites, R.string.menu_favorites, R.drawable.ic_filter_favorites, true, true),
        MAP("map", R.id.filterGeo, R.string.menu_map, R.drawable.ic_filter_geo, false, false)
    }

    companion object {
        private const val KEY_ACTIVE_FILTER_ID = "key_active_filter_id"
        private const val DRAWER_DETAIL_TAG = "drawer_detail"
        private const val DEPTH_MIN_SCALE = 0.85f
        private const val EXTRA_REOPEN_THEME_DRAWER = "extra_reopen_theme_drawer"
        /** Backstack name used by cloud sub-fragment navigation. */
        const val DRAWER_CLOUD_NAV = "drawer_cloud_nav"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferencesManager: PreferencesManager
    private var activeFilterId: Int = R.id.filterAllFiles
    private var pagerCallback: ViewPager2.OnPageChangeCallback? = null
    private var areTopFiltersVisible = true
    private var albumsCustomActionsVisible = false
    private var albumsCustomEditing = false
    private var albumsCustomCanSave = false
    private var lastNotifiedPage: Int = Int.MIN_VALUE
    private var currentGalleryViewMode: GalleryViewMode = GalleryViewMode.GRID
    private var viewModeSyncJob: kotlinx.coroutines.Job? = null
    private val pageViewModes = mutableMapOf<Int, GalleryViewMode>()
    private var unlockInProgress = false
    private var activeThemeDarkMode: Boolean = false
    private var activeThemePalette: PreferencesManager.ThemePalette = PreferencesManager.ThemePalette.DEFAULT
    private lateinit var mainPagerAdapter: MainPagerAdapter
    private lateinit var drawerCategoryAdapter: DrawerCategoryConfigAdapter
    private lateinit var drawerCategoryTouchHelper: ItemTouchHelper
    private val configurableDrawerCategories = listOf(
        DrawerCategoryKey.ALL,
        DrawerCategoryKey.PHOTOS,
        DrawerCategoryKey.VIDEOS,
        DrawerCategoryKey.FOLDERS,
        DrawerCategoryKey.FAVORITES
    )
    private var bottomFilterOrder: MutableList<DrawerCategoryKey> = configurableDrawerCategories.toMutableList()
    private var bottomFilterVisible: MutableSet<DrawerCategoryKey> = configurableDrawerCategories.toMutableSet()
    private val unlockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        unlockInProgress = false
        if (result.resultCode != RESULT_OK) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        preferencesManager = PreferencesManager(this)
        applyThemeFromPreferences()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        setupFilterButtons()
        setupDrawerCategoryConfig()
        renderViewModeButtons()
        restoreGalleryViewMode()
        setupDrawer()
        applyChromeThemeColors()
        restoreThemeDrawerAfterRecreateIfNeeded()
        scheduleTrashCleanup()
        scheduleCloudSync()
        cleanupInvalidTrashEntries()

        val restoredFilter = savedInstanceState?.getInt(KEY_ACTIVE_FILTER_ID)
        if (restoredFilter != null) {
            applyFilterState(restoredFilter)
            binding.mainViewPager.setCurrentItem(filterIdToPage(restoredFilter), false)
        } else {
            applyFilterState(R.id.filterAllFiles)
            lifecycleScope.launch {
                val persisted = preferencesManager.getActiveTopFilterId(R.id.filterAllFiles)
                applyFilterState(persisted)
                binding.mainViewPager.setCurrentItem(filterIdToPage(persisted), false)
            }
        }

        supportFragmentManager.addOnBackStackChangedListener { updateTopBarState() }
        updateTopBarState()
    }

    override fun onDestroy() {
        pagerCallback?.let { binding.mainViewPager.unregisterOnPageChangeCallback(it) }
        pagerCallback = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        applyChromeThemeColors()
        enforceAppLockIfNeeded()
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch {
            preferencesManager.setLastBackgroundElapsedRealtime(SystemClock.elapsedRealtime())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ACTIVE_FILTER_ID, activeFilterId)
    }

    // ── ViewPager ────────────────────────────────────────────────────────────

    private fun setupViewPager() {
        mainPagerAdapter = MainPagerAdapter(this, orderedFilterIdsFromBottomOrder())
        binding.mainViewPager.adapter = mainPagerAdapter
        binding.mainViewPager.setPageTransformer { page, position ->
            when {
                position < -1f -> {
                    page.alpha = 0f
                }
                position <= 0f -> {
                    page.alpha = 1f
                    page.translationX = 0f
                    page.translationZ = 0f
                    page.scaleX = 1f
                    page.scaleY = 1f
                }
                position <= 1f -> {
                    page.alpha = 1f - position
                    page.translationX = page.width * -position
                    page.translationZ = -1f
                    val scaleFactor = DEPTH_MIN_SCALE + (1f - DEPTH_MIN_SCALE) * (1f - abs(position))
                    page.scaleX = scaleFactor
                    page.scaleY = scaleFactor
                }
                else -> {
                    page.alpha = 0f
                }
            }
        }
        pagerCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val filterId = pageToFilterId(position)
                applyFilterState(filterId)
                persistActiveFilter(filterId)
                scheduleNotifyCurrentPageSelected(position)
            }

            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    val currentPage = binding.mainViewPager.currentItem
                    scheduleNotifyCurrentPageSelected(currentPage, force = true)
                }
            }
        }
        binding.mainViewPager.registerOnPageChangeCallback(requireNotNull(pagerCallback))
    }

    private fun setupFilterButtons() {
        binding.filterAllFiles.setOnClickListener { selectFilter(R.id.filterAllFiles) }
        binding.filterPhotos.setOnClickListener { selectFilter(R.id.filterPhotos) }
        binding.filterVideos.setOnClickListener { selectFilter(R.id.filterVideos) }
        binding.filterFolders.setOnClickListener { selectFilter(R.id.filterFolders) }
        binding.filterFavorites.setOnClickListener { selectFilter(R.id.filterFavorites) }

        binding.btnSort.setOnClickListener {
            showSortDialog()
        }
        binding.btnViewGrid.setOnClickListener {
            setGalleryViewMode(GalleryViewMode.GRID)
        }
        binding.btnViewList.setOnClickListener {
            setGalleryViewMode(GalleryViewMode.LIST)
        }
        binding.btnAlbumsReorderEdit.setOnClickListener {
            getAlbumsFragment()?.onCustomReorderEditClicked()
        }
        binding.btnAlbumsReorderSave.setOnClickListener {
            getAlbumsFragment()?.onCustomReorderSaveClicked()
        }
        binding.btnGeoRefreshTop.setOnClickListener {
            getGeoMapFragment()?.refreshFromTopBar()
        }
    }

    private fun restoreGalleryViewMode() {
        syncViewModeForFilter(activeFilterId, forceNotify = true)
    }

    // ── Drawer ───────────────────────────────────────────────────────────────

    private fun setupDrawer() {
        syncDrawerDetailBackgroundWithNavigation()

        binding.btnDrawerToggle.setOnClickListener {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                binding.drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                animateDrawerToggle(slideOffset)
            }

            override fun onDrawerOpened(drawerView: View) {
                binding.btnDrawerToggle.contentDescription = getString(R.string.close_side_menu)
                setTopFiltersVisibility(false)
                renderDrawerCategoryList()
            }

            override fun onDrawerClosed(drawerView: View) {
                binding.btnDrawerToggle.contentDescription = getString(R.string.open_side_menu)
                updateTopBarState()
                // When drawer closes, also hide detail panel and restore nav menu
                hideDrawerDetail()
            }
        })

        binding.navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                // Open inside drawer, keep drawer open
                R.id.nav_language  -> showInDrawer(LanguageFragment(), getString(R.string.menu_language))
                R.id.nav_theme     -> showInDrawer(ThemeFragment(), getString(R.string.theme_mode))
                R.id.nav_trash     -> showInDrawer(TrashFragment(), getString(R.string.menu_trash))
                R.id.nav_cloud     -> showInDrawer(CloudSettingsFragment(), getString(R.string.menu_cloud_storage))
                R.id.nav_security  -> showInDrawer(SettingsFragment(), getString(R.string.menu_security))
                R.id.nav_clear_cache -> showClearCacheDialog()
                R.id.nav_about     -> showInDrawer(AboutFragment(), getString(R.string.about))
            }
            true
        }

        // Back button inside drawer detail panel
        binding.btnDrawerDetailBack.setOnClickListener {
            handleDrawerDetailBack()
        }
    }

    private fun setupDrawerCategoryConfig() {
        drawerCategoryAdapter = DrawerCategoryConfigAdapter(
            onStartDrag = { holder -> drawerCategoryTouchHelper.startDrag(holder) },
            onCategoryClicked = { prefKey -> onDrawerCategoryClicked(prefKey) },
            onVisibilityChanged = { prefKey, visible -> onDrawerCategoryVisibilityChanged(prefKey, visible) },
            onOrderChanged = { prefKeys -> onDrawerCategoryOrderChanged(prefKeys) }
        )

        binding.drawerCategoryRecycler.layoutManager = LinearLayoutManager(this)
        binding.drawerCategoryRecycler.adapter = drawerCategoryAdapter

        drawerCategoryTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val canMove = drawerCategoryAdapter.canMove(viewHolder.bindingAdapterPosition, viewHolder.bindingAdapterPosition)
                val dragFlags = if (canMove) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0
                return makeMovementFlags(dragFlags, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                return drawerCategoryAdapter.moveItem(from, to)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean = false

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                drawerCategoryAdapter.dispatchOrderChanged()
            }
        })
        drawerCategoryTouchHelper.attachToRecyclerView(binding.drawerCategoryRecycler)

        lifecycleScope.launch {
            val persistedOrder = preferencesManager.getBottomFilterOrder(configurableDrawerCategories.map { it.prefKey })
            val persistedVisible = preferencesManager.getBottomFilterVisible(configurableDrawerCategories.map { it.prefKey }.toSet())

            bottomFilterOrder = normalizeConfigurableOrder(persistedOrder).toMutableList()
            bottomFilterVisible = normalizeConfigurableVisible(persistedVisible).toMutableSet()

            rebuildPagerForOrder(activeFilterId)
            applyBottomFilterConfigToUi()
            renderDrawerCategoryList()
        }
    }

    private fun onDrawerCategoryClicked(prefKey: String) {
        val key = drawerKeyByPref(prefKey) ?: return
        when (key) {
            DrawerCategoryKey.MAP -> openGeoOverlay()
            else -> key.filterId?.let { selectFilter(it) }
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun onDrawerCategoryVisibilityChanged(prefKey: String, visible: Boolean) {
        val key = drawerKeyByPref(prefKey) ?: return
        if (!key.canToggleBottomVisibility) return

        if (visible) {
            bottomFilterVisible.add(key)
        } else {
            bottomFilterVisible.remove(key)
        }

        rebuildPagerForOrder(activeFilterId)
        ensureActiveCategoryStillVisible()
        applyBottomFilterConfigToUi()
        renderDrawerCategoryList()
        persistBottomFilterConfig()
    }

    private fun onDrawerCategoryOrderChanged(prefKeys: List<String>) {
        bottomFilterOrder = normalizeConfigurableOrder(prefKeys).toMutableList()
        rebuildPagerForOrder(activeFilterId)
        applyBottomFilterConfigToUi()
        renderDrawerCategoryList()
        persistBottomFilterConfig()
    }

    private fun ensureActiveCategoryStillVisible() {
        val activeKey = drawerCategoryFromFilterId(activeFilterId) ?: return
        if (!activeKey.canToggleBottomVisibility) return
        if (bottomFilterVisible.contains(activeKey)) return

        val fallbackFilterId = bottomFilterOrder.firstOrNull { bottomFilterVisible.contains(it) }?.filterId
            ?: R.id.filterAllFiles
        val page = filterIdToPage(fallbackFilterId)
        if (binding.mainViewPager.currentItem != page) {
            binding.mainViewPager.setCurrentItem(page, false)
        }
        applyFilterState(fallbackFilterId)
    }

    private fun applyBottomFilterConfigToUi() {
        val container = binding.topFiltersContainer
        val viewsByKey = mapOf(
            DrawerCategoryKey.ALL to binding.filterAllFiles,
            DrawerCategoryKey.PHOTOS to binding.filterPhotos,
            DrawerCategoryKey.VIDEOS to binding.filterVideos,
            DrawerCategoryKey.FOLDERS to binding.filterFolders,
            DrawerCategoryKey.FAVORITES to binding.filterFavorites
        )

        container.removeAllViews()
        bottomFilterOrder.forEach { key ->
            val view = viewsByKey[key] ?: return@forEach
            view.visibility = if (bottomFilterVisible.contains(key)) View.VISIBLE else View.GONE
            container.addView(view)
        }
        if (bottomFilterVisible.isEmpty()) {
            container.visibility = View.GONE
        } else if (areTopFiltersVisible) {
            container.visibility = View.VISIBLE
        }
        updateFilterIndicators(activeFilterId)
    }

    private fun renderDrawerCategoryList() {
        if (!::drawerCategoryAdapter.isInitialized) return
        val selectedKey = drawerCategoryFromFilterId(activeFilterId)
        val items = buildList {
            bottomFilterOrder.forEach { key ->
                add(
                    DrawerCategoryConfigAdapter.Item(
                        key = key.prefKey,
                        titleRes = key.titleRes,
                        iconRes = key.iconRes,
                        isVisibleInBottom = bottomFilterVisible.contains(key),
                        canToggleBottomVisibility = key.canToggleBottomVisibility,
                        isDraggable = key.isDraggable,
                        isSelected = selectedKey == key
                    )
                )
            }
            add(
                DrawerCategoryConfigAdapter.Item(
                    key = DrawerCategoryKey.MAP.prefKey,
                    titleRes = DrawerCategoryKey.MAP.titleRes,
                    iconRes = DrawerCategoryKey.MAP.iconRes,
                    isVisibleInBottom = false,
                    canToggleBottomVisibility = false,
                    isDraggable = false,
                    isSelected = selectedKey == DrawerCategoryKey.MAP
                )
            )
        }
        drawerCategoryAdapter.submitItems(items)
    }

    private fun persistBottomFilterConfig() {
        lifecycleScope.launch {
            preferencesManager.setBottomFilterOrder(bottomFilterOrder.map { it.prefKey })
            preferencesManager.setBottomFilterVisible(bottomFilterVisible.map { it.prefKey }.toSet())
        }
    }

    private fun normalizeConfigurableOrder(prefKeys: List<String>): List<DrawerCategoryKey> {
        val parsed = prefKeys.mapNotNull { drawerKeyByPref(it) }.filter { it.canToggleBottomVisibility }
        val seen = mutableSetOf<DrawerCategoryKey>()
        val normalized = mutableListOf<DrawerCategoryKey>()
        parsed.forEach { key ->
            if (seen.add(key)) normalized.add(key)
        }
        configurableDrawerCategories.forEach { key ->
            if (seen.add(key)) normalized.add(key)
        }
        return normalized
    }

    private fun normalizeConfigurableVisible(prefKeys: Set<String>): Set<DrawerCategoryKey> {
        return prefKeys.mapNotNull { drawerKeyByPref(it) }
            .filter { it.canToggleBottomVisibility }
            .toSet()
    }

    private fun rebuildPagerForOrder(focusFilterId: Int) {
        val current = binding.mainViewPager.currentItem
        pagerCallback?.let { binding.mainViewPager.unregisterOnPageChangeCallback(it) }

        mainPagerAdapter = MainPagerAdapter(this, orderedFilterIdsFromBottomOrder())
        binding.mainViewPager.adapter = mainPagerAdapter
        binding.mainViewPager.setCurrentItem(mainPagerAdapter.pageOf(focusFilterId), false)

        pagerCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val filterId = pageToFilterId(position)
                applyFilterState(filterId)
                persistActiveFilter(filterId)
                scheduleNotifyCurrentPageSelected(position)
            }

            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    val currentPage = binding.mainViewPager.currentItem
                    scheduleNotifyCurrentPageSelected(currentPage, force = true)
                }
            }
        }
        binding.mainViewPager.registerOnPageChangeCallback(requireNotNull(pagerCallback))

        if (current != binding.mainViewPager.currentItem) {
            scheduleNotifyCurrentPageSelected(binding.mainViewPager.currentItem, force = true)
        }
    }

    private fun orderedFilterIdsFromBottomOrder(): List<Int> {
        val visible = bottomFilterOrder
            .filter { bottomFilterVisible.contains(it) }
            .mapNotNull { it.filterId }
        return if (visible.isEmpty()) listOf(R.id.filterAllFiles) else visible
    }

    private fun drawerKeyByPref(prefKey: String): DrawerCategoryKey? {
        return DrawerCategoryKey.entries.firstOrNull { it.prefKey == prefKey }
    }

    private fun drawerCategoryFromFilterId(filterId: Int): DrawerCategoryKey? {
        return DrawerCategoryKey.entries.firstOrNull { it.filterId == filterId }
    }

    private fun syncDrawerDetailBackgroundWithNavigation() {
        // Always use drawer_surface to match fragment backgrounds consistently
        binding.drawerDetailPanel.setBackgroundResource(R.color.drawer_surface)
        binding.drawerDetailContainer.setBackgroundResource(R.color.drawer_surface)
    }

    private fun applyThemeFromPreferences() {
        val isDarkMode = runBlocking { preferencesManager.isDarkModeEnabled() }
        val accent = runBlocking { preferencesManager.getThemeAccent(isDarkMode) }
        val palette = runBlocking { preferencesManager.getThemePalette(isDarkMode) }
        activeThemeDarkMode = isDarkMode
        activeThemePalette = palette
        val targetMode = if (isDarkMode) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }

        if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
            AppCompatDelegate.setDefaultNightMode(targetMode)
        }

        // We apply only curated accents with verified on-color contrast pairs.
        setTheme(resolveThemeStyleForAccent(accent))
    }

    private fun restoreThemeDrawerAfterRecreateIfNeeded() {
        if (!intent.getBooleanExtra(EXTRA_REOPEN_THEME_DRAWER, false)) return
        intent.putExtra(EXTRA_REOPEN_THEME_DRAWER, false)
        showInDrawer(ThemeFragment(), getString(R.string.theme_mode))
    }

    fun recreateKeepingThemeDrawer() {
        intent.putExtra(EXTRA_REOPEN_THEME_DRAWER, true)
        recreate()
    }

    fun applyNightModeKeepingThemeDrawer(targetMode: Int) {
        intent.putExtra(EXTRA_REOPEN_THEME_DRAWER, true)
        AppCompatDelegate.setDefaultNightMode(targetMode)
    }

    private fun applyChromeThemeColors() {
        val colors = ThemeManager.resolvePaletteColors(
            isDarkMode = activeThemeDarkMode,
            palette = activeThemePalette
        )
        val accent = runBlocking { preferencesManager.getThemeAccent(activeThemeDarkMode) }
        val accentColors = ThemeManager.resolveAccentColors(activeThemeDarkMode, accent)
        val textStateList = ThemeManager.drawerTextStateList(colors.onSurface)

        window.statusBarColor = colors.surface
        window.navigationBarColor = colors.surface
        binding.drawerLayout.setBackgroundColor(colors.background)
        binding.appBar.setBackgroundColor(colors.surface)
        binding.topBarHeader.setBackgroundColor(colors.surface)
        binding.topFiltersContainer.setBackgroundColor(colors.surface)
        binding.drawerNavPanel.setBackgroundColor(colors.drawerSurface)
        binding.drawerDetailPanel.setBackgroundColor(colors.drawerSurface)
        binding.drawerDetailContainer.setBackgroundColor(colors.drawerSurface)
        binding.navigationView.setBackgroundColor(colors.drawerSurface)

        binding.tvAppTitle.setTextColor(colors.onSurface)
        binding.tvDrawerDetailTitle.setTextColor(colors.onSurface)

        listOf(
            binding.btnDrawerToggle,
            binding.btnAlbumsReorderEdit,
            binding.btnAlbumsReorderSave,
            binding.btnGeoRefreshTop,
            binding.btnViewGrid,
            binding.btnViewList,
            binding.btnSort,
            binding.btnDrawerDetailBack
        ).forEach { button ->
            button.imageTintList = ColorStateList.valueOf(colors.onSurface)
        }

        listOf(
            binding.iconAllFiles,
            binding.iconPhotos,
            binding.iconVideos,
            binding.iconFolders,
            binding.iconFavorites
        ).forEach { image ->
            image.imageTintList = ColorStateList.valueOf(colors.onSurface)
        }

        binding.navigationView.itemIconTintList = textStateList
        binding.navigationView.itemTextColor = textStateList

        // Also repaint currently visible fragment trees so base palette affects content screens.
        applyPaletteToVisibleContent(colors, accentColors.accent)
    }

    private fun applyPaletteToVisibleContent(colors: ThemeManager.PaletteColors, accentColor: Int) {
        tintViewTree(binding.mainViewPager, colors, accentColor)
        tintViewTree(binding.container, colors, accentColor)
        tintViewTree(binding.drawerDetailContainer, colors, accentColor)

        supportFragmentManager.fragments.forEach { fragment ->
            fragment.view?.let { root ->
                tintViewTree(root, colors, accentColor)
            }
            fragment.childFragmentManager.fragments.forEach { child ->
                child.view?.let { root ->
                    tintViewTree(root, colors, accentColor)
                }
            }
        }
    }

    private fun tintViewTree(view: View, colors: ThemeManager.PaletteColors, accentColor: Int) {
        val bg = view.background
        val bgColor = (bg as? ColorDrawable)?.color
        val defaultLightSurface = 0xFFFFFFFF.toInt()
        val defaultDarkSurface = 0xFF1A1A1A.toInt()
        val defaultLightDrawer = 0xFFFFFFFF.toInt()
        val defaultDarkDrawer = 0xFF3C3C3C.toInt()

        val shouldUseSurface = when {
            view.id == R.id.selectionBar -> true
            bgColor == null -> false
            bgColor == defaultLightSurface || bgColor == defaultDarkSurface -> true
            else -> false
        }
        val shouldUseDrawer =
            bgColor == defaultLightDrawer || bgColor == defaultDarkDrawer

        when {
            shouldUseSurface -> {
                // Keep category strip and top bars on surface; content roots use background for stronger palette visibility.
                val targetColor = when (view.id) {
                    R.id.topFiltersContainer,
                    R.id.topBarHeader,
                    R.id.appBar,
                    R.id.selectionBar -> colors.surface
                    else -> colors.background
                }
                view.setBackgroundColor(targetColor)
            }
            shouldUseDrawer -> view.setBackgroundColor(colors.drawerSurface)
        }

        if (view is TextView && view.id == R.id.headerTitle) {
            view.setTextColor(accentColor)
        }
        if (view.id == R.id.headerContainer) {
            view.setBackgroundColor(colors.background)
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                tintViewTree(view.getChildAt(i), colors, accentColor)
            }
        }
    }

    private fun resolveThemeStyleForAccent(accent: PreferencesManager.ThemeAccent): Int {
        return when (accent) {
            PreferencesManager.ThemeAccent.BLUE -> R.style.Theme_Galerinio_Accent_Blue
            PreferencesManager.ThemeAccent.GREEN -> R.style.Theme_Galerinio_Accent_Green
            PreferencesManager.ThemeAccent.ORANGE -> R.style.Theme_Galerinio_Accent_Orange
            PreferencesManager.ThemeAccent.PURPLE -> R.style.Theme_Galerinio_Accent_Purple
            PreferencesManager.ThemeAccent.ROSE -> R.style.Theme_Galerinio_Accent_Rose
        }
    }


    /**
     * Show [fragment] inside the drawer panel (detail view).
     * The NavigationView is hidden and the detail panel is shown.
     * Drawer stays open.
     */
    private fun showInDrawer(fragment: androidx.fragment.app.Fragment, title: String) {
        // Show detail panel, hide main nav
        binding.navigationView.visibility = View.GONE
        binding.drawerDetailPanel.visibility = View.VISIBLE
        binding.tvDrawerDetailTitle.text = title

        supportFragmentManager.beginTransaction()
            .runOnCommit {
                if (!isFinishing && !isDestroyed) {
                    applyChromeThemeColors()
                }
            }
            .replace(R.id.drawerDetailContainer, fragment, DRAWER_DETAIL_TAG)
            .commit()

        // Ensure drawer is open
        if (!binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    /** Hide the in-drawer detail panel and restore the nav list. */
    private fun hideDrawerDetail() {
        if (binding.drawerDetailPanel.visibility != View.VISIBLE) return

        // Clear any cloud sub-navigation backstack entries first
        supportFragmentManager.popBackStackImmediate(
            DRAWER_CLOUD_NAV,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )

        binding.drawerDetailPanel.visibility = View.GONE
        binding.navigationView.visibility = View.VISIBLE

        val existing = supportFragmentManager.findFragmentByTag(DRAWER_DETAIL_TAG)
        if (existing != null) {
            supportFragmentManager.beginTransaction().remove(existing).commitAllowingStateLoss()
        }
    }

    /**
     * Handle back navigation inside the drawer detail panel.
     * If there are cloud sub-navigation entries on the backstack, pop one step.
     * Otherwise, close the entire detail panel.
     */
    private fun handleDrawerDetailBack() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            val topEntry = supportFragmentManager.getBackStackEntryAt(
                supportFragmentManager.backStackEntryCount - 1
            )
            if (topEntry.name == DRAWER_CLOUD_NAV) {
                supportFragmentManager.popBackStackImmediate()
                // After popping, determine the new title
                val currentFragment = supportFragmentManager.findFragmentById(R.id.drawerDetailContainer)
                val newTitle = when (currentFragment) {
                    is CloudSettingsFragment -> getString(R.string.menu_cloud_storage)
                    is AddCloudAccountFragment -> getString(R.string.cloud_add_account)
                    else -> binding.tvDrawerDetailTitle.text
                }
                binding.tvDrawerDetailTitle.text = newTitle
                // Reapply palette-aware theme colors to the restored fragment
                reapplyDrawerThemeColors()
                return
            }
        }
        hideDrawerDetail()
    }

    /** Public method for fragments to update the drawer detail title. */
    fun updateDrawerDetailTitle(title: String) {
        binding.tvDrawerDetailTitle.text = title
    }

    /** Public method for fragments to reapply palette-aware theme colors after navigation. */
    fun reapplyDrawerThemeColors() {
        if (!isFinishing && !isDestroyed) {
            applyChromeThemeColors()
        }
    }

    private fun isDrawerDetailVisible(): Boolean =
        binding.drawerDetailPanel.visibility == View.VISIBLE

    private fun showClearCacheDialog() {
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_cache))
            .setMessage(getString(R.string.clear_cache_confirm))
            .setPositiveButton(getString(R.string.clear_cache)) { _, _ ->
                lifecycleScope.launch {
                    Glide.get(this@MainActivity).clearMemory()
                    withContext(Dispatchers.IO) {
                        Glide.get(this@MainActivity).clearDiskCache()
                    }
                    Toast.makeText(this@MainActivity, getString(R.string.cache_cleared), Toast.LENGTH_SHORT).show()
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
        DialogUi.showWithReadableButtons(builder, this, applyDrawerNightStyle = true)
    }


    // ── Overlay (PhotoView / Album detail) ───────────────────────────────────

    private fun openOverlayFragment(fragment: androidx.fragment.app.Fragment) {
        if (hasOverlayFragment()) clearOverlayBackStack()
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun openGeoOverlay() {
        applyFilterState(R.id.filterGeo)
        persistActiveFilter(R.id.filterGeo)
        openOverlayFragment(GeoMapFragment())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun animateDrawerToggle(slideOffset: Float) {
        binding.btnDrawerToggle.rotation = 90f * slideOffset
        binding.btnDrawerToggle.alpha = 1f - 0.25f * slideOffset
    }

    private fun enforceAppLockIfNeeded() {
        if (unlockInProgress) return
        lifecycleScope.launch {
            if (!preferencesManager.isAppLockEnabled()) return@launch
            val appMethod = preferencesManager.getAppLockMethod()
            if (appMethod == com.example.galerinio.data.util.PreferencesManager.LockMethod.NONE) return@launch

            val now = SystemClock.elapsedRealtime()
            val lastBackground = preferencesManager.getLastBackgroundElapsedRealtime()
            val lastUnlock = preferencesManager.getLastUnlockElapsedRealtime()
            val timeoutMs = preferencesManager.getAppLockTimeoutSeconds().coerceAtLeast(0) * 1000L

            val wasBackgrounded = lastBackground > 0L && lastBackground >= lastUnlock
            val shouldLock = if (lastUnlock == 0L) {
                true
            } else if (timeoutMs == 0L) {
                wasBackgrounded
            } else {
                wasBackgrounded && (now - lastBackground) >= timeoutMs
            }

            if (shouldLock) {
                unlockInProgress = true
                unlockLauncher.launch(
                    Intent(this@MainActivity, UnlockActivity::class.java)
                        .putExtra(UnlockActivity.EXTRA_UNLOCK_SCOPE, UnlockActivity.SCOPE_APP)
                )
            }
        }
    }

    private fun setTopFiltersVisibility(visible: Boolean) {
        if (areTopFiltersVisible == visible) return
        areTopFiltersVisible = visible
        if (bottomFilterVisible.isEmpty()) {
            binding.topFiltersContainer.visibility = View.GONE
            return
        }
        val targetAlpha = if (visible) 1f else 0f
        binding.topFiltersContainer.animate()
            .alpha(targetAlpha)
            .setDuration(150L)
            .withEndAction {
                binding.topFiltersContainer.visibility = if (visible) View.VISIBLE else View.GONE
            }
            .start()
        if (visible) binding.topFiltersContainer.visibility = View.VISIBLE
    }

    private fun selectPage(page: Int) {
        if (hasOverlayFragment()) clearOverlayBackStack()
        if (binding.mainViewPager.currentItem == page) {
            scheduleNotifyCurrentPageSelected(page, force = true)
        } else {
            binding.mainViewPager.setCurrentItem(page, true)
            binding.mainViewPager.post {
                binding.mainViewPager.requestLayout()
                binding.mainViewPager.invalidate()
            }
        }
    }

    private fun selectFilter(filterId: Int) {
        selectPage(filterIdToPage(filterId))
    }

    private fun scheduleNotifyCurrentPageSelected(page: Int, force: Boolean = false, attempt: Int = 0) {
        if (!force && lastNotifiedPage == page) return
        binding.mainViewPager.post {
            if (binding.mainViewPager.currentItem != page) return@post
            val delivered = notifyCurrentPageSelected(page)
            if (delivered) {
                lastNotifiedPage = page
            } else if (attempt < 8) {
                binding.mainViewPager.postDelayed(
                    { scheduleNotifyCurrentPageSelected(page, force = true, attempt = attempt + 1) },
                    40L
                )
            }
        }
    }

    private fun notifyCurrentPageSelected(page: Int): Boolean {
        var delivered = false
        val modeForPage = pageViewModes[page] ?: currentGalleryViewMode
        when (val fragment = supportFragmentManager.findFragmentByTag("f$page")) {
            is GalleryFragment -> {
                if (fragment.isAdded && fragment.view != null) {
                    android.util.Log.d("MainActivity", "Notifying GalleryFragment page $page")
                    fragment.setViewMode(
                        if (modeForPage == GalleryViewMode.GRID) {
                            GalleryFragment.ViewMode.GRID
                        } else {
                            GalleryFragment.ViewMode.LIST
                        }
                    )
                    fragment.onMainPageSelected()
                    delivered = true
                }
            }
            is AlbumsFragment -> {
                if (fragment.isAdded && fragment.view != null) {
                    android.util.Log.d("MainActivity", "Notifying AlbumsFragment page $page")
                    fragment.setViewMode(
                        if (modeForPage == GalleryViewMode.GRID) {
                            AlbumsFragment.ViewMode.GRID
                        } else {
                            AlbumsFragment.ViewMode.LIST
                        }
                    )
                    fragment.onMainPageSelected()
                    delivered = true
                }
            }
            is GeoMapFragment -> {
                if (fragment.isAdded && fragment.view != null) {
                    fragment.onMainPageSelected()
                    delivered = true
                }
            }
        }
        return delivered
    }

    private fun clearOverlayBackStack() {
        supportFragmentManager.popBackStack(
            null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
    }

    private fun applyFilterState(filterId: Int) {
        activeFilterId = filterId
        updateFilterIndicators(filterId)
        renderDrawerCategoryList()
        if (!hasOverlayFragment()) updateTitle(filterId)
        renderAlbumsCustomTopActions()
        renderViewModeButtons()
        syncViewModeForFilter(filterId)
        applyChromeThemeColors()
    }

    private fun syncViewModeForFilter(filterId: Int, forceNotify: Boolean = false) {
        val page = filterIdToPage(filterId)
        val cachedMode = pageViewModes[page]
        if (cachedMode != null) {
            val changed = currentGalleryViewMode != cachedMode
            currentGalleryViewMode = cachedMode
            renderViewModeButtons()
            if (binding.mainViewPager.currentItem == page && (forceNotify || changed)) {
                scheduleNotifyCurrentPageSelected(page, force = true)
            }
            return
        }

        viewModeSyncJob?.cancel()
        viewModeSyncJob = lifecycleScope.launch {
            val stored = preferencesManager.getGalleryViewMode(filterIdToViewModeCategory(filterId))
            val resolved = if (stored == GalleryViewMode.LIST.name) GalleryViewMode.LIST else GalleryViewMode.GRID
            pageViewModes[page] = resolved

            // Ignore stale async value if user already switched to another page.
            if (binding.mainViewPager.currentItem != page) return@launch

            val changed = currentGalleryViewMode != resolved
            currentGalleryViewMode = resolved
            renderViewModeButtons()
            if (forceNotify || changed) {
                scheduleNotifyCurrentPageSelected(page, force = true)
            }
        }
    }

    private fun updateFilterIndicators(activeId: Int) {
        listOf(
            R.id.filterAllFiles  to binding.indicatorAllFiles,
            R.id.filterPhotos    to binding.indicatorPhotos,
            R.id.filterVideos    to binding.indicatorVideos,
            R.id.filterFolders   to binding.indicatorFolders,
            R.id.filterFavorites to binding.indicatorFavorites
        ).forEach { (id, indicator) ->
            indicator.visibility = if (id == activeId) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun updateTitle(filterId: Int) {
        binding.tvAppTitle.text = when (filterId) {
            R.id.filterPhotos    -> getString(R.string.menu_images)
            R.id.filterVideos    -> getString(R.string.menu_videos)
            R.id.filterFolders   -> getString(R.string.menu_folders)
            R.id.filterGeo       -> getString(R.string.menu_map)
            R.id.filterFavorites -> getString(R.string.menu_favorites)
            else                 -> getString(R.string.menu_all_files)
        }
    }

    fun setTopBarTitle(title: String) {
        binding.tvAppTitle.text = title
    }

    private fun updateTopBarState() {
        val overlayFragment = supportFragmentManager.findFragmentById(R.id.container)
        val hasOverlay = overlayFragment != null && supportFragmentManager.backStackEntryCount > 0
        val isPhotoViewerOpen = overlayFragment is PhotoViewFragment
        val shouldShowCategoryBar = !hasOverlay && !isPhotoViewerOpen && !binding.drawerLayout.isDrawerOpen(GravityCompat.START)

        binding.container.visibility = if (hasOverlay) View.VISIBLE else View.GONE
        binding.mainViewPager.isUserInputEnabled = !hasOverlay
        binding.appBar.visibility = if (isPhotoViewerOpen) View.GONE else View.VISIBLE
        setTopFiltersVisibility(shouldShowCategoryBar)

        if (isPhotoViewerOpen) {
            binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
        } else {
            binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        }

        if (!hasOverlay && !isPhotoViewerOpen) {
            val pageFilterId = pageToFilterId(binding.mainViewPager.currentItem)
            if (activeFilterId != pageFilterId) {
                applyFilterState(pageFilterId)
            } else {
                updateTitle(activeFilterId)
            }
        }
        renderAlbumsCustomTopActions()
    }

    fun updateAlbumsCustomTopActions(isVisible: Boolean, isEditing: Boolean, canSave: Boolean) {
        albumsCustomActionsVisible = isVisible
        albumsCustomEditing = isEditing
        albumsCustomCanSave = canSave
        renderAlbumsCustomTopActions()
    }

    private fun renderAlbumsCustomTopActions() {
        val overlayFragment = supportFragmentManager.findFragmentById(R.id.container)
        val hasOverlay = overlayFragment != null && supportFragmentManager.backStackEntryCount > 0
        val shouldShow = albumsCustomActionsVisible &&
            pageToFilterId(binding.mainViewPager.currentItem) == R.id.filterFolders &&
            !hasOverlay &&
            !binding.drawerLayout.isDrawerOpen(GravityCompat.START)
        val shouldShowGeoRefresh =
            (overlayFragment is GeoMapFragment) &&
                !binding.drawerLayout.isDrawerOpen(GravityCompat.START)

        binding.btnAlbumsReorderEdit.visibility = if (shouldShow) View.VISIBLE else View.GONE
        binding.btnAlbumsReorderSave.visibility = if (shouldShow) View.VISIBLE else View.GONE
        binding.btnAlbumsReorderSave.isEnabled = shouldShow && albumsCustomCanSave
        binding.btnAlbumsReorderEdit.alpha = if (shouldShow && albumsCustomEditing) 1f else 0.75f
        binding.btnAlbumsReorderSave.alpha = if (binding.btnAlbumsReorderSave.isEnabled) 1f else 0.45f
        binding.btnGeoRefreshTop.visibility = if (shouldShowGeoRefresh) View.VISIBLE else View.GONE
    }

    private fun hasOverlayFragment(): Boolean = supportFragmentManager.backStackEntryCount > 0

    private fun persistActiveFilter(filterId: Int) {
        lifecycleScope.launch { preferencesManager.setActiveTopFilterId(filterId) }
    }

    private fun pageToFilterId(page: Int): Int = mainPagerAdapter.filterIdAt(page)

    private fun filterIdToPage(filterId: Int): Int = mainPagerAdapter.pageOf(filterId)


    private fun filterIdToViewModeCategory(filterId: Int): String = when (filterId) {
        R.id.filterPhotos -> "photos"
        R.id.filterVideos -> "videos"
        R.id.filterFolders -> "folders"
        R.id.filterFavorites -> "favorites"
        else -> "all"
    }

    private fun scheduleTrashCleanup() {
        val request = PeriodicWorkRequestBuilder<TrashCleanupWorker>(1, TimeUnit.DAYS)
            .addTag(TrashCleanupWorker.WORK_TAG)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            TrashCleanupWorker.WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun scheduleCloudSync() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.example.galerinio.data.sync.SyncScheduler.scheduleAll(this@MainActivity)
        }
    }

    /**
     * Удаляет недействительные записи корзины с trashPath="[SYSTEM_TRASH]"
     * (остались от старого кода, который пытался использовать системную корзину)
     */
    private fun cleanupInvalidTrashEntries() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val database = com.example.galerinio.data.local.GalerioDatabase.getInstance(this@MainActivity)
                val trashDao = database.trashDao()
                // Удаляем записи с несуществующим путем
                val deletedCount = trashDao.deleteByTrashPath("[SYSTEM_TRASH]")
                if (deletedCount > 0) {
                    android.util.Log.d("MainActivity", "Cleaned up $deletedCount invalid trash entries")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to cleanup invalid trash entries", e)
            }
        }
    }

    // ── Sort Dialog ──────────────────────────────────────────────────────────

    private fun showSortDialog() {
        if (supportFragmentManager.findFragmentById(R.id.container) is GeoMapFragment) {
            Toast.makeText(this, getString(R.string.sort_not_available_for_map), Toast.LENGTH_SHORT).show()
            return
        }

        val currentPage = binding.mainViewPager.currentItem
        val currentFilterId = pageToFilterId(currentPage)
        val category = when (currentFilterId) {
            R.id.filterPhotos -> "photos"
            R.id.filterVideos -> "videos"
            R.id.filterFavorites -> "favorites"
            R.id.filterFolders -> "albums"
            else -> "all"
        }

        if (category == "geo") {
            Toast.makeText(this, getString(R.string.sort_not_available_for_map), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val currentOptions = com.example.galerinio.domain.model.SortOptions(
                sortType = com.example.galerinio.domain.model.SortType.valueOf(
                    preferencesManager.getSortType(category)
                ),
                isDescending = preferencesManager.getSortDescending(category),
                groupingType = if (category == "albums") {
                    com.example.galerinio.domain.model.GroupingType.NONE
                } else {
                    com.example.galerinio.domain.model.GroupingType.valueOf(
                        preferencesManager.getGroupingType(category)
                    )
                }
            )

            supportFragmentManager.setFragmentResultListener(
                com.example.galerinio.presentation.ui.dialog.SortOptionsDialog.REQUEST_KEY_SORT_OPTIONS,
                this@MainActivity
            ) { _, bundle ->
                val newOptions = com.example.galerinio.domain.model.SortOptions(
                    sortType = com.example.galerinio.domain.model.SortType.valueOf(
                        bundle.getString("arg_sort_type", com.example.galerinio.domain.model.SortType.DATE_TAKEN.name)
                    ),
                    isDescending = bundle.getBoolean("arg_is_descending", true),
                    groupingType = com.example.galerinio.domain.model.GroupingType.valueOf(
                        bundle.getString("arg_grouping_type", com.example.galerinio.domain.model.GroupingType.NONE.name)
                    )
                )
                applySortOptions(newOptions, category)
            }

            val dialog = com.example.galerinio.presentation.ui.dialog.SortOptionsDialog.newInstance(
                currentOptions = currentOptions,
                isAlbumMode = category == "albums"
            )
            dialog.show(supportFragmentManager, "SortOptionsDialog")
        }
    }
    
    private fun applySortOptions(
        options: com.example.galerinio.domain.model.SortOptions,
        category: String
    ) {
        lifecycleScope.launch {
            val normalizedOptions = if (category == "albums") {
                options.copy(groupingType = com.example.galerinio.domain.model.GroupingType.NONE)
            } else {
                options
            }

            preferencesManager.setSortType(category, options.sortType.name)
            preferencesManager.setSortDescending(category, normalizedOptions.isDescending)
            preferencesManager.setGroupingType(category, normalizedOptions.groupingType.name)

            if (category == "albums") {
                getAlbumsFragment()?.applySortOptions(normalizedOptions)
            } else {
                getCurrentGalleryFragment()?.applySortOptions(normalizedOptions)
            }
        }
    }
    
    private fun getCurrentGalleryFragment(): GalleryFragment? {
        val tag = "f" + binding.mainViewPager.currentItem
        return supportFragmentManager.findFragmentByTag(tag) as? GalleryFragment
    }

    private fun setGalleryViewMode(mode: GalleryViewMode) {
        if (currentGalleryViewMode == mode) return
        currentGalleryViewMode = mode
        pageViewModes[binding.mainViewPager.currentItem] = mode
        lifecycleScope.launch {
            preferencesManager.setGalleryViewMode(filterIdToViewModeCategory(activeFilterId), mode.name)
        }
        renderViewModeButtons()
        if (activeFilterId == R.id.filterFolders) {
            getAlbumsFragment()?.setViewMode(
                if (mode == GalleryViewMode.GRID) AlbumsFragment.ViewMode.GRID
                else AlbumsFragment.ViewMode.LIST
            )
            return
        }
        val fragment = getCurrentGalleryFragment() ?: return
        fragment.setViewMode(
            if (mode == GalleryViewMode.GRID) GalleryFragment.ViewMode.GRID
            else GalleryFragment.ViewMode.LIST
        )
    }

    private fun renderViewModeButtons() {
        val shouldShow = activeFilterId != R.id.filterGeo
        val visibility = if (shouldShow) View.VISIBLE else View.GONE
        binding.btnViewGrid.visibility = visibility
        binding.btnViewList.visibility = visibility
        if (!shouldShow) return

        val isGrid = currentGalleryViewMode == GalleryViewMode.GRID
        binding.btnViewGrid.isEnabled = !isGrid
        binding.btnViewList.isEnabled = isGrid
        binding.btnViewGrid.alpha = if (isGrid) 1f else 0.6f
        binding.btnViewList.alpha = if (isGrid) 0.6f else 1f
    }
    
    private fun getAlbumsFragment(): AlbumsFragment? {
        val tag = "f" + filterIdToPage(R.id.filterFolders)
        return supportFragmentManager.findFragmentByTag(tag) as? AlbumsFragment
    }

    private fun getGeoMapFragment(): GeoMapFragment? {
        val overlayGeo = supportFragmentManager.findFragmentById(R.id.container) as? GeoMapFragment
        if (overlayGeo != null) return overlayGeo
        return null
    }

    // ── Back press ────────────────────────────────────────────────────────────

    override fun onBackPressed() {
        // 1. Drawer detail panel visible → try step-by-step back
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START) && isDrawerDetailVisible()) {
            handleDrawerDetailBack()
            return
        }
        // 2. Drawer open → close drawer
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            return
        }
        // 3. Overlay fragment open (photo viewer, album detail, etc.) → pop back stack
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return
        }
        // 4. No overlay, no drawer → move to background instead of closing
        moveTaskToBack(true)
    }
}
