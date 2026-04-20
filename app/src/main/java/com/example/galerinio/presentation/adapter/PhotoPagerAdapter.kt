package com.example.galerinio.presentation.adapter

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.graphics.drawable.Drawable
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.galerinio.R
import com.example.galerinio.databinding.ItemPhotoViewBinding
import com.example.galerinio.domain.model.MediaModel
import java.io.File

@UnstableApi
class PhotoPagerAdapter(
    private val mediaList: List<MediaModel>,
    private val onInfoClick: (MediaModel) -> Unit,
    private val onDeleteClick: (MediaModel) -> Unit,
    private val onShareClick: (MediaModel) -> Unit,
    private val onCopyClick: (MediaModel) -> Unit,
    private val onMoveClick: (MediaModel) -> Unit,
    private val onEditClick: (MediaModel) -> Unit,
    private val onFavoriteClick: (MediaModel, Boolean) -> Unit,
    private val rotationProvider: (Long) -> Int,
    private val onRotateRightClick: (MediaModel) -> Unit,
    private val initialPlaybackState: PlaybackState? = null,
    private val autoPlayInitialMediaId: Long? = null,
    private val onPrimaryVisualReady: (Long) -> Unit = {},
    private val onViewerChromeChanged: (controlsVisible: Boolean, isVideoPage: Boolean) -> Unit = { _, _ -> },
    private val onVideoPlaybackActiveChanged: (Boolean) -> Unit = {},
    private val onNavigateToAdjacentVideo: (direction: Int) -> Unit = {}
) : RecyclerView.Adapter<PhotoPagerAdapter.PhotoViewHolder>() {

    companion object {
        private const val PAYLOAD_PRIMARY_CHANGED = "payload_primary_changed"
        private const val VIDEO_SEEK_MS = 10_000L
        private const val PREVIOUS_DOUBLE_TAP_WINDOW_MS = 280L
        private const val DISABLED_NAV_BUTTON_ALPHA = 0.35f
        private const val CONTROLS_AUTO_HIDE_MS = 2500L
        private const val CONTROLS_ANIMATION_DURATION_MS = 200L  // Единая длительность для плавной синхронизации
        private const val PLAYER_MIN_BUFFER_MS = 3_000
        private const val PLAYER_MAX_BUFFER_MS = 10_000
        private const val PLAYER_BUFFER_FOR_PLAYBACK_MS = 1_000
        private const val PLAYER_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_500
        private const val PLAYER_TARGET_BUFFER_BYTES = 6 * 1024 * 1024
    }

    data class PlaybackState(
        val mediaId: Long,
        val positionMs: Long,
        val playWhenReady: Boolean
    )

    private var areControlsVisible = false
    private var activeVideoHolder: PhotoViewHolder? = null
    private var primaryPosition: Int = RecyclerView.NO_POSITION
    private val favoriteOverrides = mutableMapOf<Long, Boolean>()
    private var pendingInitialPlaybackState: PlaybackState? = initialPlaybackState
    private var isVideoPlaybackActive = false
    private var pendingAutoPlayMediaId: Long? = autoPlayInitialMediaId

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoViewBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(mediaList[position])
    }

    override fun onBindViewHolder(
        holder: PhotoViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_PRIMARY_CHANGED)) {
            holder.updatePrimaryState(mediaList[position], position == primaryPosition)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: PhotoViewHolder) {
        if (holder === activeVideoHolder) {
            super.onViewRecycled(holder)
            return
        }
        holder.releaseVideoPlayer()
        super.onViewRecycled(holder)
    }

    override fun onViewDetachedFromWindow(holder: PhotoViewHolder) {
        if (holder === activeVideoHolder) {
            super.onViewDetachedFromWindow(holder)
            return
        }
        holder.releaseVideoPlayer()
        super.onViewDetachedFromWindow(holder)
    }

    private var recyclerView: RecyclerView? = null
    
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }
    
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    fun onPrimaryItemChanged(position: Int) {
        val previous = primaryPosition
        primaryPosition = position

        // Откладываем notifyItemChanged, чтобы избежать IllegalStateException
        // во время scroll callback (при layout/measure pass)
        recyclerView?.post {
            if (previous in mediaList.indices && previous != position && mediaList[previous].isVideo) {
                notifyItemChanged(previous, PAYLOAD_PRIMARY_CHANGED)
            }
            if (position in mediaList.indices && mediaList[position].isVideo) {
                notifyItemChanged(position, PAYLOAD_PRIMARY_CHANGED)
            }
        }

        if (position in mediaList.indices) {
            areControlsVisible = false
            onViewerChromeChanged(false, mediaList[position].isVideo)
            if (!mediaList[position].isVideo) {
                notifyVideoPlaybackActive(false)
            }
        }
    }

    fun releaseAllPlayers() {
        activeVideoHolder?.releaseVideoPlayer()
        activeVideoHolder = null
        notifyVideoPlaybackActive(false)
    }

    private fun notifyVideoPlaybackActive(active: Boolean) {
        if (isVideoPlaybackActive == active) return
        isVideoPlaybackActive = active
        onVideoPlaybackActiveChanged(active)
    }

    fun capturePlaybackState(): PlaybackState? {
        val holder = activeVideoHolder ?: return null
        val media = holder.boundMedia ?: return null
        val player = holder.player ?: return null
        return PlaybackState(
            mediaId = media.id,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            playWhenReady = player.playWhenReady
        )
    }

    override fun getItemCount(): Int = mediaList.size

    inner class PhotoViewHolder(
        private val binding: ItemPhotoViewBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        var player: ExoPlayer? = null
            private set
        var boundMedia: MediaModel? = null
            private set
        private var pendingSeekBackRunnable: Runnable? = null
        private var videoActionsMenu: PopupMenu? = null
        private var navBarInsetBottomPx: Int = 0
        private val baseBottomActionsPaddingBottom: Int = binding.bottomActionsBar.paddingBottom
        private val baseVideoPlayerPaddingBottom: Int = binding.videoPlayerView.paddingBottom
        private val baseVideoMoreBottomMargin: Int =
            (binding.btnVideoMore.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        private val autoHideControlsRunnable = Runnable {
            val exo = player ?: return@Runnable
            if (!exo.isPlaying) return@Runnable
            binding.videoPlayerView.hideController()
        }

        init {
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
                navBarInsetBottomPx = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                applySystemBottomInsetToChrome()
                insets
            }
            ViewCompat.requestApplyInsets(binding.root)
        }

        private fun scheduleAutoHideControls() {
            cancelAutoHideControls()
            binding.videoPlayerView.postDelayed(autoHideControlsRunnable, CONTROLS_AUTO_HIDE_MS)
        }

        private fun cancelAutoHideControls() {
            binding.videoPlayerView.removeCallbacks(autoHideControlsRunnable)
        }

        private fun currentAdapterPosition(): Int {
            val pos = bindingAdapterPosition
            return if (pos != RecyclerView.NO_POSITION) pos else absoluteAdapterPosition
        }

        fun bind(media: MediaModel) {
            boundMedia = media
            releaseVideoPlayer()

            if (media.isVideo) {
                bindVideo(media)
            } else {
                bindPhoto(media)
            }

            binding.btnInfo.setOnClickListener { onInfoClick(media) }
            binding.btnDelete.setOnClickListener { onDeleteClick(media) }
            binding.btnShare.setOnClickListener { onShareClick(media) }
            binding.btnCopy.setOnClickListener { onCopyClick(media) }
            binding.btnMove.setOnClickListener { onMoveClick(media) }
            binding.btnRotateRight.setOnClickListener {
                if (!media.isVideo) {
                    onRotateRightClick(media)
                    applyPhotoRotation(media)
                }
            }
            binding.btnEdit.setOnClickListener {
                if (!media.isVideo) {
                    onEditClick(media)
                }
            }
            binding.btnFavorite.setOnClickListener {
                val currentFavorite = favoriteOverrides[media.id] ?: media.isFavorite
                val targetFavorite = !currentFavorite
                favoriteOverrides[media.id] = targetFavorite
                setFavoriteIcon(targetFavorite)
                onFavoriteClick(media, targetFavorite)
            }
            binding.btnVideoMore.setOnClickListener {
                showVideoActionsMenu(media)
                binding.videoPlayerView.showController()
                scheduleAutoHideControls()
            }

            val isFavoriteNow = favoriteOverrides[media.id] ?: media.isFavorite
            setFavoriteIcon(isFavoriteNow)
            binding.btnRotateRight.visibility = if (media.isVideo) View.GONE else View.VISIBLE
            binding.btnEdit.visibility = if (media.isVideo) View.GONE else View.VISIBLE
        }

        fun updatePrimaryState(media: MediaModel, isPrimary: Boolean) {
            if (!media.isVideo) return
            boundMedia = media
            if (isPrimary) {
                bindVideo(media)
            } else {
                bindVideoThumbnail(media)
            }
        }

        private fun setFavoriteIcon(isFavorite: Boolean) {
            val favoriteIcon = if (isFavorite) R.drawable.heart_filled else R.drawable.heart_outline
            binding.btnFavorite.setImageResource(favoriteIcon)
        }

        fun releaseVideoPlayer() {
            cancelPendingSeekBack()
            cancelAutoHideControls()
            dismissVideoActionsMenu()

            // КРИТИЧЕСКИ ВАЖНО: полностью останавливаем воспроизведение перед освобождением
            player?.let { exo ->
                try {
                    // Сначала убираем автовоспроизведение
                    exo.playWhenReady = false
                    // Останавливаем плеер (это очищает буферы)
                    exo.stop()
                    // Очищаем медиа-элементы из очереди
                    exo.clearMediaItems()
                } catch (e: Exception) {
                    // Игнорируем ошибки при остановке уже освобожденного плеера
                }
            }
            
            try {
                binding.videoPlayerView.setControllerVisibilityListener(null as PlayerView.ControllerVisibilityListener?)
            } catch (_: Exception) { /* ignore */ }
            
            // Очищаем все обработчики кликов
            binding.videoPlayerView.findViewById<View?>(androidx.media3.ui.R.id.exo_prev)?.setOnClickListener(null)
            binding.videoPlayerView.findViewById<View?>(androidx.media3.ui.R.id.exo_next)?.setOnClickListener(null)
            binding.videoPlayerView.findViewById<View?>(androidx.media3.ui.R.id.exo_rew)?.setOnClickListener(null)
            binding.videoPlayerView.findViewById<View?>(androidx.media3.ui.R.id.exo_ffwd)?.setOnClickListener(null)
            
            // Отсоединяем плеер от view перед освобождением
            binding.videoPlayerView.player = null
            binding.videoPlayerView.alpha = 1f

            // Освобождаем ExoPlayer (это освобождает аудио-кодеки и другие ресурсы)
            player?.let { exo ->
                try {
                    exo.release()
                } catch (e: Exception) {
                    // Игнорируем ошибки при освобождении
                }
            }
            player = null
            notifyVideoPlaybackActive(false)

            if (activeVideoHolder === this) {
                activeVideoHolder = null
            }
        }

        private fun bindPhoto(media: MediaModel) {
            dismissVideoActionsMenu()
            updateBottomActionsOffset(isVideo = false)
            binding.videoPlayerView.visibility = View.GONE
            binding.videoPlayBadge.visibility = View.GONE
            binding.photoView.visibility = View.VISIBLE
            applyPhotoRotation(media)
            binding.photoView.setZoomEnabled(true)
            binding.photoView.resetZoom()
            binding.photoView.setOnSingleTapListener {
                areControlsVisible = !areControlsVisible
                applyControlsVisibility(animate = true)
            }
            applyControlsVisibility(animate = false)
            if (currentAdapterPosition() == primaryPosition) {
                onViewerChromeChanged(areControlsVisible, false)
                notifyVideoPlaybackActive(false)
            }

            val ctx = binding.root.context

            // Thumbnail acts as a low-res placeholder while the full-resolution image
            // is being decoded.  We intentionally do NOT call notifyPrimaryVisualReady
            // from the thumbnail callback — doing so would start the openingPreview
            // fade-out before the full-res is in place, revealing a blurry intermediate
            // state and causing a visible quality "pop".
            val thumbRequest = Glide.with(ctx)
                .load(media.filePath)
                .override(400, 400)
                .fitCenter()
                .dontAnimate()

            // Main full-resolution request.  The openingPreview overlay only starts
            // fading out AFTER this listener fires (i.e. full-res is ready AND the
            // ZoomImageView matrix has been set synchronously by setImageDrawable).
            Glide.with(ctx)
                .load(media.filePath)
                .thumbnail(thumbRequest)
                .placeholder(android.R.color.black)
                .error(android.R.color.black)
                .dontAnimate()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        notifyPrimaryVisualReady(media.id)
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        // Full-resolution image is now set on ZoomImageView (Glide calls
                        // target.onResourceReady AFTER this listener returns false).
                        // hideOpeningPreview waits 2 VSYNC frames before fading, by which
                        // time the GPU has uploaded the texture and fitImageToView() has
                        // been applied synchronously inside setImageDrawable().
                        notifyPrimaryVisualReady(media.id)
                        return false
                    }
                })
                .into(binding.photoView)
        }

        private fun bindVideoThumbnail(media: MediaModel) {
            dismissVideoActionsMenu()
            updateBottomActionsOffset(isVideo = true)
            binding.videoPlayerView.visibility = View.GONE
            binding.videoPlayBadge.visibility = View.VISIBLE
            binding.photoView.visibility = View.VISIBLE
            binding.photoView.rotation = 0f
            binding.photoView.setZoomEnabled(false)
            binding.photoView.setOnSingleTapListener(null)
            binding.btnVideoMore.visibility = View.GONE
            Glide.with(binding.root.context)
                .load(media.filePath)
                .thumbnail(
                    Glide.with(binding.root.context)
                        .load(media.filePath)
                        .override(120, 120)
                        .dontAnimate()
                )
                .placeholder(android.R.color.black)
                .error(android.R.color.black)
                .dontAnimate()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        notifyPrimaryVisualReady(media.id)
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        notifyPrimaryVisualReady(media.id)
                        return false
                    }
                })
                .into(binding.photoView)
        }

        private fun bindVideo(media: MediaModel) {
            if (currentAdapterPosition() != primaryPosition) {
                // Offscreen video pages should not allocate a player to avoid OOM on low-RAM devices.
                bindVideoThumbnail(media)
                return
            }

            val previousActiveHolder = activeVideoHolder.takeIf { it !== this }

            // КРИТИЧЕСКИ ВАЖНО: если у этого holder уже есть плеер, освобождаем его перед созданием нового
            // Это предотвращает утечку при повторном bind (например, при возврате на видео)
            if (player != null) {
                releaseVideoPlayer()
            }

            updateBottomActionsOffset(isVideo = true)
            binding.photoView.setOnSingleTapListener(null)
            binding.photoView.setZoomEnabled(false)
            binding.photoView.rotation = 0f
            // Keep poster visible until first decoded frame to avoid black flash between videos.
            binding.photoView.visibility = View.VISIBLE
            binding.videoPlayerView.visibility = View.VISIBLE
            binding.videoPlayerView.alpha = 0f
            // For primary video page use PlayerView controls only.
            binding.videoPlayBadge.visibility = View.GONE
            Glide.with(binding.root.context)
                .load(media.filePath)
                .thumbnail(
                    Glide.with(binding.root.context)
                        .load(media.filePath)
                        .override(120, 120)
                        .dontAnimate()
                )
                .placeholder(android.R.color.black)
                .error(android.R.color.black)
                .dontAnimate()
                .into(binding.photoView)
            areControlsVisible = false
            applyControlsVisibility(animate = false)

            val mediaUri = buildMediaUri(media.filePath)
            val context = binding.root.context
            val renderersFactory = DefaultRenderersFactory(context)
                // If hardware codec init fails (common for some HEVC streams), ExoPlayer
                // may fall back to another available decoder instead of hard failure.
                .setEnableDecoderFallback(true)
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    PLAYER_MIN_BUFFER_MS,
                    PLAYER_MAX_BUFFER_MS,
                    PLAYER_BUFFER_FOR_PLAYBACK_MS,
                    PLAYER_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .setTargetBufferBytes(PLAYER_TARGET_BUFFER_BYTES)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            val exoPlayer = ExoPlayer.Builder(context, renderersFactory)
                .setLoadControl(loadControl)
                .setSeekBackIncrementMs(VIDEO_SEEK_MS)
                .setSeekForwardIncrementMs(VIDEO_SEEK_MS)
                .build()
            player = exoPlayer
            activeVideoHolder = this
            binding.videoPlayerView.player = exoPlayer
            binding.videoPlayerView.setShutterBackgroundColor(Color.TRANSPARENT)
            binding.videoPlayerView.setControllerShowTimeoutMs(2500)
            binding.videoPlayerView.setControllerAutoShow(false)
            binding.videoPlayerView.setControllerHideOnTouch(true)
            // Сохраняем функцию enforceNavState для использования в ControllerVisibilityListener
            var enforceNavStateFunc: (() -> Unit)? = null

            binding.videoPlayerView.setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    areControlsVisible = visibility == View.VISIBLE
                    val isPrimaryVideoPage =
                        currentAdapterPosition() == primaryPosition && boundMedia?.isVideo == true
                    applyControlsVisibility(animate = !isPrimaryVideoPage)
                    if (areControlsVisible) {
                        // При показе контроллера восстанавливаем обработчики кликов,
                        // т.к. PlayerView может пересоздать внутренние view
                        enforceNavStateFunc?.invoke()
                        if (exoPlayer.isPlaying) {
                            scheduleAutoHideControls()
                        }
                    } else {
                        cancelAutoHideControls()
                    }
                }
            )
            enforceNavStateFunc = setupVideoControllerButtons(exoPlayer)
            // Не устанавливаем setOnClickListener — PlayerView сам обрабатывает тап
            // (toggleControllerVisibility), VisibilityListener синхронизирует нижний бар.

            exoPlayer.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (boundMedia?.id == media.id) {
                        binding.videoPlayerView.alpha = 1f
                        binding.photoView.visibility = View.GONE
                        binding.videoPlayBadge.visibility = View.GONE
                    }
                    previousActiveHolder?.releaseVideoPlayer()
                    notifyPrimaryVisualReady(media.id)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (currentAdapterPosition() == primaryPosition && boundMedia?.isVideo == true) {
                        notifyVideoPlaybackActive(isPlaying)
                    }
                    binding.videoPlayBadge.visibility = View.GONE
                    if (isPlaying && pendingAutoPlayMediaId == media.id) {
                        pendingAutoPlayMediaId = null
                    }
                    if (isPlaying && binding.videoPlayerView.isControllerFullyVisible) {
                        scheduleAutoHideControls()
                    } else if (!isPlaying) {
                        cancelAutoHideControls()
                        binding.videoPlayerView.showController()
                    }
                }
                
                override fun onPlaybackStateChanged(playbackState: Int) {
                    // Сбрасываем pendingInitialPlaybackState только когда плеер готов к воспроизведению
                    // это гарантирует, что состояние не потеряется при быстрой ротации экрана
                    if (playbackState == Player.STATE_READY && pendingInitialPlaybackState?.mediaId == media.id) {
                        pendingInitialPlaybackState = null
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val isHevcDecoderIssue = (error.message.orEmpty().contains("video/hevc", ignoreCase = true)
                        || error.message.orEmpty().contains("hvc1", ignoreCase = true)
                        || error.cause?.message.orEmpty().contains("video/hevc", ignoreCase = true)
                        || error.cause?.message.orEmpty().contains("hvc1", ignoreCase = true))
                    Toast.makeText(
                        context,
                        if (isHevcDecoderIssue) {
                            "HEVC decoder is not stable on this device"
                        } else {
                            "Can't play this video on device decoder"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                    exoPlayer.playWhenReady = false
                    cancelAutoHideControls()
                    notifyVideoPlaybackActive(false)
                }
            })

            exoPlayer.setMediaItem(MediaItem.fromUri(mediaUri))
            exoPlayer.prepare()

            val restored = pendingInitialPlaybackState.takeIf { it?.mediaId == media.id }
            val shouldAutoPlayNow = pendingAutoPlayMediaId == media.id
            if (restored != null) {
                exoPlayer.seekTo(restored.positionMs.coerceAtLeast(0L))
                // Auto-play is allowed only once for explicitly requested стартовое видео.
                exoPlayer.playWhenReady = shouldAutoPlayNow
                // НЕ сбрасываем состояние сразу - оно будет сброшено в onPlaybackStateChanged
                // когда плеер действительно будет готов к воспроизведению
            } else {
                exoPlayer.playWhenReady = shouldAutoPlayNow
            }
            binding.videoPlayerView.hideController()
            if (!exoPlayer.playWhenReady) {
                // Avoid requiring an extra tap: show native player play button immediately.
                binding.videoPlayerView.showController()
            }
            if (exoPlayer.playWhenReady) {
                scheduleAutoHideControls()
            }
        }

        private fun showVideoActionsMenu(media: MediaModel) {
            dismissVideoActionsMenu()
            val popup = PopupMenu(binding.root.context, binding.btnVideoMore)
            popup.menuInflater.inflate(R.menu.video_actions_menu, popup.menu)

            val isFavoriteNow = favoriteOverrides[media.id] ?: media.isFavorite
            popup.menu.findItem(R.id.action_favorite)?.title = if (isFavoriteNow) {
                binding.root.context.getString(R.string.remove_from_favorites)
            } else {
                binding.root.context.getString(R.string.favorite)
            }

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_info -> onInfoClick(media)
                    R.id.action_share -> onShareClick(media)
                    R.id.action_copy -> onCopyClick(media)
                    R.id.action_move -> onMoveClick(media)
                    R.id.action_delete -> onDeleteClick(media)
                    R.id.action_favorite -> {
                        val currentFavorite = favoriteOverrides[media.id] ?: media.isFavorite
                        val targetFavorite = !currentFavorite
                        favoriteOverrides[media.id] = targetFavorite
                        onFavoriteClick(media, targetFavorite)
                    }
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            popup.setOnDismissListener {
                if (videoActionsMenu === popup) {
                    videoActionsMenu = null
                }
            }
            videoActionsMenu = popup
            popup.show()
        }

        private fun dismissVideoActionsMenu() {
            videoActionsMenu?.dismiss()
            videoActionsMenu = null
        }

        private fun setupVideoControllerButtons(exoPlayer: ExoPlayer): () -> Unit {
            val hasPreviousVideo = hasAdjacentVideo(direction = -1)
            val hasNextVideo = hasAdjacentVideo(direction = 1)

            binding.videoPlayerView.findViewById<View?>(androidx.media3.ui.R.id.exo_prev)?.apply {
                applyAdjacentVideoButtonState(isAvailable = hasPreviousVideo)
                visibility = View.VISIBLE
                setOnClickListener {
                    if (!hasPreviousVideo) return@setOnClickListener
                    if (pendingSeekBackRunnable != null) {
                        cancelPendingSeekBack()
                        onNavigateToAdjacentVideo(-1)
                    } else {
                        val runnable = Runnable {
                            pendingSeekBackRunnable = null
                            seekBack(exoPlayer)
                        }
                        pendingSeekBackRunnable = runnable
                        postDelayed(runnable, PREVIOUS_DOUBLE_TAP_WINDOW_MS)
                    }
                    binding.videoPlayerView.showController()
                    scheduleAutoHideControls()
                }
            }

            val seekBackClick = View.OnClickListener {
                cancelPendingSeekBack()
                seekBack(exoPlayer)
                binding.videoPlayerView.showController()
                scheduleAutoHideControls()
            }
            binding.videoPlayerView.findViewById<View?>(androidx.media3.ui.R.id.exo_rew)?.setOnClickListener(seekBackClick)
            binding.videoPlayerView.findViewById<View?>(androidx.media3.ui.R.id.exo_rew)?.apply {
                isEnabled = true
                isClickable = true
                alpha = 1f
            }

            val seekForwardClick = View.OnClickListener {
                cancelPendingSeekBack()
                seekForward(exoPlayer)
                binding.videoPlayerView.showController()
                scheduleAutoHideControls()
            }
            binding.videoPlayerView.findViewById<View?>(androidx.media3.ui.R.id.exo_next)?.apply {
                applyAdjacentVideoButtonState(isAvailable = hasNextVideo)
                visibility = View.VISIBLE
                setOnClickListener {
                    if (!hasNextVideo) return@setOnClickListener
                    cancelPendingSeekBack()
                    onNavigateToAdjacentVideo(1)
                    binding.videoPlayerView.showController()
                    scheduleAutoHideControls()
                }
            }
            binding.videoPlayerView.findViewById<View?>(androidx.media3.ui.R.id.exo_ffwd)?.setOnClickListener(seekForwardClick)
            binding.videoPlayerView.findViewById<View?>(androidx.media3.ui.R.id.exo_ffwd)?.apply {
                isEnabled = true
                isClickable = true
                alpha = 1f
            }

            // ExoPlayer вызывает updateButton() при смене состояния плеера, что сбрасывает
            // isEnabled=false для exo_prev/exo_next (т.к. плейлист из одного элемента).
            // Принудительно восстанавливаем наше состояние после каждого такого вызова.
            fun enforceNavState() {
                binding.videoPlayerView.post {
                    binding.videoPlayerView.findViewById<View?>(androidx.media3.ui.R.id.exo_prev)?.apply {
                        applyAdjacentVideoButtonState(isAvailable = hasPreviousVideo)
                    }
                    binding.videoPlayerView.findViewById<View?>(androidx.media3.ui.R.id.exo_next)?.apply {
                        applyAdjacentVideoButtonState(isAvailable = hasNextVideo)
                    }
                    // Some controller updates reset ffwd/rew availability and listeners.
                    // Пересоздаем обработчики внутри post для корректной работы
                    binding.videoPlayerView.findViewById<View?>(androidx.media3.ui.R.id.exo_rew)?.apply {
                        isEnabled = true
                        isClickable = true
                        alpha = 1f
                        setOnClickListener {
                            cancelPendingSeekBack()
                            seekBack(exoPlayer)
                            binding.videoPlayerView.showController()
                            scheduleAutoHideControls()
                        }
                    }
                    binding.videoPlayerView.findViewById<View?>(androidx.media3.ui.R.id.exo_ffwd)?.apply {
                        isEnabled = true
                        isClickable = true
                        alpha = 1f
                        setOnClickListener {
                            cancelPendingSeekBack()
                            seekForward(exoPlayer)
                            binding.videoPlayerView.showController()
                            scheduleAutoHideControls()
                        }
                    }
                }
            }
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) = enforceNavState()
                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = enforceNavState()
            })
            
            // Возвращаем функцию для вызова при показе контроллера
            return ::enforceNavState
        }

        private fun View.applyAdjacentVideoButtonState(isAvailable: Boolean) {
            isEnabled = isAvailable
            isClickable = isAvailable
            alpha = if (isAvailable) 1f else DISABLED_NAV_BUTTON_ALPHA
        }

        private fun hasAdjacentVideo(direction: Int): Boolean {
            val startIndex = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: primaryPosition
            if (startIndex == RecyclerView.NO_POSITION) return false

            return when {
                direction < 0 -> (startIndex - 1 downTo 0).any { mediaList[it].isVideo }
                direction > 0 -> ((startIndex + 1)..mediaList.lastIndex).any { mediaList[it].isVideo }
                else -> false
            }
        }

        private fun cancelPendingSeekBack() {
            pendingSeekBackRunnable?.let { binding.videoPlayerView.removeCallbacks(it) }
            pendingSeekBackRunnable = null
        }


        private fun seekBack(exoPlayer: ExoPlayer) {
            exoPlayer.seekTo((exoPlayer.currentPosition - VIDEO_SEEK_MS).coerceAtLeast(0L))
        }

        private fun seekForward(exoPlayer: ExoPlayer) {
            val duration = exoPlayer.duration.takeIf { it > 0L } ?: (exoPlayer.currentPosition + VIDEO_SEEK_MS)
            exoPlayer.seekTo((exoPlayer.currentPosition + VIDEO_SEEK_MS).coerceAtMost(duration))
        }

        private fun notifyPrimaryVisualReady(mediaId: Long) {
            val pos = currentAdapterPosition()
            if (pos != RecyclerView.NO_POSITION && pos == primaryPosition) {
                onPrimaryVisualReady(mediaId)
            }
        }

        private fun updateBottomActionsOffset(isVideo: Boolean) {
            val params = binding.bottomActionsBar.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            // Keep action bar above ExoPlayer controller to avoid overlap with seek/navigation controls.
            params.bottomMargin = if (isVideo) dpToPx(76f) else 0
            binding.bottomActionsBar.layoutParams = params
            applySystemBottomInsetToChrome()
        }

        private fun applySystemBottomInsetToChrome() {
            binding.bottomActionsBar.updatePadding(
                bottom = baseBottomActionsPaddingBottom + navBarInsetBottomPx
            )
            binding.videoPlayerView.updatePadding(
                bottom = baseVideoPlayerPaddingBottom + navBarInsetBottomPx
            )
            val moreParams = binding.btnVideoMore.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            moreParams.bottomMargin = baseVideoMoreBottomMargin + navBarInsetBottomPx
            binding.btnVideoMore.layoutParams = moreParams
        }

        private fun dpToPx(dp: Float): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                binding.root.resources.displayMetrics
            ).toInt()
        }

        private fun openWithExternalPlayer(uri: Uri) {
            val context = binding.root.context
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "No external video player found", Toast.LENGTH_SHORT).show()
            }
        }

        private fun buildMediaUri(filePath: String): Uri {
            if (filePath.startsWith("content://")) return Uri.parse(filePath)
            val file = File(filePath)
            return if (file.exists()) {
                FileProvider.getUriForFile(
                    binding.root.context,
                    "${binding.root.context.packageName}.fileprovider",
                    file
                )
            } else {
                Uri.fromFile(file)
            }
        }

        private fun applyControlsVisibility(animate: Boolean) {
            val actionsBar = binding.bottomActionsBar
            val isPrimaryPage = currentAdapterPosition() == primaryPosition
            val isPrimaryVideoPage = isPrimaryPage && boundMedia?.isVideo == true
            val animateVideoChrome = animate && !isPrimaryVideoPage

            val showPhotoActions = isPrimaryPage && !isPrimaryVideoPage && areControlsVisible
            val showVideoMore = isPrimaryVideoPage && areControlsVisible

            if (!showVideoMore) {
                dismissVideoActionsMenu()
            }

            animateChromeView(actionsBar, showPhotoActions, animate)
            animateChromeView(binding.btnVideoMore, showVideoMore, animateVideoChrome)

            if (isPrimaryPage) {
                onViewerChromeChanged(areControlsVisible, isPrimaryVideoPage)
            }
        }

        private fun applyPhotoRotation(media: MediaModel) {
            val angle = rotationProvider(media.id).toFloat()
            binding.photoView.rotation = angle
        }

        private fun animateChromeView(view: View, visible: Boolean, animate: Boolean) {
            if (visible) {
                if (view.visibility == View.VISIBLE && view.alpha >= 0.99f) return
                if (animate) {
                    view.animate().cancel()
                    if (view.visibility != View.VISIBLE) {
                        view.alpha = 0f
                        view.visibility = View.VISIBLE
                    }
                    view.animate().alpha(1f).setDuration(CONTROLS_ANIMATION_DURATION_MS).start()
                } else {
                    view.visibility = View.VISIBLE
                    view.alpha = 1f
                }
                return
            }

            if (animate && view.visibility == View.VISIBLE) {
                view.animate().cancel()
                view.animate()
                    .alpha(0f)
                    .setDuration(CONTROLS_ANIMATION_DURATION_MS)
                    .withEndAction {
                        view.visibility = View.GONE
                        view.alpha = 0f
                    }
                    .start()
            } else {
                view.alpha = 0f
                view.visibility = View.GONE
            }
        }
    }
}
