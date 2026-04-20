package com.example.galerinio.presentation.ui.fragment

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.core.app.SharedElementCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.withTransaction
import com.example.galerinio.R
import com.example.galerinio.data.local.GalerioDatabase
import com.example.galerinio.data.repository.MediaRepositoryImpl
import com.example.galerinio.data.repository.TrashRepositoryImpl
import com.example.galerinio.data.repository.UserPermissionRequiredException
import com.example.galerinio.data.util.MediaStoreObserver
import com.example.galerinio.data.util.MediaScanner
import com.example.galerinio.data.util.PreferencesManager
import com.example.galerinio.databinding.FragmentGalleryBinding
import com.example.galerinio.domain.model.MediaModel
import com.example.galerinio.presentation.adapter.MediaAdapter
import com.example.galerinio.presentation.ui.activity.MainActivity
import com.example.galerinio.presentation.ui.activity.UnlockActivity
import com.example.galerinio.presentation.ui.util.DialogUi
import com.example.galerinio.presentation.ui.util.ThemeManager
import com.example.galerinio.presentation.viewmodel.GalleryViewModel
import com.example.galerinio.presentation.viewmodel.UiState
import com.example.galerinio.presentation.viewmodel.ViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class GalleryFragment : Fragment() {

    companion object {
        private const val ARG_FILTER = "arg_filter"
        private const val ARG_ALBUM_ID = "arg_album_id"
        private const val ARG_ALBUM_NAME = "arg_album_name"
        private const val ARG_ALBUM_PATH = "arg_album_path"
        private const val ARG_MEDIA_IDS = "arg_media_ids"
        private const val ARG_CUSTOM_TITLE = "arg_custom_title"
        private const val AUTO_SCAN_INTERVAL_MS = 5 * 60 * 1000L
        private const val MIN_SPAN_COUNT = 1
        private const val MAX_SPAN_COUNT = 6
        private const val SCALE_STEP = 1.08f
        private const val HYSTERESIS_DEAD_ZONE = 0.0025f
        private const val SPAN_CHANGE_COOLDOWN_MS = 90L
        private const val GRID_REFLOW_ANIMATION_COOLDOWN_MS = 110L
        private const val OBSERVER_DEBOUNCE_MS = 800L
        private const val FAST_SCROLL_HIDE_DELAY_MS = 1200L
        private const val TAG = "GalleryFragment"

        fun newInstance(filter: GalleryFilter): GalleryFragment {
            return GalleryFragment().apply {
                arguments = Bundle().apply { putString(ARG_FILTER, filter.name) }
            }
        }

        fun newAlbumInstance(albumId: Long, albumName: String, albumPath: String = ""): GalleryFragment {
            return GalleryFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FILTER, GalleryFilter.ALL.name)
                    putLong(ARG_ALBUM_ID, albumId)
                    putString(ARG_ALBUM_NAME, albumName)
                    putString(ARG_ALBUM_PATH, albumPath)
                }
            }
        }

        fun newGeoClusterInstance(mediaIds: LongArray, title: String? = null): GalleryFragment {
            return GalleryFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FILTER, GalleryFilter.ALL.name)
                    putLongArray(ARG_MEDIA_IDS, mediaIds)
                    putString(ARG_CUSTOM_TITLE, title)
                }
            }
        }
    }

    enum class GalleryFilter { ALL, PHOTOS, VIDEOS, FAVORITES }
    enum class ViewMode { GRID, LIST }
    private enum class PendingFileOperation { COPY, MOVE }
    private enum class FastScrollBubbleMode { HIDDEN, DRAG }

    // ── ActivityResult launchers ──────────────────────────────────────────────

    /** Для удаления через MediaStore API 30+ */
    private var pendingDeleteItems: List<MediaModel> = emptyList()
    private var pendingDeleteForTrash: MediaModel? = null
    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Пользователь дал разрешение на удаление
            val trashItem = pendingDeleteForTrash
            if (trashItem != null) {
                pendingDeleteForTrash = null
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        // После разрешения файл уже удален системой из MediaStore
                        // Файл УЖЕ в корзине и БД, просто обновляем галерею
                        viewModel.deleteMultipleMedia(listOf(trashItem.id))
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.moved_to_trash),
                            Toast.LENGTH_SHORT
                        ).show()
                        exitSelectionMode()
                    } catch (e: Exception) {
                        android.util.Log.e("GalleryFragment", "Error after permission granted", e)
                        Toast.makeText(
                            requireContext(),
                            "Error: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } else {
                viewModel.deleteMultipleMedia(pendingDeleteItems.map { it.id })
                Toast.makeText(
                    requireContext(),
                    getString(R.string.files_deleted, pendingDeleteItems.size),
                    Toast.LENGTH_SHORT
                ).show()
                pendingDeleteItems = emptyList()
                exitSelectionMode()
            }
        } else {
            pendingDeleteForTrash = null
            Toast.makeText(
                requireContext(),
                getString(R.string.failed_move_to_trash),
                Toast.LENGTH_SHORT
            ).show()
            exitSelectionMode()
        }
    }

    /** Для запроса разрешений (современный API) */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it } || hasMediaReadPermission()) {
            if (selectedMediaIds != null) {
                applyCurrentFilter()
            } else {
                loadInitialContent(forceRescan = true)
            }
        } else {
            Toast.makeText(requireContext(), "Grant media access in system settings", Toast.LENGTH_LONG).show()
        }
    }

    /** Для выбора папки назначения (копирование / перемещение) */
    private var pendingFileOperation: PendingFileOperation = PendingFileOperation.COPY
    private var pendingOperationItems: List<MediaModel> = emptyList()
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            when (pendingFileOperation) {
                PendingFileOperation.COPY -> performCopy(uri, pendingOperationItems)
                PendingFileOperation.MOVE -> performMove(uri, pendingOperationItems)
            }
        }
        pendingOperationItems = emptyList()
    }

    private var pendingProtectedMediaToOpen: MediaModel? = null
    private var pendingProtectedTransitionName: String? = null
    private val unlockProtectedMediaLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val media = pendingProtectedMediaToOpen
        val transitionName = pendingProtectedTransitionName
        pendingProtectedMediaToOpen = null
        pendingProtectedTransitionName = null
        if (result.resultCode == Activity.RESULT_OK && media != null) {
            openPhotoView(media, transitionName ?: "media_thumb_${media.id}")
        }
    }

    // ── Поля ─────────────────────────────────────────────────────────────────

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: GalleryViewModel
    private lateinit var trashRepository: TrashRepositoryImpl
    private var mediaAdapter: MediaAdapter? = null
    private var groupedMediaAdapter: com.example.galerinio.presentation.adapter.GroupedMediaAdapter? = null
    private val mediaScanner by lazy { MediaScanner(requireContext()) }
    private val preferencesManager by lazy { PreferencesManager(requireContext()) }
    private var spanCount = 3
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
    private var pendingReturnMediaId: Long? = null
    private var pendingReturnTransitionName: String? = null
    private var manualRefreshInProgress = false
    private var currentSortOptions: com.example.galerinio.domain.model.SortOptions? = null
    private var rawMediaList: List<com.example.galerinio.domain.model.MediaModel> = emptyList()
    private var mediaRotations: Map<Long, Int> = emptyMap()
    private var rotationLoadJob: Job? = null
    private var latestRotationRequestIds: List<Long> = emptyList()
    private var folderProtectionEnabled = false
    private var protectedAlbumIds: Set<Long> = emptySet()
    private var protectedMediaDisplayMode = PreferencesManager.ProtectedMediaDisplayMode.HIDE
    private var fastScrollMediaList: List<com.example.galerinio.domain.model.MediaModel> = emptyList()
    private var fastScrollAdapterPositions: IntArray = IntArray(0)
    private val fastScrollMonthFormat = SimpleDateFormat("MMM", Locale.getDefault())
    private val fastScrollYearFormat = SimpleDateFormat("yyyy", Locale.getDefault())
    private var isFastScrollDragging = false
    private var fastScrollBubbleMode = FastScrollBubbleMode.HIDDEN
    private val fastScrollAutoHideRunnable = Runnable {
        if (_binding == null || isFastScrollDragging) return@Runnable
        binding.fastScrollContainer.animate().alpha(0f).setDuration(180L).withEndAction {
            if (_binding == null || isFastScrollDragging) return@withEndAction
            binding.fastScrollContainer.visibility = View.GONE
            binding.fastScrollContainer.alpha = 1f
            applyFastScrollBubbleMode(FastScrollBubbleMode.HIDDEN)
        }.start()
    }

    /**
     * Колбэк кнопки «Назад» для режима выделения.
     * Включается ТОЛЬКО когда есть активное выделение — это гарантирует,
     * что после exitSelectionMode() система снова обрабатывает back press штатно
     * (без риска что isEnabled останется false навсегда).
     */
    private val selectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitSelectionMode()
        }
    }

    private val currentFilter: GalleryFilter by lazy {
        arguments?.getString(ARG_FILTER)
            ?.let { v -> GalleryFilter.entries.find { it.name == v } }
            ?: GalleryFilter.ALL
    }
    /**
     * Long.MIN_VALUE is the "not set" sentinel. This way ALL Long values
     * (including negative ones from FNV/hashCode) are treated as valid album IDs.
     */
    private val selectedAlbumId: Long? by lazy {
        val id = arguments?.getLong(ARG_ALBUM_ID, Long.MIN_VALUE) ?: Long.MIN_VALUE
        if (id != Long.MIN_VALUE) id else null
    }
    private val selectedAlbumName: String? by lazy {
        arguments?.getString(ARG_ALBUM_NAME)
    }
    /**
     * Absolute folder path (Android < Q) used for path-based media filtering,
     * which is more reliable than albumId-based filtering on Android 9 OEM devices.
     * On Android >= Q the value is a relative path and albumId is used instead.
     */
    private val selectedAlbumPath: String? by lazy {
        arguments?.getString(ARG_ALBUM_PATH)?.takeIf { it.isNotBlank() }
    }
    private val selectedMediaIds: List<Long>? by lazy {
        arguments?.getLongArray(ARG_MEDIA_IDS)?.toList()?.takeIf { it.isNotEmpty() }
    }
    private val customScreenTitle: String? by lazy {
        arguments?.getString(ARG_CUSTOM_TITLE)?.takeIf { it.isNotBlank() }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyGalleryPaletteColors()
        updateTopBarTitleForCurrentScreen()
        initViewModel()
        setupRecyclerView()
        setupFastScroller()
        setupSharedElementReturnHandling()
        restoreGridColumnsForCurrentFilter()
        setupPinchToZoom()
        observeViewModel()
        checkPermissionsAndLoadMedia()
        setupSwipeRefresh()
        setupSelectionBar()
        setupBackPress()
    }

    override fun onDestroyView() {
        unregisterMediaStoreObserver()
        rotationLoadJob?.cancel()
        _binding?.fastScrollContainer?.removeCallbacks(fastScrollAutoHideRunnable)
        super.onDestroyView()
        _binding = null
    }

    override fun onStart() {
        super.onStart()
        registerMediaStoreObserver()
    }

    override fun onResume() {
        super.onResume()
        applyGalleryPaletteColors()
        applyFastScrollerAccent()
        viewLifecycleOwner.lifecycleScope.launch {
            refreshProtectionFilterState()
            if (rawMediaList.isNotEmpty()) {
                updateAdapterWithSorting(rawMediaList)
            }
        }
        // Avoid heavy rescans on every page resume; only bootstrap if list is still empty.
        if (selectedAlbumId == null && selectedMediaIds == null && hasMediaReadPermission() && rawMediaList.isEmpty()) {
            loadInitialContent(ignoreAutoScanInterval = false)
        }
    }

    override fun onStop() {
        unregisterMediaStoreObserver()
        super.onStop()
    }

    // ── ViewModel ─────────────────────────────────────────────────────────────

    private fun initViewModel() {
        val database = GalerioDatabase.getInstance(requireContext())
        val mediaRepository = MediaRepositoryImpl(database.mediaDao())
        val albumRepository = com.example.galerinio.data.repository.AlbumRepositoryImpl(database.albumDao())
        trashRepository = TrashRepositoryImpl(
            database.trashDao(),
            requireContext().filesDir.resolve("trash_media"),
            requireContext().applicationContext
        )
        val factory = ViewModelFactory(mediaRepository, albumRepository)
        viewModel = ViewModelProvider(this, factory)[GalleryViewModel::class.java]
    }

    fun onMainPageSelected() {
        if (_binding == null) return
        android.util.Log.d("GalleryFragment", "onMainPageSelected called - filter: $currentFilter, hasData: ${rawMediaList.size}")
        applyGalleryPaletteColors()
        viewLifecycleOwner.lifecycleScope.launch {
            refreshProtectionFilterState()
            // Always re-apply protected media rules before drawing list.
            updateAdapterWithSorting(rawMediaList)
            forceRecyclerRedraw()
        }
        // Do not rescan storage on every tab return; this blocks UI and breaks smooth page swipes.
        if (selectedAlbumId == null && hasMediaReadPermission() && rawMediaList.isEmpty()) {
            loadInitialContent(ignoreAutoScanInterval = false)
        }
    }

    private fun forceRecyclerRedraw() {
        binding.recyclerView.post {
            if (_binding == null) return@post
            binding.recyclerView.visibility = View.VISIBLE
            binding.recyclerView.requestLayout()
            binding.recyclerView.invalidate()
        }
    }

    private fun scrollToTop() {
        val layoutManager = binding.recyclerView.layoutManager as? GridLayoutManager ?: return
        binding.recyclerView.stopScroll()
        layoutManager.scrollToPositionWithOffset(0, 0)
        binding.recyclerView.post { binding.recyclerView.scrollToPosition(0) }
    }

    private fun refreshAdapterDisplay() {
        android.util.Log.d("GalleryFragment", "refreshAdapterDisplay: data=${rawMediaList.size}, recyclerViewVisible=${binding.recyclerView.visibility}")

        // Убедимся, что RecyclerView виден и данные есть
        if (rawMediaList.isNotEmpty()) {
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
            binding.errorState.visibility = View.GONE

            // КРИТИЧНО: Повторно отправляем данные в адаптер через submitList
            val sortOptions = currentSortOptions
            if (sortOptions != null) {
                updateAdapterWithSorting(rawMediaList)
                binding.recyclerView.invalidate()
            }
        }

        android.util.Log.d("GalleryFragment", "refreshAdapterDisplay: update requested")
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        // Load sort options
        viewLifecycleOwner.lifecycleScope.launch {
            val category = getCategoryName()
            currentSortOptions = com.example.galerinio.domain.model.SortOptions(
                sortType = com.example.galerinio.domain.model.SortType.valueOf(
                    preferencesManager.getSortType(category)
                ),
                isDescending = preferencesManager.getSortDescending(category),
                groupingType = com.example.galerinio.domain.model.GroupingType.valueOf(
                    preferencesManager.getGroupingType(category)
                )
            )
            setupAdapter()
        }
    }

    private fun setupAdapter() {
        val sortOptions = currentSortOptions ?: return
        val activeSpanCount = getActiveSpanCount()

        if (sortOptions.groupingType == com.example.galerinio.domain.model.GroupingType.NONE) {
            // Use regular adapter
            if (mediaAdapter == null) {
                mediaAdapter = MediaAdapter(
                    onItemClick = { media, thumbnailView, transitionName ->
                        handleMediaClick(media, transitionName)
                    },
                    onSelectionChanged = { selectedIds ->
                        onSelectionChanged(selectedIds)
                    },
                    rotationProvider = { mediaId -> mediaRotations[mediaId] ?: 0 }
                )
            }
            mediaAdapter?.setDisplayMode(
                if (currentViewMode == ViewMode.LIST) MediaAdapter.DisplayMode.LIST
                else MediaAdapter.DisplayMode.GRID
            )
            binding.recyclerView.adapter = mediaAdapter
            binding.recyclerView.layoutManager = GridLayoutManager(context, activeSpanCount)
        } else {
            // Use grouped adapter with span configuration
            if (groupedMediaAdapter == null) {
                groupedMediaAdapter = com.example.galerinio.presentation.adapter.GroupedMediaAdapter(
                    onItemClick = { media, thumbnailView, transitionName ->
                        handleMediaClick(media, transitionName)
                    },
                    onSelectionChanged = { selectedIds ->
                        onSelectionChanged(selectedIds)
                    },
                    rotationProvider = { mediaId -> mediaRotations[mediaId] ?: 0 }
                )
            }
            groupedMediaAdapter?.setDisplayMode(
                if (currentViewMode == ViewMode.LIST) {
                    com.example.galerinio.presentation.adapter.GroupedMediaAdapter.DisplayMode.LIST
                } else {
                    com.example.galerinio.presentation.adapter.GroupedMediaAdapter.DisplayMode.GRID
                }
            )
            val layoutManager = GridLayoutManager(context, activeSpanCount)
            layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return when (groupedMediaAdapter?.currentList?.get(position)) {
                        is com.example.galerinio.domain.model.MediaListItem.Header -> layoutManager.spanCount
                        else -> 1
                    }
                }
            }
            binding.recyclerView.adapter = groupedMediaAdapter
            binding.recyclerView.layoutManager = layoutManager
        }
    }

    private fun openPhotoView(media: com.example.galerinio.domain.model.MediaModel, transitionName: String) {
        pendingReturnMediaId = media.id
        pendingReturnTransitionName = transitionName

        if (!media.isVideo) {
            val dm = resources.displayMetrics
            com.bumptech.glide.Glide.with(requireContext())
                .load(media.filePath)
                .fitCenter()
                .preload(dm.widthPixels, dm.heightPixels)
        }

        val currentMediaIds = rawMediaList.map { it.id }.toLongArray()
        val photoViewFragment = PhotoViewFragment.newInstance(
            startMediaId = media.id,
            filter = currentFilter.name,
            albumId = selectedAlbumId,
            mediaIds = currentMediaIds,
            previewPath = media.filePath,
            previewIsVideo = media.isVideo,
            sharedTransitionName = null
        )
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_up_in,
                R.anim.fade_out,
                R.anim.fade_in,
                R.anim.slide_down_out
            )
            .replace(R.id.container, photoViewFragment)
            .addToBackStack(null)
            .commit()
    }

    private fun handleMediaClick(media: MediaModel, transitionName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val requiresUnlock = shouldShowProtectedMediaAsBlur() && media.albumId in protectedAlbumIds
            if (!requiresUnlock) {
                openPhotoView(media, transitionName)
                return@launch
            }
            if (preferencesManager.getFolderLockMethod() == PreferencesManager.LockMethod.NONE) {
                Toast.makeText(requireContext(), getString(R.string.security_select_method_to_activate), Toast.LENGTH_SHORT).show()
                return@launch
            }
            Toast.makeText(requireContext(), getString(R.string.folder_open_requires_unlock), Toast.LENGTH_SHORT).show()
            pendingProtectedMediaToOpen = media
            pendingProtectedTransitionName = transitionName
            unlockProtectedMediaLauncher.launch(
                Intent(requireContext(), UnlockActivity::class.java)
                    .putExtra(UnlockActivity.EXTRA_UNLOCK_SCOPE, UnlockActivity.SCOPE_FOLDER)
            )
        }
    }

    private fun getCategoryName(): String {
        return when {
            selectedAlbumId != null -> "album"
            currentFilter == GalleryFilter.PHOTOS -> "photos"
            currentFilter == GalleryFilter.VIDEOS -> "videos"
            currentFilter == GalleryFilter.FAVORITES -> "favorites"
            else -> "all"
        }
    }

    private fun setupSharedElementReturnHandling() {
        parentFragmentManager.setFragmentResultListener(
            PhotoViewFragment.REQUEST_KEY_PHOTO_RETURN,
            viewLifecycleOwner
        ) { _, bundle ->
            val returnedId = bundle.getLong(PhotoViewFragment.RESULT_KEY_MEDIA_ID, Long.MIN_VALUE)
            if (returnedId != Long.MIN_VALUE) {
                pendingReturnMediaId = returnedId
            }

            postponeEnterTransition()
            pendingReturnMediaId?.let { mediaId ->
                val targetPosition = mediaAdapter?.currentList?.indexOfFirst { it.id == mediaId } ?: -1
                if (targetPosition >= 0) {
                    binding.recyclerView.scrollToPosition(targetPosition)
                }
            }
            binding.recyclerView.doOnPreDraw { startPostponedEnterTransition() }
        }

        setExitSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(
                names: MutableList<String>,
                sharedElements: MutableMap<String, View>
            ) {
                val mediaId = pendingReturnMediaId ?: return
                if (names.isEmpty()) return

                val targetPosition = mediaAdapter?.currentList?.indexOfFirst { it.id == mediaId } ?: -1
                if (targetPosition < 0) return

                val holder = binding.recyclerView.findViewHolderForAdapterPosition(targetPosition)
                val targetView = holder?.itemView?.findViewById<View>(R.id.imageView) ?: return

                val mappedName = pendingReturnTransitionName ?: names.first()
                ViewCompat.setTransitionName(targetView, mappedName)
                sharedElements.clear()
                sharedElements[mappedName] = targetView

                pendingReturnMediaId = null
                pendingReturnTransitionName = null
            }
        })
    }

    private fun onSelectionChanged(selectedIds: Set<Long>) {
        val hasSelection = selectedIds.isNotEmpty()
        selectionBackCallback.isEnabled = hasSelection
        if (hasSelection) {
            binding.selectionBar.visibility = View.VISIBLE
        } else {
            binding.selectionBar.visibility = View.GONE
        }
    }

    // ── Selection bar ─────────────────────────────────────────────────────────

    private fun setupSelectionBar() {
        binding.btnDeleteSelected.setOnClickListener { onDeleteSelected() }
        binding.btnCopySelected.setOnClickListener { onCopySelected() }
        binding.btnMoveSelected.setOnClickListener { onMoveSelected() }
        binding.btnShareSelected.setOnClickListener { onShareSelected() }
        binding.btnFavoriteSelected.setOnClickListener { onFavoriteSelected() }
    }

    private fun exitSelectionMode() {
        clearSelection()
        binding.selectionBar.visibility = View.GONE
    }

    private fun setupBackPress() {
        // selectionBackCallback изначально выключен (enabled=false).
        // Он включается только при наличии выделенных элементов — в onSelectionChanged().
        // Это правильный AndroidX-паттерн: колбэк с enabled=false прозрачен для системы,
        // поэтому back press обрабатывается штатно, когда режим выделения неактивен.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            selectionBackCallback
        )
    }

    // ── Действия над выбранными файлами ───────────────────────────────────────

    private fun onDeleteSelected() {
        val items = getSelectedItems()
        if (items.isEmpty()) return

        // Диалог подтверждения удаления
        val builder = android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_confirm_title))
            .setMessage(getString(R.string.delete_confirm_message, items.size))
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(getString(R.string.delete_confirm_yes)) { _, _ ->
                performDelete(items)
            }
            .setNegativeButton(getString(R.string.cancel), null)
        DialogUi.showWithReadableButtons(builder, requireContext())
    }

    private fun performDelete(items: List<MediaModel>) {
        Log.d(TAG, "performDelete: Starting deletion for ${items.size} items")

        viewLifecycleOwner.lifecycleScope.launch {
            val movedIds = mutableListOf<Long>()
            val needPermissionItems = mutableListOf<MediaModel>()

            // Сначала пытаемся удалить все файлы
            for (media in items) {
                try {
                    Log.d(TAG, "performDelete: Processing file: ${media.fileName}")
                    android.util.Log.e("TRASH_DEBUG", "")
                    android.util.Log.e("TRASH_DEBUG", "========================================")
                    android.util.Log.e("TRASH_DEBUG", "PERFORMDELETE: Processing ${media.fileName}")
                    android.util.Log.e("TRASH_DEBUG", "Media ID: ${media.id}")
                    android.util.Log.e("TRASH_DEBUG", "========================================")
                    val moved = trashRepository.moveToTrash(media)
                    Log.d(TAG, "performDelete: moveToTrash returned: $moved")
                    android.util.Log.e("TRASH_DEBUG", "PERFORMDELETE: moveToTrash returned: $moved")

                    // Проверяем, есть ли pending permission request после каждой попытки
                    val permissionRequest = TrashRepositoryImpl.pendingUserPermissionRequest
                    if (permissionRequest != null) {
                        Log.d(TAG, "performDelete: Found pending permission request for ${permissionRequest.second.fileName}")
                        android.util.Log.e("TRASH_DEBUG", "PERFORMDELETE: Found pending permission request")
                        TrashRepositoryImpl.pendingUserPermissionRequest = null
                        needPermissionItems.add(permissionRequest.second)
                        // НЕ прерываем цикл - продолжаем обрабатывать остальные файлы
                    } else if (moved) {
                        movedIds += media.id
                        Log.d(TAG, "performDelete: Successfully moved to trash: ${media.fileName}")
                        android.util.Log.e("TRASH_DEBUG", "PERFORMDELETE: Added to movedIds, total: ${movedIds.size}")
                    } else {
                        Log.w(TAG, "performDelete: Failed to move to trash: ${media.fileName}")
                        android.util.Log.e("TRASH_DEBUG", "PERFORMDELETE: NOT added to movedIds (moved=false)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "performDelete: moveToTrash failed for ${media.filePath}", e)
                    android.util.Log.e("TRASH_DEBUG", "PERFORMDELETE: Exception - ${e.message}", e)
                }
            }
            
            // Если есть файлы, требующие разрешения, создаём batch request для них
            if (needPermissionItems.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Log.d(TAG, "performDelete: Creating batch delete request for ${needPermissionItems.size} files")
                try {
                    val urisToDelete = needPermissionItems.mapNotNull { media ->
                        val mediaUri = if (media.isVideo) {
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        } else {
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        }
                        
                        // Находим MediaStore ID
                        val mediaStoreId = findMediaStoreId(media, mediaUri)
                        if (mediaStoreId != null) {
                            ContentUris.withAppendedId(mediaUri, mediaStoreId)
                        } else {
                            null
                        }
                    }
                    
                    if (urisToDelete.isNotEmpty()) {
                        val pendingIntent = MediaStore.createDeleteRequest(
                            requireContext().contentResolver,
                            urisToDelete
                        )
                        pendingDeleteForTrash = needPermissionItems.first()
                        deleteRequestLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "performDelete: Failed to create batch delete request", e)
                }
            }
            
            android.util.Log.e("TRASH_DEBUG", "")
            android.util.Log.e("TRASH_DEBUG", "PERFORMDELETE: Final movedIds count: ${movedIds.size}")
            if (movedIds.isNotEmpty()) {
                android.util.Log.e("TRASH_DEBUG", "PERFORMDELETE: Calling viewModel.deleteMultipleMedia with IDs: $movedIds")
                viewModel.deleteMultipleMedia(movedIds)
            } else {
                android.util.Log.e("TRASH_DEBUG", "PERFORMDELETE: NOT calling viewModel.deleteMultipleMedia (no IDs)")
            }

            val failedCount = items.size - movedIds.size
            val message = if (failedCount == 0) {
                getString(R.string.files_deleted, movedIds.size)
            } else {
                getString(R.string.trash_partial_move, movedIds.size, failedCount)
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            exitSelectionMode()
        }
    }
    
    // Вспомогательная функция для поиска MediaStore ID
    private suspend fun findMediaStoreId(media: MediaModel, mediaUri: android.net.Uri): Long? {
        return withContext(Dispatchers.IO) {
            try {
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.DISPLAY_NAME
                )
                
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(File(media.filePath).name)
                
                requireContext().contentResolver.query(
                    mediaUri,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        if (dataColumn >= 0) {
                            val dataPath = cursor.getString(dataColumn)
                            if (dataPath == media.filePath) {
                                return@withContext id
                            }
                        } else {
                            return@withContext id
                        }
                    }
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "findMediaStoreId: Error", e)
                null
            }
        }
    }

    private fun onShareSelected() {
        val items = getSelectedItems()
        if (items.isEmpty()) return

        val uris = ArrayList<Uri>(items.mapNotNull { media ->
            try {
                FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    File(media.filePath)
                )
            } catch (_: Exception) { null }
        })

        if (uris.isEmpty()) return

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = items.first().mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
        exitSelectionMode()
    }

    private fun onFavoriteSelected() {
        val items = getSelectedItems()
        if (items.isEmpty()) return
        // Если хотя бы один не в избранном — добавляем все
        val targetState = items.any { !it.isFavorite }
        viewModel.setFavoriteMultiple(items.map { it.id }, targetState)
        val msg = if (targetState) getString(R.string.added_to_favorites)
                  else getString(R.string.removed_from_favorites)
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        exitSelectionMode()
    }

    private fun onCopySelected() {
        val items = getSelectedItems()
        if (items.isEmpty()) return
        pendingFileOperation = PendingFileOperation.COPY
        pendingOperationItems = items
        folderPickerLauncher.launch(null)
    }

    private fun onMoveSelected() {
        val items = getSelectedItems()
        if (items.isEmpty()) return
        pendingFileOperation = PendingFileOperation.MOVE
        pendingOperationItems = items
        folderPickerLauncher.launch(null)
    }

    private fun performCopy(destTreeUri: Uri, items: List<MediaModel>) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            items.forEach { media ->
                try {
                    val treeDocId = DocumentsContract.getTreeDocumentId(destTreeUri)
                    val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(destTreeUri, treeDocId)
                    val newFileUri = DocumentsContract.createDocument(
                        requireContext().contentResolver, treeDocUri, media.mimeType, media.fileName
                    )
                    if (newFileUri != null) {
                        requireContext().contentResolver.openOutputStream(newFileUri)?.use { out ->
                            FileInputStream(File(media.filePath)).use { it.copyTo(out) }
                        }
                        successCount++
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            withContext(Dispatchers.Main) {
                val msg = if (successCount == items.size)
                    getString(R.string.files_copied, successCount)
                else
                    getString(R.string.copy_error)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                exitSelectionMode()
            }
        }
    }

    private fun performMove(destTreeUri: Uri, items: List<MediaModel>) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            val movedIds = mutableListOf<Long>()
            items.forEach { media ->
                try {
                    val treeDocId = DocumentsContract.getTreeDocumentId(destTreeUri)
                    val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(destTreeUri, treeDocId)
                    val newFileUri = DocumentsContract.createDocument(
                        requireContext().contentResolver, treeDocUri, media.mimeType, media.fileName
                    )
                    if (newFileUri != null) {
                        requireContext().contentResolver.openOutputStream(newFileUri)?.use { out ->
                            FileInputStream(File(media.filePath)).use { it.copyTo(out) }
                        }
                        // Удаляем оригинал
                        requireContext().contentResolver.delete(
                            ContentUris.withAppendedId(
                                if (media.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                mediaStoreId(media.id)
                            ), null, null
                        )
                        movedIds.add(media.id)
                        successCount++
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            withContext(Dispatchers.Main) {
                if (movedIds.isNotEmpty()) viewModel.deleteMultipleMedia(movedIds)
                val msg = if (successCount == items.size)
                    getString(R.string.files_moved, successCount)
                else
                    getString(R.string.move_error)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                exitSelectionMode()
            }
        }
    }

    // ── Observe ───────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { uiState ->
                    when (uiState) {
                        is UiState.Loading -> {
                            binding.swipeRefresh.isRefreshing = manualRefreshInProgress
                            binding.recyclerView.visibility = View.VISIBLE
                            binding.emptyState.visibility = View.GONE
                            binding.errorState.visibility = View.GONE
                        }
                        is UiState.Success -> {
                            manualRefreshInProgress = false
                            binding.swipeRefresh.isRefreshing = false
                            binding.recyclerView.visibility = View.VISIBLE
                            binding.emptyState.visibility = View.GONE
                            binding.errorState.visibility = View.GONE

                            rawMediaList = uiState.data
                            refreshProtectionFilterState()
                            updateAdapterWithSorting(uiState.data)
                            forceRecyclerRedraw()
                        }
                        is UiState.Empty -> {
                            manualRefreshInProgress = false
                            binding.swipeRefresh.isRefreshing = false
                            binding.recyclerView.visibility = View.GONE
                            binding.emptyState.visibility = View.VISIBLE
                            binding.errorState.visibility = View.GONE
                            rawMediaList = emptyList()
                            fastScrollMediaList = emptyList()
                            fastScrollAdapterPositions = IntArray(0)
                            updateFastScrollerVisibility()
                            mediaAdapter?.submitList(emptyList())
                            groupedMediaAdapter?.submitList(emptyList())
                        }
                        is UiState.Error -> {
                            manualRefreshInProgress = false
                            binding.swipeRefresh.isRefreshing = false
                            binding.recyclerView.visibility = View.GONE
                            binding.emptyState.visibility = View.GONE
                            binding.errorState.visibility = View.VISIBLE
                            fastScrollMediaList = emptyList()
                            fastScrollAdapterPositions = IntArray(0)
                            updateFastScrollerVisibility()
                        }
                    }
                }
            }
        }
    }

    private fun updateAdapterWithSorting(mediaList: List<com.example.galerinio.domain.model.MediaModel>) {
        val sortOptions = currentSortOptions ?: return
        val showProtectedAsBlur = shouldShowProtectedMediaAsBlur()
        mediaAdapter?.setProtectedMediaRendering(showProtectedAsBlur, protectedAlbumIds)
        groupedMediaAdapter?.setProtectedMediaRendering(showProtectedAsBlur, protectedAlbumIds)
        val safeMedia = applyProtectedMediaFilter(mediaList)
        preloadMediaRotations(safeMedia)

        if (sortOptions.groupingType == com.example.galerinio.domain.model.GroupingType.NONE) {
            // Use regular adapter with sorting
            val sorted = com.example.galerinio.data.util.MediaSorter.sortAndGroupMedia(safeMedia, sortOptions)
            val mediaOnly = sorted.filterIsInstance<com.example.galerinio.domain.model.MediaListItem.MediaItem>()
                .map { it.media }
            mediaAdapter?.submitList(mediaOnly)
            fastScrollMediaList = mediaOnly
            fastScrollAdapterPositions = IntArray(mediaOnly.size) { it }
        } else {
            // Use grouped adapter
            val grouped = com.example.galerinio.data.util.MediaSorter.sortAndGroupMedia(safeMedia, sortOptions)
            groupedMediaAdapter?.submitList(grouped)
            val mediaItems = mutableListOf<com.example.galerinio.domain.model.MediaModel>()
            val positions = mutableListOf<Int>()
            grouped.forEachIndexed { index, item ->
                if (item is com.example.galerinio.domain.model.MediaListItem.MediaItem) {
                    mediaItems.add(item.media)
                    positions.add(index)
                }
            }
            fastScrollMediaList = mediaItems
            fastScrollAdapterPositions = positions.toIntArray()
        }
        updateFastScrollerVisibility()
    }

    private fun preloadMediaRotations(mediaList: List<MediaModel>) {
        val photoIds = mediaList.asSequence()
            .filterNot { it.isVideo }
            .map { it.id }
            .toList()

        latestRotationRequestIds = photoIds
        rotationLoadJob?.cancel()

        if (photoIds.isEmpty()) {
            mediaRotations = emptyMap()
            mediaAdapter?.notifyDataSetChanged()
            groupedMediaAdapter?.notifyDataSetChanged()
            return
        }

        rotationLoadJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val persisted = runCatching { preferencesManager.getMediaRotations(photoIds) }
                .getOrDefault(emptyMap())
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                if (latestRotationRequestIds != photoIds) return@withContext
                mediaRotations = persisted
                mediaAdapter?.notifyDataSetChanged()
                groupedMediaAdapter?.notifyDataSetChanged()
            }
        }
    }

    private suspend fun refreshProtectionFilterState() {
        folderProtectionEnabled = preferencesManager.isFolderProtectionEnabled()
        protectedMediaDisplayMode = preferencesManager.getProtectedMediaDisplayMode()
        protectedAlbumIds = if (folderProtectionEnabled) {
            preferencesManager.getProtectedAlbumIds()
        } else {
            emptySet()
        }
    }

    private fun shouldShowProtectedMediaAsBlur(): Boolean {
        val onGeneralCategories = selectedAlbumId == null && selectedMediaIds == null
        return folderProtectionEnabled &&
            protectedMediaDisplayMode == PreferencesManager.ProtectedMediaDisplayMode.BLUR &&
            onGeneralCategories
    }

    private fun applyProtectedMediaFilter(mediaList: List<com.example.galerinio.domain.model.MediaModel>): List<com.example.galerinio.domain.model.MediaModel> {
        val shouldFilter = folderProtectionEnabled &&
            protectedMediaDisplayMode == PreferencesManager.ProtectedMediaDisplayMode.HIDE &&
            protectedAlbumIds.isNotEmpty() &&
            selectedAlbumId == null &&
            selectedMediaIds == null
        if (!shouldFilter) return mediaList
        return mediaList.filterNot { it.albumId in protectedAlbumIds }
    }

    private fun setupFastScroller() {
        applyFastScrollerAccent()
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (!canUseFastScroller()) return
                if (newState == RecyclerView.SCROLL_STATE_IDLE && !isFastScrollDragging) {
                    applyFastScrollBubbleMode(FastScrollBubbleMode.HIDDEN)
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
                        // During regular swipe scrolling we only sync thumb position,
                        // date bubble is reserved for explicit drag/hold on the thumb.
                        syncFastScrollThumbWithRecycler()
                    }
                }
            }
        })

        binding.fastScrollContainer.setOnTouchListener { _, event ->
            if (fastScrollMediaList.isEmpty()) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isTouchOnFastScrollThumb(event.x, event.y)) return@setOnTouchListener false
                    isFastScrollDragging = true
                    binding.recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
                    showFastScroller()
                    applyFastScrollBubbleMode(FastScrollBubbleMode.DRAG)
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
                    applyFastScrollBubbleMode(FastScrollBubbleMode.HIDDEN)
                    scheduleFastScrollerHide()
                    true
                }
                else -> false
            }
        }
    }

    private fun applyFastScrollerAccent() {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val isDarkMode = preferencesManager.isDarkModeEnabled()
            val accent = preferencesManager.getThemeAccent(isDarkMode)
            val accentColors = ThemeManager.resolveAccentColors(isDarkMode, accent)
            val accentColor = accentColors.accent
            binding.fastScrollThumb.backgroundTintList = ColorStateList.valueOf(accentColor)
            binding.fastScrollTrack.backgroundTintList =
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accentColor, 96))
            binding.fastScrollDateBubble.backgroundTintList =
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accentColor, 224))
            binding.fastScrollDateBubble.setTextColor(accentColors.onAccent)
        }
    }

    private fun applyGalleryPaletteColors() {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val isDarkMode = preferencesManager.isDarkModeEnabled()
            val palette = preferencesManager.getThemePalette(isDarkMode)
            val accent = preferencesManager.getThemeAccent(isDarkMode)
            val colors = ThemeManager.resolvePaletteColors(isDarkMode, palette)
            val accentColor = ThemeManager.resolveAccentColors(isDarkMode, accent).accent

            binding.root.setBackgroundColor(colors.background)
            binding.selectionBar.setBackgroundColor(colors.surface)
            binding.swipeRefresh.setProgressBackgroundColorSchemeColor(colors.surface)
            binding.swipeRefresh.setColorSchemeColors(accentColor)
        }
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

        val trackTop = track.top.toFloat()
        val trackBottom = track.bottom.toFloat()
        val thumbHalf = thumb.height / 2f
        val minCenter = trackTop + thumbHalf
        val maxCenter = trackBottom - thumbHalf
        val clampedCenter = yInContainer.coerceIn(minCenter, maxCenter)

        thumb.y = clampedCenter - thumbHalf

        val usableHeight = (maxCenter - minCenter).coerceAtLeast(1f)
        val fraction = ((clampedCenter - minCenter) / usableHeight).coerceIn(0f, 1f)
        val mediaIndex = (fraction * (fastScrollMediaList.size - 1)).roundToInt().coerceIn(0, fastScrollMediaList.lastIndex)
        val adapterPosition = fastScrollAdapterPositions.getOrElse(mediaIndex) { mediaIndex }

        val lm = binding.recyclerView.layoutManager as? GridLayoutManager
        if (lm != null) {
            lm.scrollToPositionWithOffset(adapterPosition, 0)
        } else {
            binding.recyclerView.scrollToPosition(adapterPosition)
        }

        val media = fastScrollMediaList[mediaIndex]
        binding.fastScrollDateBubble.text = formatFastScrollBubbleDate(media.dateAdded)
        applyFastScrollBubbleMode(FastScrollBubbleMode.DRAG, clampedCenter)
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

    private fun formatFastScrollBubbleDate(timestamp: Long): String {
        val date = Date(timestamp)
        val month = fastScrollMonthFormat.format(date).trim().trimEnd('.')
        val year = fastScrollYearFormat.format(date)
        return if (month.isEmpty()) year else "$month.$year"
    }

    private fun canUseFastScroller(): Boolean = fastScrollMediaList.size > 24

    private fun showFastScroller() {
        if (!canUseFastScroller()) {
            binding.fastScrollContainer.visibility = View.GONE
            applyFastScrollBubbleMode(FastScrollBubbleMode.HIDDEN)
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

    private fun applyFastScrollBubbleMode(mode: FastScrollBubbleMode, centerYInContainer: Float? = null) {
        fastScrollBubbleMode = mode
        val bubble = binding.fastScrollDateBubble
        when (mode) {
            FastScrollBubbleMode.HIDDEN -> {
                bubble.visibility = View.GONE
            }
            FastScrollBubbleMode.DRAG -> {
                val centerY = centerYInContainer ?: return
                bubble.visibility = View.VISIBLE
                positionFastScrollBubble(centerY, attachToThumb = false)
            }
        }
    }


    private fun positionFastScrollBubble(centerYInContainer: Float, attachToThumb: Boolean) {
        val bubble = binding.fastScrollDateBubble
        bubble.post {
            val desiredX = if (attachToThumb) {
                binding.fastScrollContainer.x + (binding.fastScrollContainer.width - bubble.width) / 2f
            } else {
                // Keep date bubble left from the thumb so finger does not cover the date while dragging.
                val bubbleGapPx = (12f * resources.displayMetrics.density)
                binding.fastScrollContainer.x - bubble.width - bubbleGapPx
            }
            val minX = 8f
            val maxX = (binding.root.width - bubble.width - 8).toFloat().coerceAtLeast(minX)
            bubble.x = desiredX.coerceIn(minX, maxX)

            val desiredY = binding.fastScrollContainer.y + centerYInContainer - bubble.height / 2f
            val minY = 8f
            val maxY = (binding.root.height - bubble.height - 8).toFloat().coerceAtLeast(minY)
            bubble.y = desiredY.coerceIn(minY, maxY)
        }
    }

    private fun updateFastScrollerVisibility() {
        val enabled = canUseFastScroller()
        if (!enabled) {
            binding.fastScrollContainer.removeCallbacks(fastScrollAutoHideRunnable)
            binding.fastScrollContainer.visibility = View.GONE
            applyFastScrollBubbleMode(FastScrollBubbleMode.HIDDEN)
        } else {
            binding.fastScrollContainer.visibility = View.GONE
            binding.fastScrollContainer.post {
                if (_binding == null || isFastScrollDragging) return@post
                syncFastScrollThumbWithRecycler()
            }
            if (!isFastScrollDragging) {
                scheduleFastScrollerHide()
            }
        }
    }

    // ── Permissions & scan ────────────────────────────────────────────────────

    private fun checkPermissionsAndLoadMedia() {
        val permissions = getMediaPermissionRequestList()

        if (hasMediaReadPermission()) {
            if (selectedMediaIds != null) {
                applyCurrentFilter()
            } else {
                loadInitialContent()
            }
        } else {
            permissionLauncher.launch(permissions)
        }
    }


    private fun loadInitialContent(
        forceRescan: Boolean = false,
        ignoreAutoScanInterval: Boolean = false,
        showRefreshIndicator: Boolean = false
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (showRefreshIndicator) {
                    manualRefreshInProgress = true
                    _binding?.swipeRefresh?.isRefreshing = true
                }
                val database = GalerioDatabase.getInstance(requireContext())
                val hasCachedMedia = database.mediaDao().getMediaCount().first() > 0
                val scannerSchemaVersion = preferencesManager.getScannerSchemaVersion()
                val schemaOutdated = scannerSchemaVersion < MediaScanner.SCANNER_SCHEMA_VERSION
                if (!hasCachedMedia) {
                    loadMediaToDatabase(fullScan = true)
                    return@launch
                }

                if (schemaOutdated) {
                    loadMediaToDatabase(fullScan = true)
                    return@launch
                }

                val lastScanAt = preferencesManager.lastLibraryScanAtFlow.first()
                val shouldSkipScan = !forceRescan && !ignoreAutoScanInterval && lastScanAt > 0L &&
                    System.currentTimeMillis() - lastScanAt < AUTO_SCAN_INTERVAL_MS

                if (shouldSkipScan) {
                    applyCurrentFilter()
                } else {
                    loadMediaToDatabase(fullScan = forceRescan, lastScanAt = lastScanAt)
                }
            } catch (_: Exception) {
                manualRefreshInProgress = false
                _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    private fun loadMediaToDatabase(fullScan: Boolean, lastScanAt: Long = 0L) {
        if (syncJob?.isActive == true) {
            if (fullScan) pendingObserverFullRescan = true
            if (!manualRefreshInProgress) {
                _binding?.swipeRefresh?.isRefreshing = false
            }
            return
        }
        syncJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val database = GalerioDatabase.getInstance(requireContext())
                val favoriteIds = database.mediaDao().getFavoriteIds().toSet()
                val currentMediaIds = mediaScanner.getCurrentMediaIds()
                val existingIds = database.mediaDao().getAllIds().toSet()

                val shouldFallbackToFullScan = !fullScan && (currentMediaIds - existingIds).isNotEmpty()
                val mediaList = mediaScanner.scanMediaFiles(
                    sinceMs = if (fullScan || shouldFallbackToFullScan) null else lastScanAt
                )
                val scannedAlbums = mediaScanner.scanAlbums()
                Log.d(
                    TAG,
                    "sync: fullScan=$fullScan fallbackFull=$shouldFallbackToFullScan media=${mediaList.size} currentIds=${currentMediaIds.size} existingIds=${existingIds.size} albums=${scannedAlbums.size}"
                )

                database.withTransaction {
                    if (mediaList.isNotEmpty()) {
                        database.mediaDao().insertAllMedia(mediaList.map { m ->
                            com.example.galerinio.data.local.entity.MediaEntity(
                                id = m.id, fileName = m.fileName, filePath = m.filePath,
                                mimeType = m.mimeType, size = m.size, dateModified = m.dateModified,
                                dateAdded = m.dateAdded, width = m.width, height = m.height,
                                duration = m.duration, albumId = m.albumId,
                                isFavorite = m.id in favoriteIds
                            )
                        })
                    }
                    val staleIds = database.mediaDao().getAllIds().toSet() - currentMediaIds
                    Log.d(TAG, "sync: staleIds=${staleIds.size}")
                    if (staleIds.isNotEmpty()) database.mediaDao().deleteMediaByIds(staleIds.toList())

                    database.albumDao().clearAlbums()
                    if (scannedAlbums.isNotEmpty()) {
                        database.albumDao().insertAllAlbums(scannedAlbums.map { a ->
                            com.example.galerinio.data.local.entity.AlbumEntity(
                                id = a.id, name = a.name, path = a.path,
                                coverMediaId = a.coverMediaId, mediaCount = a.mediaCount, dateAdded = a.dateAdded
                            )
                        })
                    }
                }
                preferencesManager.setLastLibraryScanAt(System.currentTimeMillis())
                preferencesManager.setScannerSchemaVersion(MediaScanner.SCANNER_SCHEMA_VERSION)
                applyCurrentFilter()
            } catch (_: Exception) {
                applyCurrentFilter()
            } finally {
                if (!manualRefreshInProgress) {
                    _binding?.swipeRefresh?.isRefreshing = false
                }
                if (pendingObserverFullRescan && _binding != null) {
                    pendingObserverFullRescan = false
                    loadMediaToDatabase(fullScan = true)
                }
            }
        }
    }

    private fun applyCurrentFilter() {
        val path = selectedAlbumPath
        val id = selectedAlbumId
        val ids = selectedMediaIds
        when {
            ids != null -> viewModel.loadMediaByIds(ids)
            // Android < Q: filePath is an absolute path → path-based query is reliable
            path != null && path.startsWith("/") -> viewModel.loadMediaByFolderPath(path)
            // Android >= Q or no path: fall back to albumId-based query
            id != null -> viewModel.loadMediaByAlbum(id)
            else -> when (currentFilter) {
                GalleryFilter.ALL -> viewModel.loadAllMedia()
                GalleryFilter.PHOTOS -> viewModel.loadImages()
                GalleryFilter.VIDEOS -> viewModel.loadVideos()
                GalleryFilter.FAVORITES -> viewModel.loadFavorites()
            }
        }
    }

    private fun updateTopBarTitleForCurrentScreen() {
        val title = customScreenTitle ?: selectedAlbumName ?: return
        (activity as? MainActivity)?.setTopBarTitle(title)
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            manualRefreshInProgress = true
            binding.swipeRefresh.isRefreshing = true
            loadInitialContent(forceRescan = true, showRefreshIndicator = true)
        }
    }

    private fun registerMediaStoreObserver() {
        if (mediaStoreObserver != null) return
        mediaStoreObserver = MediaStoreObserver(
            contentResolver = requireContext().contentResolver,
            observedUris = listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Files.getContentUri("external")
            )
        ) {
            observerDebounceJob?.cancel()
            observerDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(OBSERVER_DEBOUNCE_MS)
                if (!hasMediaReadPermission()) return@launch
                loadInitialContent(forceRescan = true)
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasImages = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            val hasVideos = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            val hasSelectedVisual = if (Build.VERSION.SDK_INT >= 34) {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
            hasImages || hasVideos || hasSelectedVisual
        } else {
            // Android 9 и ниже - проверяем оба разрешения
            val hasRead = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            val hasWrite = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            hasRead && hasWrite
        }
    }

    private fun getMediaPermissionRequestList(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 34) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun restoreGridColumnsForCurrentFilter() {
        viewLifecycleOwner.lifecycleScope.launch {
            val albumId = selectedAlbumId
            val restored = if (albumId != null) {
                preferencesManager.getAlbumContentGridColumns(albumId)
            } else {
                preferencesManager.getGalleryGridColumns(currentFilter.name)
            }
                .coerceIn(MIN_SPAN_COUNT, MAX_SPAN_COUNT)
            spanCount = restored
            applyViewModeToLayoutManager()
        }
    }


    private fun persistGridColumnsForCurrentFilter() {
        viewLifecycleOwner.lifecycleScope.launch {
            val albumId = selectedAlbumId
            if (albumId != null) {
                preferencesManager.setAlbumContentGridColumns(albumId, spanCount)
            } else {
                preferencesManager.setGalleryGridColumns(currentFilter.name, spanCount)
            }
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

                    // Игнорируем микродвижения, чтобы убрать дрожание.
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
                        persistGridColumnsForCurrentFilter()
                        gridColumnsChangedByPinch = false
                    }
                }
            }
        )

        binding.recyclerView.setOnTouchListener { _, event ->
            val inPinch = currentViewMode == ViewMode.GRID && (event.pointerCount >= 2 || isPinchActive)
            if (inPinch) {
                detector.onTouchEvent(event)
            }
            inPinch
        }
    }

    private fun mediaStoreId(encodedId: Long): Long = kotlin.math.abs(encodedId)

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

    // ── Adapter helper methods ────────────────────────────────────────────────

    private fun clearSelection() {
        mediaAdapter?.clearSelection()
        groupedMediaAdapter?.clearSelection()
    }

    private fun getSelectedItems(): List<com.example.galerinio.domain.model.MediaModel> {
        val selectedIds = when (binding.recyclerView.adapter) {
            groupedMediaAdapter -> groupedMediaAdapter?.getSelectedIds().orEmpty()
            mediaAdapter -> mediaAdapter?.getSelectedIds().orEmpty()
            else -> emptySet()
        }
        return rawMediaList.filter { it.id in selectedIds }
    }

    // ── Sort and Grouping ─────────────────────────────────────────────────────
    
    fun applySortOptions(options: com.example.galerinio.domain.model.SortOptions) {
        currentSortOptions = options
        setupAdapter()
        updateAdapterWithSorting(rawMediaList)
    }

    fun setViewMode(mode: ViewMode) {
        if (currentViewMode == mode) return
        currentViewMode = mode
        if (_binding == null) return
        mediaAdapter?.setDisplayMode(
            if (mode == ViewMode.LIST) MediaAdapter.DisplayMode.LIST else MediaAdapter.DisplayMode.GRID
        )
        groupedMediaAdapter?.setDisplayMode(
            if (mode == ViewMode.LIST) {
                com.example.galerinio.presentation.adapter.GroupedMediaAdapter.DisplayMode.LIST
            } else {
                com.example.galerinio.presentation.adapter.GroupedMediaAdapter.DisplayMode.GRID
            }
        )
        applyViewModeToLayoutManager()
        binding.recyclerView.requestLayout()
    }

    private fun getActiveSpanCount(): Int = if (currentViewMode == ViewMode.LIST) 1 else spanCount

    private fun applyViewModeToLayoutManager() {
        val layoutManager = binding.recyclerView.layoutManager as? GridLayoutManager ?: return
        layoutManager.spanCount = getActiveSpanCount()
        layoutManager.spanSizeLookup.invalidateSpanIndexCache()
    }
}
