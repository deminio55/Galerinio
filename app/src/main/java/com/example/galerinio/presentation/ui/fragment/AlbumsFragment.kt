package com.example.galerinio.presentation.ui.fragment

import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.room.withTransaction
import com.example.galerinio.R
import com.example.galerinio.data.local.GalerioDatabase
import com.example.galerinio.data.local.entity.AlbumEntity
import com.example.galerinio.data.local.entity.MediaEntity
import com.example.galerinio.data.repository.AlbumRepositoryImpl
import com.example.galerinio.data.repository.MediaRepositoryImpl
import com.example.galerinio.data.util.MediaStoreObserver
import com.example.galerinio.data.util.MediaScanner
import com.example.galerinio.data.util.PreferencesManager
import com.example.galerinio.databinding.FragmentAlbumsBinding
import com.example.galerinio.presentation.adapter.AlbumAdapter
import com.example.galerinio.presentation.ui.activity.MainActivity
import com.example.galerinio.presentation.ui.activity.UnlockActivity
import com.example.galerinio.presentation.ui.util.DialogUi
import com.example.galerinio.presentation.ui.util.ThemeManager
import com.example.galerinio.presentation.viewmodel.AlbumViewModel
import com.example.galerinio.presentation.viewmodel.UiState
import com.example.galerinio.presentation.viewmodel.ViewModelFactory
import com.example.galerinio.domain.model.SortType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

class AlbumsFragment : Fragment() {

    enum class ViewMode { GRID, LIST }

    companion object {
        private const val MIN_SPAN_COUNT = 1
        private const val MAX_SPAN_COUNT = 6
        private const val SCALE_STEP = 1.08f
        private const val HYSTERESIS_DEAD_ZONE = 0.0025f
        private const val SPAN_CHANGE_COOLDOWN_MS = 90L
        private const val GRID_REFLOW_ANIMATION_COOLDOWN_MS = 110L
        private const val OBSERVER_DEBOUNCE_MS = 800L
        private const val FAST_SCROLL_HIDE_DELAY_MS = 1200L
    }
    
