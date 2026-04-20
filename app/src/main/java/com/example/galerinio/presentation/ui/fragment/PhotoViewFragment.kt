package com.example.galerinio.presentation.ui.fragment

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.LayoutInflater

import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.transition.TransitionInflater
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import com.example.galerinio.R
import com.example.galerinio.data.local.GalerioDatabase
import com.example.galerinio.data.repository.MediaRepositoryImpl
import com.example.galerinio.data.repository.TrashRepositoryImpl
import com.example.galerinio.data.util.PreferencesManager
import com.example.galerinio.databinding.FragmentPhotoViewBinding
import com.example.galerinio.presentation.adapter.PhotoPagerAdapter
import com.example.galerinio.presentation.ui.editor.PhotoEditorActivity
import com.example.galerinio.presentation.ui.util.DialogUi
import com.example.galerinio.presentation.viewmodel.GalleryViewModel
import com.example.galerinio.presentation.viewmodel.PhotoRotationViewModel
import com.example.galerinio.presentation.viewmodel.UiState
import com.example.galerinio.presentation.viewmodel.ViewModelFactory
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

@UnstableApi
class PhotoViewFragment : Fragment() {

    private enum class PendingFileOperation { COPY, MOVE }

    private var _binding: FragmentPhotoViewBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: GalleryViewModel
    private lateinit var videoPlaybackViewModel: com.example.galerinio.presentation.viewmodel.VideoPlaybackViewModel
    private lateinit var photoRotationViewModel: PhotoRotationViewModel
    private lateinit var trashRepository: TrashRepositoryImpl
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var photoPagerAdapter: PhotoPagerAdapter
    private var startMediaId: Long = -1L
    private var initialPosition: Int = 0
    private var sourceFilter: String = GalleryFragment.GalleryFilter.ALL.name
    private var sourceAlbumId: Long? = null
    private var sourceMediaIds: List<Long> = emptyList()
    private var previewPath: String = ""
    private var previewIsVideo: Boolean = false
    private var sharedTransitionName: String = ""
    private var skipOpeningPreviewOnRecreate: Boolean = false
    private var isSharedTransitionStarted = false
    private var isOpeningPreviewHidden = false
    private var lastBoundMediaSignature: List<Long> = emptyList()
    private var currentMediaList: List<com.example.galerinio.domain.model.MediaModel> = emptyList()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val previewFallbackHideRunnable = Runnable { hideOpeningPreview() }
    private var isViewerChromeVisible = true
    private var isCurrentPrimaryPageVideo = false
    private var isKeepingScreenOnForVideo = false
    private var pendingFileOperation: PendingFileOperation = PendingFileOperation.COPY
    private var pendingOperationMedia: com.example.galerinio.domain.model.MediaModel? = null
    // Убрано: restoredPlaybackState и pendingPlaybackStateForSave
    // Теперь используем VideoPlaybackViewModel
    private var pendingAdjacentNavTarget: Int? = null
    private var lastAdjacentNavUptimeMs: Long = 0L
    private var pendingPositionAfterDelete: Int? = null
    private var baseViewerContainerPaddingTop: Int = 0
    private var baseViewerContainerPaddingBottom: Int = 0