    private var _binding: FragmentAlbumsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var viewModel: AlbumViewModel
    private lateinit var albumAdapter: AlbumAdapter
    private val preferencesManager by lazy { PreferencesManager(requireContext()) }
    private val mediaScanner by lazy { MediaScanner(requireContext()) }
    private var spanCount = 2
    private var currentViewMode: ViewMode = ViewMode.GRID
    private var syncJob: Job? = null
    private var pinchAccumulatedScale = 1f
    private var lastSpanChangeAtMs = 0L
    private var lastGridReflowAnimAtMs = 0L
    private var isPinchActive = false
    private var gridColumnsChangedByPinch = false
    private var mediaStoreObserver: MediaStoreObserver? = null
    private var observerDebounceJob: Job? = null
    private var pendingObserverFullRescan = false
    private var manualRefreshInProgress = false
    private var currentSortOptions: com.example.galerinio.domain.model.SortOptions? = null
    private var rawAlbumsList: List<com.example.galerinio.domain.model.AlbumModel> = emptyList()
    private var protectedAlbumIds: Set<Long> = emptySet()
    private var folderProtectionEnabled: Boolean = false
    private var customAlbumOrderIds: List<Long> = emptyList()
    private var isCustomSortMode = false
    private var isCustomReorderEditing = false
    private var hasPendingCustomOrderChanges = false
    private var pendingOpenProtectedAlbum: com.example.galerinio.domain.model.AlbumModel? = null
    private var pendingToggleProtectionAlbum: com.example.galerinio.domain.model.AlbumModel? = null
    private var isFastScrollDragging = false
    private lateinit var itemTouchHelper: ItemTouchHelper
    private val fastScrollAutoHideRunnable = Runnable {
        if (_binding == null || isFastScrollDragging) return@Runnable
        binding.fastScrollContainer.animate().alpha(0f).setDuration(180L).withEndAction {
            if (_binding == null || isFastScrollDragging) return@withEndAction
            binding.fastScrollContainer.visibility = View.GONE
            binding.fastScrollContainer.alpha = 1f
        }.start()
    }
    private val unlockProtectedAlbumLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val album = pendingOpenProtectedAlbum
        pendingOpenProtectedAlbum = null
        if (result.resultCode == android.app.Activity.RESULT_OK && album != null) {
            openAlbum(album)
        }
    }
    private val unlockForProtectionToggleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val album = pendingToggleProtectionAlbum
        pendingToggleProtectionAlbum = null
        if (result.resultCode == android.app.Activity.RESULT_OK && album != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                if (!preferencesManager.isFolderProtectionEnabled()) return@launch
                preferencesManager.toggleAlbumProtection(album.id)
                protectedAlbumIds = preferencesManager.getProtectedAlbumIds()
                albumAdapter.setProtectedAlbumIds(protectedAlbumIds)
                val nowProtected = protectedAlbumIds.contains(album.id)
                val message = if (nowProtected) R.string.folder_marked_protected else R.string.folder_unmarked_protected
                Toast.makeText(requireContext(), getString(message), Toast.LENGTH_SHORT).show()
            }
        }
    }
    private val customReorderBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (hasPendingCustomOrderChanges) {
                showExitReorderDialog()
            } else {
                disableReorderEditMode()
            }
        }
    }

    private fun showExitReorderDialog() {
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.folders_reorder_unsaved_title))
            .setMessage(getString(R.string.folders_reorder_unsaved_message))
            .setPositiveButton(getString(R.string.folders_reorder_save_action)) { _, _ ->
                onCustomReorderSaveClicked()
            }
            .setNeutralButton(getString(R.string.folders_reorder_discard_action)) { _, _ ->
                discardPendingReorderChanges()
            }
            .setNegativeButton(getString(R.string.cancel), null)
        DialogUi.showWithReadableButtons(builder, requireContext())
    }

    private fun discardPendingReorderChanges() {
        hasPendingCustomOrderChanges = false
        updateAdapterWithSorting(rawAlbumsList)
        disableReorderEditMode()
    }

    private fun disableReorderEditMode() {
        isCustomReorderEditing = false
        updateCustomModeUi()
        Toast.makeText(requireContext(), getString(R.string.folders_reorder_edit_disabled), Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlbumsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyAlbumsPaletteColors()

        initViewModel()
        setupRecyclerView()
        setupFastScroller()
        restoreGridColumnsForAlbums()
        setupPinchToZoom()
        setupSwipeRefresh()
        setupBackPress()
        observeProtectionState()
        observeViewModel()
        refreshLibrary(forceRescan = false)
    }

    private fun setupBackPress() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            customReorderBackCallback
        )
    }

    override fun onResume() {
        super.onResume()
        applyAlbumsPaletteColors()
        (activity as? MainActivity)?.setTopBarTitle(getString(R.string.menu_folders))
        applyFastScrollerAccent()
        updateFastScrollerVisibility()
        updateCustomModeUi()
        restoreGridColumnsForAlbums()
        viewLifecycleOwner.lifecycleScope.launch {
            syncProtectionStateForUi()
        }
        // Do not rescan albums on every resume; this blocks map/tab rendering on low-end devices.
        if (hasMediaReadPermission() && rawAlbumsList.isEmpty()) {
            refreshLibrary(forceRescan = false)
        }
    }

    override fun onStart() {
        super.onStart()
        registerMediaStoreObserver()
    }

    override fun onStop() {
        unregisterMediaStoreObserver()
        super.onStop()
    }
    
    private fun initViewModel() {
        val database = GalerioDatabase.getInstance(requireContext())
        val mediaRepository = MediaRepositoryImpl(database.mediaDao())
        val albumRepository = AlbumRepositoryImpl(database.albumDao())
        val factory = ViewModelFactory(mediaRepository, albumRepository)
        viewModel = ViewModelProvider(this, factory)[AlbumViewModel::class.java]
    }
    
    private fun setupRecyclerView() {
        albumAdapter = AlbumAdapter(
            onItemClick = { album -> onAlbumClick(album) },
            onItemLongClick = { album -> onAlbumLongClick(album) },
            onProtectionClick = { album -> onProtectionClick(album) }
        )
        albumAdapter.setDisplayMode(
            if (currentViewMode == ViewMode.LIST) AlbumAdapter.DisplayMode.LIST
            else AlbumAdapter.DisplayMode.GRID
        )

        val dragCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = isCustomSortMode && isCustomReorderEditing

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                if (!isCustomSortMode || !isCustomReorderEditing) return makeMovementFlags(0, 0)
                val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END
                return makeMovementFlags(dragFlags, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (!isCustomSortMode || !isCustomReorderEditing) return false
                val moved = albumAdapter.swapItems(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                if (moved) {
                    hasPendingCustomOrderChanges = true
                    updateCustomModeUi()
                }
                return moved
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }
        itemTouchHelper = ItemTouchHelper(dragCallback)

        binding.recyclerView.apply {
            adapter = albumAdapter
            layoutManager = GridLayoutManager(context, getActiveSpanCount())
        }
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)
        applyFastScrollerAccent()
        updateFastScrollerVisibility()
    }

    private fun applyFastScrollerAccent() {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val isDarkMode = preferencesManager.isDarkModeEnabled()
            val accent = preferencesManager.getThemeAccent(isDarkMode)
            val accentColor = ThemeManager.resolveAccentColors(isDarkMode, accent).accent
            binding.fastScrollThumb.backgroundTintList =
                ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(accentColor, 172))
            binding.fastScrollTrack.backgroundTintList =
                ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(accentColor, 52))
        }
    }

    private fun applyAlbumsPaletteColors() {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val isDarkMode = preferencesManager.isDarkModeEnabled()
            val palette = preferencesManager.getThemePalette(isDarkMode)
            val accent = preferencesManager.getThemeAccent(isDarkMode)
            val colors = ThemeManager.resolvePaletteColors(isDarkMode, palette)
            val accentColor = ThemeManager.resolveAccentColors(isDarkMode, accent).accent

            binding.root.setBackgroundColor(colors.background)
            binding.swipeRefresh.setProgressBackgroundColorSchemeColor(colors.surface)
            binding.swipeRefresh.setColorSchemeColors(accentColor)
        }
    }

    private fun setupFastScroller() {
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (!canUseFastScroller()) return
                if (newState == RecyclerView.SCROLL_STATE_IDLE && !isFastScrollDragging) {
                    scheduleFastScrollerHide()
                } else {
                    showFastScroller()
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!canUseFastScroller()) return
                if (dy != 0) {
                    showFastScroller()
                    if (!isFastScrollDragging) {
                        syncFastScrollThumbWithRecycler()
                    }
                }
            }
        })

        binding.fastScrollContainer.setOnTouchListener { _, event ->
            if (!canUseFastScroller()) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isTouchOnFastScrollThumb(event.x, event.y)) return@setOnTouchListener false
                    isFastScrollDragging = true
                    binding.recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
                    showFastScroller()
                    handleFastScroll(event.y)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isFastScrollDragging) return@setOnTouchListener false
                    handleFastScroll(event.y)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isFastScrollDragging) return@setOnTouchListener false
                    isFastScrollDragging = false
                    scheduleFastScrollerHide()
                    true
                }
                else -> false
            }
        }
    }

    private fun canUseFastScroller(): Boolean = albumAdapter.itemCount > 20

    private fun updateFastScrollerVisibility() {
        if (!canUseFastScroller()) {
            binding.fastScrollContainer.removeCallbacks(fastScrollAutoHideRunnable)
            binding.fastScrollContainer.visibility = View.GONE
            return
        }
        binding.fastScrollContainer.visibility = View.GONE
        binding.fastScrollContainer.post {
            if (_binding == null || isFastScrollDragging) return@post
            syncFastScrollThumbWithRecycler()
        }
        if (!isFastScrollDragging) {
            scheduleFastScrollerHide()
        }
    }

    private fun showFastScroller() {
        if (!canUseFastScroller()) {
            binding.fastScrollContainer.visibility = View.GONE
            return
        }
        binding.fastScrollContainer.removeCallbacks(fastScrollAutoHideRunnable)
        if (binding.fastScrollContainer.visibility != View.VISIBLE) {
            binding.fastScrollContainer.alpha = 0f
            binding.fastScrollContainer.visibility = View.VISIBLE
            binding.fastScrollContainer.animate().alpha(1f).setDuration(120L).start()
        }
    }

    private fun scheduleFastScrollerHide() {
        if (!canUseFastScroller()) return
        binding.fastScrollContainer.removeCallbacks(fastScrollAutoHideRunnable)
        binding.fastScrollContainer.postDelayed(fastScrollAutoHideRunnable, FAST_SCROLL_HIDE_DELAY_MS)
    }

    private fun isTouchOnFastScrollThumb(xInContainer: Float, yInContainer: Float): Boolean {
        val thumb = binding.fastScrollThumb
        return xInContainer >= thumb.x &&
            xInContainer <= (thumb.x + thumb.width) &&
            yInContainer >= thumb.y &&
            yInContainer <= (thumb.y + thumb.height)
    }

    private fun handleFastScroll(yInContainer: Float) {
        val track = binding.fastScrollTrack
        val thumb = binding.fastScrollThumb
        val itemCount = albumAdapter.itemCount
        if (itemCount <= 0) return

        val trackTop = track.top.toFloat()
        val trackBottom = track.bottom.toFloat()
        val thumbHalf = thumb.height / 2f
        val minCenter = trackTop + thumbHalf
        val maxCenter = trackBottom - thumbHalf
        val clampedCenter = yInContainer.coerceIn(minCenter, maxCenter)
        thumb.y = clampedCenter - thumbHalf

        val usableHeight = (maxCenter - minCenter).coerceAtLeast(1f)
        val fraction = ((clampedCenter - minCenter) / usableHeight).coerceIn(0f, 1f)
        val targetPos = (fraction * (itemCount - 1)).roundToInt().coerceIn(0, itemCount - 1)

        val lm = binding.recyclerView.layoutManager as? GridLayoutManager
        if (lm != null) {
            lm.scrollToPositionWithOffset(targetPos, 0)
        } else {
            binding.recyclerView.scrollToPosition(targetPos)
        }
    }

    private fun syncFastScrollThumbWithRecycler(): Float? {
        if (!canUseFastScroller()) return null
        val rv = binding.recyclerView
        val range = rv.computeVerticalScrollRange()
        val extent = rv.computeVerticalScrollExtent().coerceAtLeast(1)
        val maxOffset = (range - extent).coerceAtLeast(1)
        val offset = rv.computeVerticalScrollOffset().coerceIn(0, maxOffset)
        val fraction = (offset.toFloat() / maxOffset.toFloat()).coerceIn(0f, 1f)

        val track = binding.fastScrollTrack
        val thumb = binding.fastScrollThumb
        val thumbHalf = thumb.height / 2f
        val minCenter = track.top + thumbHalf
        val maxCenter = track.bottom - thumbHalf
        val usable = (maxCenter - minCenter).coerceAtLeast(1f)
        val center = minCenter + fraction * usable
        thumb.y = center - thumbHalf
        return center
    }

    private fun onAlbumClick(album: com.example.galerinio.domain.model.AlbumModel) {
        viewLifecycleOwner.lifecycleScope.launch {
            val folderProtectionEnabled = preferencesManager.isFolderProtectionEnabled()
            val albumProtected = folderProtectionEnabled && protectedAlbumIds.contains(album.id)
            if (!albumProtected) {
                openAlbum(album)
                return@launch
            }
            if (!hasAnyEnabledLockMethod()) {
                Toast.makeText(requireContext(), getString(R.string.security_select_method_to_activate), Toast.LENGTH_SHORT).show()
                return@launch
            }
            Toast.makeText(requireContext(), getString(R.string.folder_open_requires_unlock), Toast.LENGTH_SHORT).show()
            pendingOpenProtectedAlbum = album
            unlockProtectedAlbumLauncher.launch(
                Intent(requireContext(), UnlockActivity::class.java)
                    .putExtra(UnlockActivity.EXTRA_UNLOCK_SCOPE, UnlockActivity.SCOPE_FOLDER)
            )
        }
    }

    private fun onAlbumLongClick(album: com.example.galerinio.domain.model.AlbumModel) {
        Toast.makeText(requireContext(), getString(R.string.folder_tap_lock_hint), Toast.LENGTH_SHORT).show()
    }

    private fun onProtectionClick(album: com.example.galerinio.domain.model.AlbumModel) {
        requestToggleProtection(album)
    }

    private fun requestToggleProtection(album: com.example.galerinio.domain.model.AlbumModel) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!preferencesManager.isFolderProtectionEnabled()) {
                Toast.makeText(requireContext(), getString(R.string.folder_protection_disabled), Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!hasAnyEnabledLockMethod()) {
                Toast.makeText(requireContext(), getString(R.string.security_select_method_to_activate), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val isProtected = protectedAlbumIds.contains(album.id)
            pendingToggleProtectionAlbum = album
            val message = if (isProtected) R.string.folder_unlock_to_unprotect else R.string.folder_unlock_to_protect
            Toast.makeText(requireContext(), getString(message), Toast.LENGTH_SHORT).show()
            unlockForProtectionToggleLauncher.launch(
                Intent(requireContext(), UnlockActivity::class.java)
                    .putExtra(UnlockActivity.EXTRA_UNLOCK_SCOPE, UnlockActivity.SCOPE_FOLDER)
            )
        }
    }

    private suspend fun hasAnyEnabledLockMethod(): Boolean {
        return preferencesManager.getFolderLockMethod() != com.example.galerinio.data.util.PreferencesManager.LockMethod.NONE
    }

    private fun openAlbum(album: com.example.galerinio.domain.model.AlbumModel) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, GalleryFragment.newAlbumInstance(album.id, album.name, album.path))
            .addToBackStack(null)
            .commit()
    }

    fun onMainPageSelected() {
        if (_binding == null) return
        applyAlbumsPaletteColors()
        // Принудительно обновляем отображение адаптера
        refreshAdapterDisplay()
        if (hasMediaReadPermission() && rawAlbumsList.isEmpty()) {
            refreshLibrary(forceRescan = false)
        }
    }


    private fun scrollToTop() {
        val layoutManager = binding.recyclerView.layoutManager as? GridLayoutManager ?: return
        binding.recyclerView.stopScroll()
        layoutManager.scrollToPositionWithOffset(0, 0)
        binding.recyclerView.post { binding.recyclerView.scrollToPosition(0) }
    }

    private fun refreshAdapterDisplay() {
        // Убедимся, что RecyclerView виден
        if (rawAlbumsList.isNotEmpty()) {
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE

            // КРИТИЧНО: Повторно отправляем данные в адаптер через submitList
            val sortOptions = currentSortOptions
            if (sortOptions != null) {
                updateAdapterWithSorting(rawAlbumsList)
                binding.recyclerView.invalidate()
            }
        }
    }

    private fun restoreGridColumnsForAlbums() {
        viewLifecycleOwner.lifecycleScope.launch {
            val restored = preferencesManager
                .getAlbumsGridColumns()
                .coerceIn(MIN_SPAN_COUNT, MAX_SPAN_COUNT)
            spanCount = restored
            applyViewModeToLayoutManager()
        }
    }

    private fun persistGridColumnsForAlbums() {
        viewLifecycleOwner.lifecycleScope.launch {
            preferencesManager.setAlbumsGridColumns(spanCount)
        }
    }

    private fun setupPinchToZoom() {
        val detector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    isPinchActive = true
                    pinchAccumulatedScale = 1f
                    lastSpanChangeAtMs = 0L
                    binding.swipeRefresh.isEnabled = false
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (currentViewMode != ViewMode.GRID) return true
                    val layoutManager = binding.recyclerView.layoutManager as? GridLayoutManager ?: return true
                    val delta = detector.scaleFactor
                    if (abs(delta - 1f) < HYSTERESIS_DEAD_ZONE) return true

                    pinchAccumulatedScale *= delta
                    val now = System.currentTimeMillis()
                    if (now - lastSpanChangeAtMs < SPAN_CHANGE_COOLDOWN_MS) return true

                    val pendingSpanCount = when {
                        pinchAccumulatedScale > SCALE_STEP && spanCount > MIN_SPAN_COUNT -> spanCount - 1
                        pinchAccumulatedScale < 1f / SCALE_STEP && spanCount < MAX_SPAN_COUNT -> spanCount + 1
                        else -> spanCount
                    }

                    if (pendingSpanCount != spanCount) {
                        spanCount = pendingSpanCount
                        layoutManager.spanCount = getActiveSpanCount()
                        animateGridReflowIfNeeded(now)
                        // One pinch threshold changes only one column step.
                        pinchAccumulatedScale = 1f
                        lastSpanChangeAtMs = now
                        gridColumnsChangedByPinch = true
                    }
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isPinchActive = false
                    pinchAccumulatedScale = 1f
                    binding.swipeRefresh.isEnabled = true
                    if (gridColumnsChangedByPinch) {
                        persistGridColumnsForAlbums()
                        gridColumnsChangedByPinch = false
                    }
                }
            }
        )

        binding.recyclerView.setOnTouchListener { _, event ->
            val inPinch = event.pointerCount >= 2 || isPinchActive
            if (inPinch) {
                detector.onTouchEvent(event)
            }
            inPinch
        }
    }
    
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Load sort options
            currentSortOptions = com.example.galerinio.domain.model.SortOptions(
                sortType = com.example.galerinio.domain.model.SortType.valueOf(
                    preferencesManager.getSortType("albums")
                ),
                isDescending = preferencesManager.getSortDescending("albums"),
                groupingType = com.example.galerinio.domain.model.GroupingType.NONE
            )
            customAlbumOrderIds = preferencesManager.getAlbumsCustomOrder()

            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { uiState ->
                    when (uiState) {
                        is UiState.Loading -> {
                            binding.swipeRefresh.isRefreshing = manualRefreshInProgress
                            binding.recyclerView.visibility = View.VISIBLE
                            binding.emptyState.visibility = View.GONE
                        }
                        is UiState.Success -> {
                            manualRefreshInProgress = false
                            binding.swipeRefresh.isRefreshing = false
                            binding.recyclerView.visibility = View.VISIBLE
                            binding.emptyState.visibility = View.GONE

                            rawAlbumsList = uiState.data
                            updateAdapterWithSorting(uiState.data)
                            updateFastScrollerVisibility()
                        }
                        is UiState.Empty -> {
                            manualRefreshInProgress = false
                            binding.swipeRefresh.isRefreshing = false
                            binding.recyclerView.visibility = View.GONE
                            binding.emptyState.visibility = View.VISIBLE
                            updateFastScrollerVisibility()
                        }
                        is UiState.Error -> {
                            manualRefreshInProgress = false
                            binding.swipeRefresh.isRefreshing = false
                            binding.recyclerView.visibility = View.GONE
                            binding.emptyState.visibility = View.VISIBLE
                            updateFastScrollerVisibility()
                        }
                    }
                }
            }
        }
    }

    private fun observeProtectionState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                preferencesManager.folderProtectionEnabledFlow
                    .combine(preferencesManager.protectedAlbumIdsFlow) { enabled, ids ->
                        enabled to if (enabled) ids else emptySet()
                    }
                    .collect { (enabled, ids) ->
                        folderProtectionEnabled = enabled
                        protectedAlbumIds = ids
                        albumAdapter.setFolderProtectionEnabled(enabled)
                        albumAdapter.setProtectedAlbumIds(ids)
                    }
            }
        }
    }

    private suspend fun syncProtectionStateForUi() {
        folderProtectionEnabled = preferencesManager.isFolderProtectionEnabled()
        protectedAlbumIds = if (folderProtectionEnabled) {
            preferencesManager.getProtectedAlbumIds()
        } else {
            emptySet()
        }
        albumAdapter.setFolderProtectionEnabled(folderProtectionEnabled)
        albumAdapter.setProtectedAlbumIds(protectedAlbumIds)
    }

    private fun updateAdapterWithSorting(albums: List<com.example.galerinio.domain.model.AlbumModel>) {
        val sortOptions = currentSortOptions ?: return
        isCustomSortMode = sortOptions.sortType == SortType.CUSTOM
        if (!isCustomSortMode) {
            isCustomReorderEditing = false
        }

        val sorted = if (isCustomSortMode) {
            val orderSource = if (hasPendingCustomOrderChanges) {
                albumAdapter.getCurrentItems().map { it.id }
            } else {
                customAlbumOrderIds
            }
            applyCustomOrder(albums, orderSource)
        } else {
            hasPendingCustomOrderChanges = false
            com.example.galerinio.data.util.MediaSorter.sortAlbums(albums, sortOptions)
        }

        albumAdapter.submitList(sorted)
        updateCustomModeUi()
        updateFastScrollerVisibility()
    }

    private fun applyCustomOrder(
        albums: List<com.example.galerinio.domain.model.AlbumModel>,
        preferredOrder: List<Long>
    ): List<com.example.galerinio.domain.model.AlbumModel> {
        if (preferredOrder.isEmpty()) return albums

        val byId = albums.associateBy { it.id }
        val result = mutableListOf<com.example.galerinio.domain.model.AlbumModel>()
        preferredOrder.forEach { id -> byId[id]?.let(result::add) }
        albums.forEach { album -> if (result.none { it.id == album.id }) result.add(album) }
        return result
    }

    private fun updateCustomModeUi() {
        if (_binding == null) return
        albumAdapter.setReorderEditing(isCustomSortMode && isCustomReorderEditing)
        customReorderBackCallback.isEnabled = isCustomSortMode && isCustomReorderEditing
        binding.swipeRefresh.isEnabled = !isCustomSortMode
        (activity as? MainActivity)?.updateAlbumsCustomTopActions(
            isVisible = isCustomSortMode,
            isEditing = isCustomReorderEditing,
            canSave = hasPendingCustomOrderChanges
        )
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            manualRefreshInProgress = true
            binding.swipeRefresh.isRefreshing = true
            refreshLibrary(forceRescan = true, showRefreshIndicator = true)
        }
    }

    private fun refreshLibrary(forceRescan: Boolean, showRefreshIndicator: Boolean = false) {
        if (!hasMediaReadPermission()) {
            manualRefreshInProgress = false
            _binding?.swipeRefresh?.isRefreshing = false
            Toast.makeText(requireContext(), "Grant media access in system settings", Toast.LENGTH_LONG).show()
            return
        }

        if (showRefreshIndicator) {
            manualRefreshInProgress = true
            _binding?.swipeRefresh?.isRefreshing = true
        }

        if (syncJob?.isActive == true) {
            if (forceRescan) pendingObserverFullRescan = true
            if (!manualRefreshInProgress) {
                _binding?.swipeRefresh?.isRefreshing = false
            }
            return
        }

        syncJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val db = GalerioDatabase.getInstance(requireContext())
                val lastScanAt = preferencesManager.lastLibraryScanAtFlow.first()
                val schemaVersion = preferencesManager.getScannerSchemaVersion()
                val schemaOutdated = schemaVersion < MediaScanner.SCANNER_SCHEMA_VERSION
                val fullScan = forceRescan || schemaOutdated || lastScanAt <= 0L

                val favoriteIds = withContext(Dispatchers.IO) {
                    db.mediaDao().getFavoriteIds().toSet()
                }

                val currentMediaIds = withContext(Dispatchers.IO) {
                    mediaScanner.getCurrentMediaIds()
                }
                val existingIds = withContext(Dispatchers.IO) {
                    db.mediaDao().getAllIds().toSet()
                }
                val shouldFallbackToFullScan = !fullScan && (currentMediaIds - existingIds).isNotEmpty()
                val mediaList = withContext(Dispatchers.IO) {
                    mediaScanner.scanMediaFiles(sinceMs = if (fullScan || shouldFallbackToFullScan) null else lastScanAt)
                }
                val albums = withContext(Dispatchers.IO) {
                    mediaScanner.scanAlbums()
                }

                withContext(Dispatchers.IO) {
                    db.withTransaction {
                        if (mediaList.isNotEmpty()) {
                            db.mediaDao().insertAllMedia(mediaList.map { m ->
                                MediaEntity(
                                    id = m.id,
                                    fileName = m.fileName,
                                    filePath = m.filePath,
                                    mimeType = m.mimeType,
                                    size = m.size,
                                    dateModified = m.dateModified,
                                    dateAdded = m.dateAdded,
                                    width = m.width,
                                    height = m.height,
                                    duration = m.duration,
                                    albumId = m.albumId,
                                    isFavorite = m.id in favoriteIds
                                )
                            })
                        }

                        val staleIds = db.mediaDao().getAllIds().toSet() - currentMediaIds
                        if (staleIds.isNotEmpty()) {
                            db.mediaDao().deleteMediaByIds(staleIds.toList())
                        }

                        db.albumDao().clearAlbums()
                        if (albums.isNotEmpty()) {
                            db.albumDao().insertAllAlbums(albums.map { a ->
                                AlbumEntity(
                                    id = a.id,
                                    name = a.name,
                                    path = a.path,
                                    coverMediaId = a.coverMediaId,
                                    mediaCount = a.mediaCount,
                                    dateAdded = a.dateAdded
                                )
                            })
                        }
                    }
                }

                preferencesManager.setLastLibraryScanAt(System.currentTimeMillis())
                preferencesManager.setScannerSchemaVersion(MediaScanner.SCANNER_SCHEMA_VERSION)
                viewModel.loadAllAlbums()
            } catch (_: Exception) {
                viewModel.loadAllAlbums()
            } finally {
                if (!manualRefreshInProgress) {
                    _binding?.swipeRefresh?.isRefreshing = false
                }
                if (pendingObserverFullRescan && _binding != null) {
                    pendingObserverFullRescan = false
                    refreshLibrary(forceRescan = true)
                }
            }
        }
    }

    private fun registerMediaStoreObserver() {
        if (mediaStoreObserver != null) return
        mediaStoreObserver = MediaStoreObserver(
            contentResolver = requireContext().contentResolver,
            observedUris = listOf(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                android.provider.MediaStore.Files.getContentUri("external")
            )
        ) {
            observerDebounceJob?.cancel()
            observerDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(OBSERVER_DEBOUNCE_MS)
                refreshLibrary(forceRescan = true)
            }
        }
        mediaStoreObserver?.register()
    }

    private fun unregisterMediaStoreObserver() {
        observerDebounceJob?.cancel()
        observerDebounceJob = null
        mediaStoreObserver?.unregister()
        mediaStoreObserver = null
    }

    private fun hasMediaReadPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val hasImages = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            val hasVideos = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            val hasSelectedVisual = if (android.os.Build.VERSION.SDK_INT >= 34) {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
            hasImages || hasVideos || hasSelectedVisual
        } else {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    override fun onDestroyView() {
        unregisterMediaStoreObserver()
        _binding?.fastScrollContainer?.removeCallbacks(fastScrollAutoHideRunnable)
        isFastScrollDragging = false
        (activity as? MainActivity)?.updateAlbumsCustomTopActions(
            isVisible = false,
            isEditing = false,
            canSave = false
        )
        super.onDestroyView()
        _binding = null
    }

    private fun animateGridReflowIfNeeded(nowMs: Long) {
        if (nowMs - lastGridReflowAnimAtMs < GRID_REFLOW_ANIMATION_COOLDOWN_MS) return
        lastGridReflowAnimAtMs = nowMs

        binding.recyclerView.animate().cancel()
        binding.recyclerView.animate()
            .alpha(0.9f)
            .setDuration(60L)
            .withEndAction {
                if (_binding == null) return@withEndAction
                binding.recyclerView.animate().alpha(1f).setDuration(120L).start()
            }
            .start()
    }

    // ── Sort and Grouping ─────────────────────────────────────────────────────

    fun applySortOptions(options: com.example.galerinio.domain.model.SortOptions) {
        val enteringCustomMode = options.sortType == SortType.CUSTOM && currentSortOptions?.sortType != SortType.CUSTOM
        currentSortOptions = options
        if (options.sortType != SortType.CUSTOM) {
            hasPendingCustomOrderChanges = false
            isCustomReorderEditing = false
        }
        updateAdapterWithSorting(rawAlbumsList)
        if (enteringCustomMode) {
            Toast.makeText(requireContext(), getString(R.string.folders_reorder_mode_enabled), Toast.LENGTH_SHORT).show()
        }
    }

    fun onCustomReorderEditClicked() {
        if (!isCustomSortMode) return
        isCustomReorderEditing = true
        updateCustomModeUi()
        Toast.makeText(requireContext(), getString(R.string.folders_reorder_edit_enabled), Toast.LENGTH_SHORT).show()
    }

    fun onCustomReorderSaveClicked() {
        if (!isCustomSortMode) return
        viewLifecycleOwner.lifecycleScope.launch {
            val currentOrder = albumAdapter.getCurrentItems().map { it.id }
            preferencesManager.setAlbumsCustomOrder(currentOrder)
            customAlbumOrderIds = currentOrder
            hasPendingCustomOrderChanges = false
            isCustomReorderEditing = false
            updateCustomModeUi()
            Toast.makeText(requireContext(), getString(R.string.folders_order_saved), Toast.LENGTH_SHORT).show()
        }
    }

    fun setViewMode(mode: ViewMode) {
        if (currentViewMode == mode) return
        currentViewMode = mode
        albumAdapter.setDisplayMode(
            if (mode == ViewMode.LIST) AlbumAdapter.DisplayMode.LIST
            else AlbumAdapter.DisplayMode.GRID
        )
        applyViewModeToLayoutManager()
        binding.recyclerView.requestLayout()
    }

    private fun getActiveSpanCount(): Int = if (currentViewMode == ViewMode.LIST) 1 else spanCount

    private fun applyViewModeToLayoutManager() {
        val layoutManager = binding.recyclerView.layoutManager as? GridLayoutManager ?: return
        layoutManager.spanCount = getActiveSpanCount()
    }
}