    // Для запроса разрешения на удаление файлов на Android 11+
    private var pendingDeleteMedia: com.example.galerinio.domain.model.MediaModel? = null
    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val media = pendingDeleteMedia
            if (media != null) {
                lifecycleScope.launch {
                    // После подтверждения разрешения нужно завершить удаление оригинала.
                    val deleted = try {
                        trashRepository.completePendingDeleteAfterPermission(media)
                    } catch (e: Exception) {
                        android.util.Log.e("PhotoViewFragment", "deleteRequestLauncher: finalize delete failed", e)
                        false
                    }

                    if (deleted) {
                        prepareNextPositionAfterDelete(media)
                        viewModel.deleteMedia(media.id)
                        Toast.makeText(requireContext(), getString(R.string.moved_to_trash), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.failed_move_to_trash), Toast.LENGTH_SHORT).show()
                    }
                    pendingDeleteMedia = null
                }
            }
        } else {
            Toast.makeText(requireContext(), getString(R.string.failed_move_to_trash), Toast.LENGTH_SHORT).show()
            pendingDeleteMedia = null
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val media = pendingOperationMedia
        pendingOperationMedia = null
        if (uri == null || media == null) return@registerForActivityResult

        when (pendingFileOperation) {
            PendingFileOperation.COPY -> performCopy(uri, media)
            PendingFileOperation.MOVE -> performMove(uri, media)
        }
    }

    private val photoEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val savedUri = result.data?.getStringExtra(PhotoEditorActivity.EXTRA_OUTPUT_URI)
            if (!savedUri.isNullOrBlank()) {
                Toast.makeText(requireContext(), getString(R.string.photo_editor_saved), Toast.LENGTH_SHORT).show()
            }
            loadSourceMedia()
        }
    }

    // ── Enter-transition completion gate ──────────────────────────────────────
    // The openingPreview IS the shared element.  If we start a ViewPropertyAnimator
    // (alpha fade) on it while the shared-element transition is still running, the
    // two animators conflict → one-frame blink.  We gate the fade behind this flag.
    private var isEnterTransitionDone = false
    private var pendingPreviewHide = false

    private val enterTransitionListener = object : androidx.transition.Transition.TransitionListener {
        override fun onTransitionStart(t: androidx.transition.Transition) {}
        override fun onTransitionEnd(t: androidx.transition.Transition) { markEnterTransitionDone() }
        override fun onTransitionCancel(t: androidx.transition.Transition) { markEnterTransitionDone() }
        override fun onTransitionPause(t: androidx.transition.Transition) {}
        override fun onTransitionResume(t: androidx.transition.Transition) {}
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            pendingAdjacentNavTarget = null
             if (::photoPagerAdapter.isInitialized) {
                 photoPagerAdapter.onPrimaryItemChanged(position)
             }
             val currentMedia = currentMediaList.getOrNull(position)
             if (currentMedia?.isVideo != true) {
                 updateViewerChrome(visible = false, isVideoPage = false)
             }
             if (!isOpeningPreviewHidden && position != initialPosition) {
                 // User swiped away before transition ended – force-complete the gate
                 markEnterTransitionDone()
                 hideOpeningPreview()
             }
         }
     }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализируем VideoPlaybackViewModel - он переживет ротацию экрана
        videoPlaybackViewModel = ViewModelProvider(this)[com.example.galerinio.presentation.viewmodel.VideoPlaybackViewModel::class.java]
        photoRotationViewModel = ViewModelProvider(this)[PhotoRotationViewModel::class.java]

        arguments?.let {
            startMediaId = it.getLong(ARG_START_MEDIA_ID, -1L)
            sourceFilter = it.getString(ARG_FILTER, GalleryFragment.GalleryFilter.ALL.name)
                ?: GalleryFragment.GalleryFilter.ALL.name
            val albumId = it.getLong(ARG_ALBUM_ID, -1L)
            sourceAlbumId = if (albumId > 0L) albumId else null
            sourceMediaIds = it.getLongArray(ARG_MEDIA_IDS)?.toList().orEmpty()
            previewPath = it.getString(ARG_PREVIEW_PATH).orEmpty()
            previewIsVideo = it.getBoolean(ARG_PREVIEW_IS_VIDEO, false)
            sharedTransitionName = it.getString(ARG_SHARED_TRANSITION_NAME).orEmpty()
        }

        val restoredMediaIdFromState = savedInstanceState?.getLong(STATE_KEY_CURRENT_MEDIA_ID, Long.MIN_VALUE)
            ?: Long.MIN_VALUE
        if (restoredMediaIdFromState != Long.MIN_VALUE) {
            startMediaId = restoredMediaIdFromState
            // On recreation, old previewPath may point to another item and causes one-frame blink.
            skipOpeningPreviewOnRecreate = true
            previewPath = ""
            sharedTransitionName = ""
        }

        // Use ViewModel playback fallback only when no explicit start id is provided.
        if (restoredMediaIdFromState == Long.MIN_VALUE && startMediaId <= 0L) {
            videoPlaybackViewModel.savedPlaybackState?.let { restored ->
                startMediaId = restored.mediaId
            }
        }

        if (sharedTransitionName.isNotBlank()) {
            val enterTr = TransitionInflater.from(requireContext())
                .inflateTransition(R.transition.media_shared_image)
            enterTr.addListener(enterTransitionListener)
            sharedElementEnterTransition = enterTr
            sharedElementReturnTransition = TransitionInflater.from(requireContext())
                .inflateTransition(R.transition.media_shared_image)
            postponeEnterTransition()
        } else {
            // No shared-element transition → gate is open from the start
            isEnterTransitionDone = true
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhotoViewBinding.inflate(inflater, container, false)
        return binding.root
    }
    

    override fun onSaveInstanceState(outState: Bundle) {
        val currentMediaId = if (_binding != null && currentMediaList.isNotEmpty()) {
            currentMediaList.getOrNull(binding.viewPager.currentItem)?.id
        } else {
            null
        }
        outState.putLong(STATE_KEY_CURRENT_MEDIA_ID, currentMediaId ?: startMediaId)

        // Состояние теперь хранится в VideoPlaybackViewModel, который переживает ротацию
        // Сохраняем в Bundle только для полного уничтожения процесса
        val playback = videoPlaybackViewModel.savedPlaybackState
        if (playback != null) {
            outState.putLong(STATE_KEY_MEDIA_ID, playback.mediaId)
            outState.putLong(STATE_KEY_PLAYBACK_POSITION_MS, playback.positionMs)
            outState.putBoolean(STATE_KEY_PLAY_WHEN_READY, playback.playWhenReady)
        }
        super.onSaveInstanceState(outState)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        if (sharedTransitionName.isNotBlank()) {
            ViewCompat.setTransitionName(binding.openingPreview, sharedTransitionName)
        }

        if (skipOpeningPreviewOnRecreate) {
            binding.openingPreview.visibility = View.GONE
            isOpeningPreviewHidden = true
        } else {
            showOpeningPreview()
        }

        initViewModel()
        observeViewModel()
        loadSourceMedia()

        binding.viewPager.offscreenPageLimit = 1
        binding.viewPager.setPageTransformer { page, position ->
            if (binding.viewPager.scrollState == ViewPager2.SCROLL_STATE_IDLE) {
                page.alpha = 1f
                page.translationX = 0f
                page.scaleX = 1f
                page.scaleY = 1f
                page.findViewById<View>(R.id.photoView)?.translationX = 0f
                page.findViewById<View>(R.id.videoPlayerView)?.translationX = 0f
                return@setPageTransformer
            }

            val primaryIsVideo = currentMediaList.getOrNull(binding.viewPager.currentItem)?.isVideo == true
            if (primaryIsVideo) {
                page.alpha = 1f
                page.translationX = 0f
                page.scaleX = 1f
                page.scaleY = 1f
                page.findViewById<View>(R.id.photoView)?.translationX = 0f
                page.findViewById<View>(R.id.videoPlayerView)?.translationX = 0f
                return@setPageTransformer
            }

            val recyclerView = page.parent as? androidx.recyclerview.widget.RecyclerView
            val holder = recyclerView?.getChildViewHolder(page)
            val pageIndex = holder?.bindingAdapterPosition ?: androidx.recyclerview.widget.RecyclerView.NO_POSITION
            val media = currentMediaList.getOrNull(pageIndex)

            val photo = page.findViewById<View>(R.id.photoView)
            val video = page.findViewById<View>(R.id.videoPlayerView)
            val isVideoPage = (media?.isVideo == true) || (video?.visibility == View.VISIBLE)

            if (isVideoPage) {
                // Video surfaces flicker on alpha/transform changes on some devices.
                page.alpha = 1f
                page.translationX = 0f
                page.scaleX = 1f
                page.scaleY = 1f
                photo?.translationX = 0f
                video?.translationX = 0f
                return@setPageTransformer
            }

            when {
                position < -1f || position > 1f -> {
                    page.alpha = 0f
                    page.translationX = 0f
                    page.scaleX = 1f
                    page.scaleY = 1f
                    photo?.translationX = 0f
                    video?.translationX = 0f
                }
                else -> {
                    // Keep page geometry stable; apply parallax only for photo pages.
                    val absPos = abs(position)
                    page.alpha = 1f
                    page.translationX = 0f
                    page.scaleX = 1f
                    page.scaleY = 1f

                    if (absPos < PHOTO_PARALLAX_CENTER_DEAD_ZONE) {
                        photo?.translationX = 0f
                        video?.translationX = 0f
                    } else {
                        val innerParallax = -position * page.width * PHOTO_PARALLAX_OFFSET_FACTOR
                        photo?.translationX = innerParallax
                        video?.translationX = 0f
                    }
                }
            }
        }
        (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)?.apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
        }
        setupStableNavigationInset()
        binding.viewPager.registerOnPageChangeCallback(pageChangeCallback)
        updateViewerChrome(visible = false, isVideoPage = false)

        // Safety: if the transition listener never fires (skipped, no shared element, etc.)
        // force-open the gate after the maximum transition duration so openingPreview
        // is never stuck on top of the ViewPager.
        uiHandler.postDelayed({ markEnterTransitionDone() }, TRANSITION_MAX_WAIT_MS)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    closeViewer()
                }
            }
        )
        
        binding.closeButton.setOnClickListener {
            closeViewer()
        }

        // Drag-to-dismiss: swipe the photo up or down to close the viewer
        binding.dragDismissLayout.onDismiss = {
            closeViewer()
        }
    }

    private fun closeViewer() {
        // Немедленно останавливаем плеер — до того как popBackStack() выполнится
        // асинхронно. Иначе при двойном нажатии "назад" аудио продолжает играть
        // пока система обрабатывает транзакцию фрагмента.
        if (::photoPagerAdapter.isInitialized) {
            photoPagerAdapter.releaseAllPlayers()
        }
        val currentMediaId = lastBoundMediaSignature.getOrNull(binding.viewPager.currentItem) ?: startMediaId
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY_PHOTO_RETURN,
            Bundle().apply { putLong(RESULT_KEY_MEDIA_ID, currentMediaId) }
        )
        parentFragmentManager.popBackStack()
    }
    
    private fun initViewModel() {
        val database = GalerioDatabase.getInstance(requireContext())
        preferencesManager = PreferencesManager(requireContext().applicationContext)
        val mediaRepository = MediaRepositoryImpl(database.mediaDao())
        trashRepository = TrashRepositoryImpl(
            database.trashDao(),
            requireContext().filesDir.resolve("trash_media"),
            requireContext().applicationContext
        )
        val albumRepository = com.example.galerinio.data.repository.AlbumRepositoryImpl(database.albumDao())
        val factory = ViewModelFactory(mediaRepository, albumRepository)
        viewModel = ViewModelProvider(this, factory)[GalleryViewModel::class.java]
    }
    
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { uiState ->
                    if (uiState is UiState.Success) {
                        setupViewPager(uiState.data)
                    }
                }
            }
        }
    }

    private fun loadSourceMedia() {
        if (sourceMediaIds.isNotEmpty()) {
            viewModel.loadMediaByIds(sourceMediaIds)
            return
        }

        sourceAlbumId?.let {
            viewModel.loadMediaByAlbum(it)
            return
        }

        when (sourceFilter) {
            GalleryFragment.GalleryFilter.PHOTOS.name -> viewModel.loadImages()
            GalleryFragment.GalleryFilter.VIDEOS.name -> viewModel.loadVideos()
            GalleryFragment.GalleryFilter.FAVORITES.name -> viewModel.loadFavorites()
            else -> viewModel.loadAllMedia()
        }
    }
    
    private fun setupViewPager(mediaList: List<com.example.galerinio.domain.model.MediaModel>) {
        if (mediaList.isEmpty()) {
            currentMediaList = emptyList()
            lastBoundMediaSignature = emptyList()
            pendingPositionAfterDelete = null
            binding.viewPager.adapter = null
            hideOpeningPreview()
            return
        }

         val mediaSignature = mediaList.map { it.id }
         if (::photoPagerAdapter.isInitialized && mediaSignature == lastBoundMediaSignature) {
             return
         }

         // Сохраняем текущее состояние в ViewModel перед пересозданием адаптера
         if (::photoPagerAdapter.isInitialized) {
             photoPagerAdapter.capturePlaybackState()?.let { state ->
                 videoPlaybackViewModel.savePlaybackState(
                     state.mediaId,
                     state.positionMs,
                     state.playWhenReady
                 )
             }
             // КРИТИЧЕСКИ ВАЖНО: освобождаем старый адаптер
             photoPagerAdapter.releaseAllPlayers()
         }

         // Restore playback state only for actual recreation flow.
         val initialPlaybackForAdapter = if (skipOpeningPreviewOnRecreate) {
             videoPlaybackViewModel.savedPlaybackState
                 ?.takeIf { state -> mediaList.any { it.id == state.mediaId } }
                 ?.let { state ->
                     PhotoPagerAdapter.PlaybackState(
                         state.mediaId,
                         state.positionMs,
                         state.playWhenReady
                     )
                 }
         } else {
             null
         }

         // После удаления остаемся на соседнем элементе в этом же списке.
         val positionAfterDelete = pendingPositionAfterDelete?.coerceIn(0, mediaList.lastIndex)
         initialPosition = if (positionAfterDelete != null) {
             pendingPositionAfterDelete = null
             positionAfterDelete
         } else {
             // Определяем стартовую позицию
             val targetMediaId = initialPlaybackForAdapter?.mediaId ?: startMediaId
             mediaList.indexOfFirst { it.id == targetMediaId }.takeIf { it >= 0 } ?: 0
         }

         lastBoundMediaSignature = mediaSignature
         photoPagerAdapter = PhotoPagerAdapter(
              mediaList,
              onInfoClick = { media -> showInfo(media) },
              onDeleteClick = { media -> deleteMedia(media) },
              onShareClick = { media -> shareMedia(media) },
              onCopyClick = { media -> copyMedia(media) },
              onMoveClick = { media -> moveMedia(media) },
               onEditClick = { media -> onEditRequested(media) },
              onFavoriteClick = { media, targetFavorite -> toggleFavorite(media, targetFavorite) },
             rotationProvider = { mediaId -> photoRotationViewModel.getRotation(mediaId) },
              onRotateRightClick = { media -> rotatePhoto(media) },
             initialPlaybackState = initialPlaybackForAdapter,
             autoPlayInitialMediaId = mediaList.getOrNull(initialPosition)
                 ?.takeIf { it.isVideo && !skipOpeningPreviewOnRecreate }
                 ?.id,
              onPrimaryVisualReady = { mediaId ->
                 val b = _binding ?: return@PhotoPagerAdapter
                 val currentId = mediaList.getOrNull(b.viewPager.currentItem)?.id
                  if (!isOpeningPreviewHidden && currentId == mediaId) {
                      hideOpeningPreview()
                  }
              },
              onViewerChromeChanged = { visible, isVideoPage ->
                  updateViewerChrome(visible, isVideoPage)
              },
              onVideoPlaybackActiveChanged = { isActive ->
                  setKeepScreenOnForVideo(isActive)
              },
              onNavigateToAdjacentVideo = { direction ->
                  navigateToAdjacentVideo(direction)
              }
          )

         currentMediaList = mediaList
         preloadPhotoRotations(mediaList)
         startMediaId = mediaList[initialPosition].id
         binding.viewPager.adapter = photoPagerAdapter
         binding.viewPager.setCurrentItem(initialPosition, false)
         // Откладываем onPrimaryItemChanged, чтобы адаптер был полностью присоединен
         binding.viewPager.post {
             photoPagerAdapter.onPrimaryItemChanged(initialPosition)
         }

         val openedMedia = mediaList.getOrNull(initialPosition)
         if (openedMedia != null && openedMedia.filePath == previewPath && openedMedia.isVideo == previewIsVideo) {
             uiHandler.postDelayed(previewFallbackHideRunnable, PREVIEW_FALLBACK_MS)
         } else {
             hideOpeningPreview()
         }
    }

    override fun onPause() {
        // Сохраняем состояние в ViewModel - он переживет ротацию
        if (::photoPagerAdapter.isInitialized) {
            photoPagerAdapter.capturePlaybackState()?.let { state ->
                videoPlaybackViewModel.savePlaybackState(
                    state.mediaId,
                    state.positionMs,
                    state.playWhenReady
                )
            }
            photoPagerAdapter.releaseAllPlayers()
        }
        setKeepScreenOnForVideo(false)
        showSystemBars()
        super.onPause()
    }

    override fun onStop() {
        // Дополнительная страховка: если onPause не успел освободить плеер
        // (например, быстрое двойное нажатие "назад"), onStop гарантирует тишину.
        if (::photoPagerAdapter.isInitialized) {
            photoPagerAdapter.releaseAllPlayers()
        }
        super.onStop()
    }
    
    override fun onDestroyView() {
        uiHandler.removeCallbacksAndMessages(null)  // cancel all pending timers
        startSharedEnterIfNeeded()
        if (::photoPagerAdapter.isInitialized) {
            // Сохраняем состояние в ViewModel, если ещё не сохранено
            if (videoPlaybackViewModel.savedPlaybackState == null) {
                photoPagerAdapter.capturePlaybackState()?.let { state ->
                    videoPlaybackViewModel.savePlaybackState(
                        state.mediaId,
                        state.positionMs,
                        state.playWhenReady
                    )
                }
            }
            photoPagerAdapter.releaseAllPlayers()
        }
        setKeepScreenOnForVideo(false)
        showSystemBars()
        ViewCompat.setOnApplyWindowInsetsListener(binding.dragDismissLayout, null)
        binding.dragDismissLayout.updatePadding(
            top = baseViewerContainerPaddingTop,
            bottom = baseViewerContainerPaddingBottom
        )
        binding.viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        binding.viewPager.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private fun setupStableNavigationInset() {
        baseViewerContainerPaddingTop = binding.dragDismissLayout.paddingTop
        baseViewerContainerPaddingBottom = binding.dragDismissLayout.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.dragDismissLayout) { view, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                top = baseViewerContainerPaddingTop + systemBarsInsets.top,
                bottom = baseViewerContainerPaddingBottom + systemBarsInsets.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.dragDismissLayout)
    }

    private fun navigateToAdjacentVideo(direction: Int) {
        if (currentMediaList.isEmpty()) return
        if (binding.viewPager.scrollState != ViewPager2.SCROLL_STATE_IDLE) return

         val currentIndex = binding.viewPager.currentItem
         val targetIndex = when {
             direction < 0 -> (currentIndex - 1 downTo 0).firstOrNull { currentMediaList[it].isVideo }
             direction > 0 -> (currentIndex + 1..currentMediaList.lastIndex).firstOrNull { currentMediaList[it].isVideo }
             else -> null
         } ?: return

        if (targetIndex == currentIndex || targetIndex == pendingAdjacentNavTarget) return

        val now = SystemClock.uptimeMillis()
        if (now - lastAdjacentNavUptimeMs < ADJACENT_NAV_DEBOUNCE_MS) return

        pendingAdjacentNavTarget = targetIndex
        lastAdjacentNavUptimeMs = now
        binding.viewPager.setCurrentItem(targetIndex, true)
     }

    private fun updateViewerChrome(visible: Boolean, isVideoPage: Boolean) {
        if (_binding == null) return

        if (isViewerChromeVisible == visible && isCurrentPrimaryPageVideo == isVideoPage) {
            return
        }

        val shouldShowOverlay = visible
        isCurrentPrimaryPageVideo = isVideoPage
        isViewerChromeVisible = shouldShowOverlay

        if (isVideoPage) {
            setOverlayViewImmediate(binding.closeButton, shouldShowOverlay)
            setOverlayViewImmediate(binding.dragHandle, shouldShowOverlay)
        } else {
            animateOverlayView(binding.closeButton, shouldShowOverlay)
            animateOverlayView(binding.dragHandle, shouldShowOverlay)
        }
        // Keep viewer within app/system frame: do not use fullscreen.
        showSystemBars()
    }

    private fun setOverlayViewImmediate(view: View, visible: Boolean) {
        view.animate().cancel()
        view.visibility = if (visible) View.VISIBLE else View.GONE
        view.alpha = if (visible) 1f else 0f
    }

    private fun setKeepScreenOnForVideo(keepOn: Boolean) {
        if (isKeepingScreenOnForVideo == keepOn) return
        isKeepingScreenOnForVideo = keepOn
        _binding?.root?.keepScreenOn = keepOn
    }

    private fun animateOverlayView(view: View, visible: Boolean) {
        view.animate().cancel()
        if (visible) {
            if (view.isVisible && view.alpha >= 0.99f) return
            if (!view.isVisible) {
                view.alpha = 0f
                view.visibility = View.VISIBLE
            }
            view.animate().alpha(1f).setDuration(CONTROLS_ANIMATION_DURATION_MS).start()
        } else if (view.isVisible) {
            view.animate()
                .alpha(0f)
                .setDuration(CONTROLS_ANIMATION_DURATION_MS)
                .withEndAction {
                    if (_binding == null) return@withEndAction
                    view.visibility = View.GONE
                    view.alpha = 0f
                }
                .start()
        } else {
            view.alpha = 0f
        }
    }

    private fun showSystemBars() {
        val window = activity?.window ?: return
        WindowInsetsControllerCompat(window, binding.root).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun showOpeningPreview() {
        if (previewPath.isBlank()) {
            binding.openingPreview.visibility = View.GONE
            isOpeningPreviewHidden = true
            return
        }

        isOpeningPreviewHidden = false
        binding.openingPreview.visibility = View.VISIBLE
        binding.openingPreview.alpha = 1f

        // Use the same override dimensions as the preload in GalleryFragment so
        // Glide can serve the bitmap directly from memory cache — synchronously,
        // meaning the image appears in the very first rendered frame (no black flash).
        val dm = resources.displayMetrics
        Glide.with(this)
            .load(previewPath)
            .override(dm.widthPixels, dm.heightPixels)
            .fitCenter()
            .dontAnimate()
            .placeholder(android.R.color.black)
            .error(android.R.color.black)
            .into(binding.openingPreview)

        // Fallback in case the shared transition is postponed but no ready callback arrives.
        uiHandler.postDelayed({ startSharedEnterIfNeeded() }, SHARED_ENTER_FALLBACK_MS)
    }

    private fun markEnterTransitionDone() {
        if (isEnterTransitionDone) return
        isEnterTransitionDone = true
        if (pendingPreviewHide && _binding != null) {
            pendingPreviewHide = false
            executeFadeOutPreview()
        }
    }

    private fun hideOpeningPreview() {
        if (isOpeningPreviewHidden || _binding == null) return
        isOpeningPreviewHidden = true
        uiHandler.removeCallbacks(previewFallbackHideRunnable)
        if (!isEnterTransitionDone) {
            // The shared-element enter transition is still running.
            // Store the request — executeFadeOutPreview() will be called by
            // markEnterTransitionDone() as soon as the transition ends.
            pendingPreviewHide = true
            return
        }
        executeFadeOutPreview()
    }

    private fun executeFadeOutPreview() {
        if (_binding == null) return
        // One VSYNC frame so the GPU has finished uploading the ZoomImageView texture.
        binding.openingPreview.postOnAnimation {
            if (_binding == null) return@postOnAnimation
            binding.openingPreview.animate()
                .alpha(0f)
                .setDuration(PREVIEW_FADE_OUT_MS)
                .withEndAction {
                    if (_binding == null) return@withEndAction
                    binding.openingPreview.visibility = View.GONE
                    binding.openingPreview.alpha = 1f
                    Glide.with(this@PhotoViewFragment).clear(binding.openingPreview)
                }
                .start()
        }
    }

    private fun startSharedEnterIfNeeded() {
        if (isSharedTransitionStarted || sharedTransitionName.isBlank()) return
        isSharedTransitionStarted = true
        view?.post { startPostponedEnterTransition() }
    }
    
    private fun showInfo(media: com.example.galerinio.domain.model.MediaModel) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val info = """
            File Name: ${media.fileName}
            Path: ${media.filePath}
            Size: ${media.size} bytes
            Date Modified: ${dateFormat.format(Date(media.dateModified))}
            Date Added: ${dateFormat.format(Date(media.dateAdded))}
            Resolution: ${media.width}x${media.height}
            Type: ${media.mimeType}
        """.trimIndent()
        
        val builder = AlertDialog.Builder(requireContext())
            .setTitle("Media Info")
            .setMessage(info)
            .setPositiveButton("OK", null)
        DialogUi.showWithReadableButtons(builder, requireContext())
    }
    
    private fun deleteMedia(media: com.example.galerinio.domain.model.MediaModel) {
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_confirm_title))
            .setMessage(getString(R.string.delete_confirm_message, 1))
            .setPositiveButton(getString(R.string.delete_confirm_yes)) { _, _ ->
                lifecycleScope.launch {
                    val moved = try {
                        trashRepository.moveToTrash(media)
                    } catch (e: Exception) {
                        android.util.Log.e("PhotoViewFragment", "deleteMedia: moveToTrash failed", e)
                        false
                    }

                    if (moved) {
                        prepareNextPositionAfterDelete(media)
                        viewModel.deleteMedia(media.id)
                        Toast.makeText(requireContext(), getString(R.string.moved_to_trash), Toast.LENGTH_SHORT).show()
                    } else {
                        val permissionRequest = TrashRepositoryImpl.pendingUserPermissionRequest
                        if (permissionRequest != null) {
                            TrashRepositoryImpl.pendingUserPermissionRequest = null
                            pendingDeleteMedia = permissionRequest.second
                            val intentSender = permissionRequest.first.intentSender
                            deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.failed_move_to_trash), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
        DialogUi.showWithReadableButtons(builder, requireContext())
    }

    private fun prepareNextPositionAfterDelete(media: com.example.galerinio.domain.model.MediaModel) {
        val deleteIndex = currentMediaList.indexOfFirst { it.id == media.id }
        if (deleteIndex >= 0) {
            pendingPositionAfterDelete = deleteIndex
            return
        }
        pendingPositionAfterDelete = binding.viewPager.currentItem
    }

    private fun shareMedia(media: com.example.galerinio.domain.model.MediaModel) {
        val shareUri = resolveShareUri(media)
        if (shareUri == null) {
            Toast.makeText(requireContext(), "File not found", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = media.mimeType
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Media"))
    }
    
    private fun copyMedia(media: com.example.galerinio.domain.model.MediaModel) {
        pendingFileOperation = PendingFileOperation.COPY
        pendingOperationMedia = media
        folderPickerLauncher.launch(null)
    }
    
    private fun moveMedia(media: com.example.galerinio.domain.model.MediaModel) {
        pendingFileOperation = PendingFileOperation.MOVE
        pendingOperationMedia = media
        folderPickerLauncher.launch(null)
    }

    private fun performCopy(destTreeUri: Uri, media: com.example.galerinio.domain.model.MediaModel) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val copied = runCatching {
                val treeDocId = DocumentsContract.getTreeDocumentId(destTreeUri)
                val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(destTreeUri, treeDocId)
                val targetUri = DocumentsContract.createDocument(
                    requireContext().contentResolver,
                    treeDocUri,
                    media.mimeType,
                    media.fileName
                ) ?: return@runCatching false

                openMediaInputStream(media)?.use { input ->
                    requireContext().contentResolver.openOutputStream(targetUri)?.use { output ->
                        input.copyTo(output)
                    }
                } != null
            }.getOrDefault(false)

            withContext(Dispatchers.Main) {
                val message = if (copied) getString(R.string.files_copied, 1) else getString(R.string.copy_error)
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performMove(destTreeUri: Uri, media: com.example.galerinio.domain.model.MediaModel) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val copied = runCatching {
                val treeDocId = DocumentsContract.getTreeDocumentId(destTreeUri)
                val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(destTreeUri, treeDocId)
                val targetUri = DocumentsContract.createDocument(
                    requireContext().contentResolver,
                    treeDocUri,
                    media.mimeType,
                    media.fileName
                ) ?: return@runCatching false

                openMediaInputStream(media)?.use { input ->
                    requireContext().contentResolver.openOutputStream(targetUri)?.use { output ->
                        input.copyTo(output)
                    }
                } != null
            }.getOrDefault(false)

            val moved = copied && deleteMediaSource(media)
            withContext(Dispatchers.Main) {
                if (moved) {
                    viewModel.deleteMedia(media.id)
                    Toast.makeText(requireContext(), getString(R.string.files_moved, 1), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.move_error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openMediaInputStream(media: com.example.galerinio.domain.model.MediaModel): InputStream? {
        val contentUri = resolveContentUri(media)
        if (contentUri != null) {
            return runCatching { requireContext().contentResolver.openInputStream(contentUri) }
                .getOrNull()
        }

        val file = File(media.filePath)
        return if (file.exists()) FileInputStream(file) else null
    }

    private fun resolveShareUri(media: com.example.galerinio.domain.model.MediaModel): Uri? {
        val contentUri = resolveContentUri(media)
        if (contentUri != null) return contentUri

        val file = File(media.filePath)
        if (!file.exists()) return null
        return FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
    }

    private fun resolveContentUri(media: com.example.galerinio.domain.model.MediaModel): Uri? {
        if (media.filePath.startsWith("content://")) {
            return media.filePath.toUri()
        }

        val mediaStoreUri = ContentUris.withAppendedId(
            if (media.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            abs(media.id)
        )

        val canOpen = runCatching {
            requireContext().contentResolver.openAssetFileDescriptor(mediaStoreUri, "r")?.close()
            true
        }.getOrDefault(false)
        return if (canOpen) mediaStoreUri else null
    }

    private fun deleteMediaSource(media: com.example.galerinio.domain.model.MediaModel): Boolean {
        val contentUri = resolveContentUri(media)
        if (contentUri != null) {
            val deleted = runCatching {
                requireContext().contentResolver.delete(contentUri, null, null) > 0
            }.getOrDefault(false)
            if (deleted) return true
        }

        return runCatching { File(media.filePath).delete() }.getOrDefault(false)
    }

    private fun toggleFavorite(media: com.example.galerinio.domain.model.MediaModel, targetFavorite: Boolean) {
        viewModel.setFavorite(media.id, targetFavorite)
        val message = if (targetFavorite) "Added to favorites" else "Removed from favorites"
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun onEditRequested(media: com.example.galerinio.domain.model.MediaModel) {
        if (media.isVideo) return
        // Показываем системный chooser со списком всех редакторов (включая встроенный)
        openSystemEditor(media)
    }


    private fun openSystemEditor(media: com.example.galerinio.domain.model.MediaModel) {
        val sourceUri = resolveShareUri(media)
        if (sourceUri == null) {
            Toast.makeText(requireContext(), getString(R.string.photo_editor_open_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val contentResolver = requireContext().contentResolver
        val inAppIntent = Intent(requireContext(), PhotoEditorActivity::class.java).apply {
            putExtra(PhotoEditorActivity.EXTRA_INPUT_URI, sourceUri.toString())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, "image", sourceUri)
        }
        val fileProviderAuthority = "${requireContext().packageName}.fileprovider"
        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(sourceUri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, "image", sourceUri)
            // Для MediaStore URI у приложения часто нет write-доступа, и попытка
            // передать write-grant приводит к SecurityException на старте intent.
            if (sourceUri.authority == fileProviderAuthority) {
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        try {
            // Показываем системный chooser + явно добавляем встроенный редактор приложения.
            val chooserIntent = Intent.createChooser(intent, getString(R.string.photo_editor_external_chooser_title)).apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(inAppIntent))
            }
            photoEditorLauncher.launch(chooserIntent)
        } catch (security: SecurityException) {
            android.util.Log.w("PhotoViewFragment", "openSystemEditor: retry without write grant", security)
            val safeIntent = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(sourceUri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "image", sourceUri)
            }
            try {
                val fallbackChooser = Intent.createChooser(safeIntent, getString(R.string.photo_editor_external_chooser_title)).apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(inAppIntent))
                }
                photoEditorLauncher.launch(fallbackChooser)
            } catch (_: Exception) {
                Toast.makeText(requireContext(), getString(R.string.photo_editor_open_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), getString(R.string.photo_editor_external_missing), Toast.LENGTH_SHORT).show()
        }
    }

    private fun rotatePhoto(media: com.example.galerinio.domain.model.MediaModel) {
        if (media.isVideo) return
        val current = photoRotationViewModel.getRotation(media.id)
        val updated = normalizeRotation(current + 90)
        photoRotationViewModel.putRotation(media.id, updated)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching { preferencesManager.setMediaRotation(media.id, updated) }
        }
    }

    private fun preloadPhotoRotations(mediaList: List<com.example.galerinio.domain.model.MediaModel>) {
        val photoIds = mediaList.asSequence().filterNot { it.isVideo }.map { it.id }.toList()
        if (photoIds.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val persisted = runCatching { preferencesManager.getMediaRotations(photoIds) }
                .getOrDefault(emptyMap())
            withContext(Dispatchers.Main) {
                if (_binding == null || !::photoPagerAdapter.isInitialized) return@withContext
                photoRotationViewModel.putAll(persisted)
                currentMediaList.forEachIndexed { index, item ->
                    if (!item.isVideo && persisted.containsKey(item.id)) {
                        photoPagerAdapter.notifyItemChanged(index)
                    }
                }
            }
        }
    }

    private fun normalizeRotation(value: Int): Int {
        val normalized = value % 360
        return if (normalized < 0) normalized + 360 else normalized
    }

    companion object {
        const val REQUEST_KEY_PHOTO_RETURN = "photo_return"
        const val RESULT_KEY_MEDIA_ID = "result_media_id"
        private const val ADJACENT_NAV_DEBOUNCE_MS = 250L
        private const val CONTROLS_ANIMATION_DURATION_MS = 200L  // Синхронизировано с адаптером
        private const val STATE_KEY_MEDIA_ID = "state_media_id"
        private const val STATE_KEY_CURRENT_MEDIA_ID = "state_current_media_id"
        private const val STATE_KEY_PLAYBACK_POSITION_MS = "state_playback_position_ms"
        private const val STATE_KEY_PLAY_WHEN_READY = "state_play_when_ready"
        private const val ARG_START_MEDIA_ID = "start_media_id"
        private const val ARG_FILTER = "filter"
        private const val ARG_ALBUM_ID = "album_id"
        private const val ARG_MEDIA_IDS = "media_ids"
        private const val ARG_PREVIEW_PATH = "preview_path"
        private const val ARG_PREVIEW_IS_VIDEO = "preview_is_video"
        private const val ARG_SHARED_TRANSITION_NAME = "shared_transition_name"
        private const val PREVIEW_FALLBACK_MS = 1500L
        private const val PREVIEW_FADE_OUT_MS = 300L  // Увеличено для более плавного исчезновения
        private const val SHARED_ENTER_FALLBACK_MS = 450L  // Синхронизировано с transition duration
        // Maximum time we wait for the enter transition to complete before
        // force-opening the gate.  Slightly longer than the transition duration (400ms)
        // to handle slow devices.  With sharedTransitionName=null this is never reached
        // since isEnterTransitionDone=true from the start.
        private const val TRANSITION_MAX_WAIT_MS = 550L  // 400ms transition + 150ms буфер
        private const val PHOTO_PARALLAX_OFFSET_FACTOR = 0.22f
        private const val PHOTO_PARALLAX_CENTER_DEAD_ZONE = 0.03f

        fun newInstance(
            startMediaId: Long,
            filter: String,
            albumId: Long?,
            mediaIds: LongArray? = null,
            previewPath: String? = null,
            previewIsVideo: Boolean = false,
            sharedTransitionName: String? = null
        ): PhotoViewFragment {
            return PhotoViewFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_START_MEDIA_ID, startMediaId)
                    putString(ARG_FILTER, filter)
                    putLong(ARG_ALBUM_ID, albumId ?: -1L)
                    mediaIds?.let { putLongArray(ARG_MEDIA_IDS, it) }
                    putString(ARG_PREVIEW_PATH, previewPath)
                    putBoolean(ARG_PREVIEW_IS_VIDEO, previewIsVideo)
                    putString(ARG_SHARED_TRANSITION_NAME, sharedTransitionName)
                }
            }
        }
    }
}
