package com.example.galerinio.presentation.ui.editor

import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.widget.PopupWindow
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.doAfterTextChanged
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.galerinio.R
import com.example.galerinio.databinding.ActivityPhotoEditorBinding
import com.example.galerinio.presentation.adapter.PhotoEditorCategoryAdapter
import com.example.galerinio.presentation.ui.util.DialogUi
import com.google.android.material.button.MaterialButton
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

class PhotoEditorActivity : AppCompatActivity() {

    private enum class ToolCategory(
        val key: String,
        val titleRes: Int,
        val iconRes: Int
    ) {
        TRANSFORM("transform", R.string.photo_editor_category_transform, R.drawable.ic_editor_transform_24),
        ADJUST("adjust", R.string.photo_editor_category_adjust, R.drawable.ic_editor_adjust_24),
        CROP("crop", R.string.photo_editor_category_crop, R.drawable.ic_editor_crop_24),
        TEXT("text", R.string.photo_editor_category_text, R.drawable.ic_editor_text_24),
        RESIZE("resize", R.string.photo_editor_category_resize, R.drawable.ic_editor_resize_24)
    }

    private enum class TextFontFamily {
        BOLD_DEFAULT,
        SANS,
        SERIF,
        MONO
    }

    private data class EditorState(
        val rotationSteps: Int,
        val flipHorizontal: Boolean,
        val flipVertical: Boolean,
        val brightnessOffset: Float,
        val contrast: Float,
        val saturation: Float,
        val resizeWidth: Int,
        val resizeHeight: Int,
        val cropRectNormalized: RectF?,
        val overlayText: String,
        val overlayTextSizeSp: Float,
        val overlayTextColor: Int,
        val overlayTextBackgroundColor: Int,
        val textFontFamily: TextFontFamily,
        val textBold: Boolean,
        val textItalic: Boolean,
        val overlayTextXPercent: Int,
        val overlayTextYPercent: Int
    )

    private lateinit var binding: ActivityPhotoEditorBinding

    private var sourceBitmap: Bitmap? = null
    private var inputUri: Uri? = null

    private var rotationSteps: Int = 0
    private var flipHorizontal: Boolean = false
    private var flipVertical: Boolean = false
    private var brightnessOffset: Float = 0f
    private var contrast: Float = 1f
    private var saturation: Float = 1f
    private var resizeWidth: Int = 0
    private var resizeHeight: Int = 0
    private var appliedCropRectNormalized: RectF? = null
    private var pendingCropRectNormalized: RectF? = null
    private var overlayText: String = ""
    private var overlayTextSizeSp: Float = 36f
    private var overlayTextColor: Int = Color.WHITE
    private var overlayTextBackgroundColor: Int = Color.TRANSPARENT
    private var textFontFamily: TextFontFamily = TextFontFamily.BOLD_DEFAULT
    private var textBold: Boolean = true
    private var textItalic: Boolean = false
    private var overlayTextXPercent: Int = 50
    private var overlayTextYPercent: Int = 85
    private var previewRenderJob: Job? = null
    private var previewBitmap: Bitmap? = null
    private var activeCategory: ToolCategory = ToolCategory.TRANSFORM
    private var visiblePanel: View? = null
    private lateinit var categoryAdapter: PhotoEditorCategoryAdapter
    private val undoHistory = ArrayDeque<EditorState>()
    private val redoHistory = ArrayDeque<EditorState>()
    private var isRestoringState = false
    private var isTextDragging = false
    private var textDragCandidate = false
    private var textDragStartX = 0f
    private var textDragStartY = 0f
    private var textDragStartTime = 0L
    private var isUpdatingResizeInputs = false
    private var initialEditorState: EditorState? = null
    private val longPressTimeoutMs by lazy { ViewConfiguration.getLongPressTimeout().toLong() }
    private val touchSlopPx by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        inputUri = intent.getStringExtra(EXTRA_INPUT_URI)?.let(Uri::parse)
        if (inputUri == null) {
            Toast.makeText(this, R.string.photo_editor_open_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.btnBack.setOnClickListener { handleExitRequest() }
        binding.btnSave.setOnClickListener { saveEditedImage() }
        binding.btnUndo.setOnClickListener { undoLastStep() }
        binding.btnRedo.setOnClickListener { redoLastStep() }
        binding.btnUndo.isEnabled = false
        binding.btnRedo.isEnabled = false

        binding.btnRotate.setOnClickListener {
            pushHistoryState()
            rotationSteps = (rotationSteps + 1) % 4
            schedulePreviewRender()
        }
        binding.btnFlipH.setOnClickListener {
            pushHistoryState()
            flipHorizontal = !flipHorizontal
            schedulePreviewRender()
        }
        binding.btnFlipV.setOnClickListener {
            pushHistoryState()
            flipVertical = !flipVertical
            schedulePreviewRender()
        }

        setupSeekbars()
        setupAdvancedTools()
        setupTextDragOnPhoto()
        setupCategoryTabs()
        // Use persistent layout listeners so crop overlay stays in sync
        // whenever the views resize (e.g. after bottom panel animation completes).
        // post{} ensures the sync runs after the full layout pass so both sibling
        // views already have their final sizes.
        val layoutSync = View.OnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                v.post { syncCropOverlayBounds() }
            }
        }
        binding.editorImageView.addOnLayoutChangeListener(layoutSync)
        binding.cropOverlay.addOnLayoutChangeListener(layoutSync)
        binding.cropOverlay.onCropDoubleTap = { commitCropSelection() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleExitRequest()
            }
        })
        loadSourceBitmap()
    }

    private fun setupCategoryTabs() {
        categoryAdapter = PhotoEditorCategoryAdapter(
            items = ToolCategory.values().map { category ->
                PhotoEditorCategoryAdapter.Item(
                    id = category.key,
                    titleRes = category.titleRes,
                    iconRes = category.iconRes
                )
            },
            onItemClick = { item ->
                val category = ToolCategory.values().firstOrNull { it.key == item.id }
                if (category != null) {
                    setActiveCategory(category)
                }
            }
        )

        binding.rvCategories.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCategories.adapter = categoryAdapter
        setActiveCategory(ToolCategory.TRANSFORM)
    }

    private fun setActiveCategory(category: ToolCategory) {
        if (activeCategory == category && visiblePanel != null) return
        activeCategory = category
        categoryAdapter.selectById(category.key)

        val cropActive = category == ToolCategory.CROP
        binding.editorImageView.setZoomEnabled(!cropActive)
        if (cropActive) {
            // Crop overlay must use fit-centered image bounds, not zoomed/panned state.
            binding.editorImageView.resetZoom()
            // После сброса зума повторно синхронизируем границы, чтобы гарантировать
            // что cropRect соответствует актуальным границам изображения
            binding.editorImageView.post {
                if (activeCategory == ToolCategory.CROP) {
                    syncCropOverlayBounds()
                }
            }
            // Также синхронизируем после завершения layout cropOverlay
            binding.cropOverlay.post {
                if (activeCategory == ToolCategory.CROP) {
                    syncCropOverlayBounds()
                }
            }
        }

        val newPanel = panelForCategory(category)
        showPanelAnimated(newPanel)
        updateCropUiForCategory(category)
        if (category != ToolCategory.TEXT) {
            hideTextDragIndicator()
            isTextDragging = false
            textDragCandidate = false
        }
    }

    private fun panelForCategory(category: ToolCategory): View {
        return when (category) {
            ToolCategory.TRANSFORM -> binding.panelTransform
            ToolCategory.ADJUST -> binding.panelAdjust
            ToolCategory.CROP -> binding.panelCrop
            ToolCategory.TEXT -> binding.panelText
            ToolCategory.RESIZE -> binding.panelResize
        }
    }

    private fun showPanelAnimated(newPanel: View) {
        val oldPanel = visiblePanel
        if (oldPanel == null) {
            listOf(binding.panelTransform, binding.panelAdjust, binding.panelCrop, binding.panelText, binding.panelResize)
                .forEach { panel ->
                    panel.visibility = if (panel === newPanel) View.VISIBLE else View.GONE
                    panel.alpha = 1f
                    panel.translationY = 0f
                }
            visiblePanel = newPanel
            return
        }
        if (oldPanel === newPanel) return

        oldPanel.animate()
            .alpha(0f)
            .translationY(24f)
            .setDuration(170)
            .withEndAction {
                oldPanel.visibility = View.GONE
                oldPanel.alpha = 1f
                oldPanel.translationY = 0f
                // After the old panel goes GONE the image container resizes;
                // re-sync crop overlay after the resulting layout pass.
                if (activeCategory == ToolCategory.CROP) {
                    binding.editorImageView.post { syncCropOverlayBounds() }
                }
            }
            .start()

        newPanel.alpha = 0f
        newPanel.translationY = 24f
        newPanel.visibility = View.VISIBLE
        newPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(190)
            .start()

        visiblePanel = newPanel
    }

    private fun setupSeekbars() {
        updateAdjustValueLabels()
        binding.seekBrightness.setOnSeekBarChangeListener(simpleSeekListener(onStartChange = { pushHistoryState() }) { progress ->
            brightnessOffset = progress - 100f
            updateAdjustValueLabels()
            schedulePreviewRender()
        })

        binding.seekContrast.setOnSeekBarChangeListener(simpleSeekListener(onStartChange = { pushHistoryState() }) { progress ->
            contrast = (progress / 100f).coerceAtLeast(0f)
            updateAdjustValueLabels()
            schedulePreviewRender()
        })

        binding.seekSaturation.setOnSeekBarChangeListener(simpleSeekListener(onStartChange = { pushHistoryState() }) { progress ->
            saturation = (progress / 100f).coerceAtLeast(0f)
            updateAdjustValueLabels()
            schedulePreviewRender()
        })
    }

    private fun updateAdjustValueLabels() {
        binding.textBrightnessValue.text = brightnessOffset.toInt().toString()
        binding.textContrastValue.text = String.format(java.util.Locale.US, "%.2f", contrast)
        binding.textSaturationValue.text = String.format(java.util.Locale.US, "%.2f", saturation)
    }

    private fun setupAdvancedTools() {
        binding.btnApplyResize.setOnClickListener { applyResizeFromInputs() }
        binding.btnCropApply.setOnClickListener { commitCropSelection() }
        binding.btnCropCancel.setOnClickListener { cancelCropSelection() }
        setupResizeAspectLiveSync()

        binding.editOverlayText.doAfterTextChanged { text ->
            if (isRestoringState) return@doAfterTextChanged
            pushHistoryState()
            overlayText = text?.toString().orEmpty()
            if (overlayText.isBlank()) {
                hideTextDragIndicator()
            }
            schedulePreviewRender()
        }

        binding.btnTextSize.setOnClickListener { showTextSizePopup() }
        binding.btnTextColor.setOnClickListener { showTextColorPopup() }
        binding.btnTextBackground.setOnClickListener { showTextBackgroundPopup() }
        binding.btnTextBold.setOnClickListener {
            pushHistoryState()
            textBold = !textBold
            updateTextToolbarState()
            schedulePreviewRender()
        }
        binding.btnTextItalic.setOnClickListener {
            pushHistoryState()
            textItalic = !textItalic
            updateTextToolbarState()
            schedulePreviewRender()
        }
        binding.btnTextFont.setOnClickListener { showTextFontPopup() }

        updateTextToolbarState()
    }

    private fun setupResizeAspectLiveSync() {
        binding.checkboxKeepAspect.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked || isUpdatingResizeInputs) return@setOnCheckedChangeListener
            val source = sourceBitmap ?: return@setOnCheckedChangeListener
            val widthValue = binding.editResizeWidth.text?.toString()?.toIntOrNull()
            val heightValue = binding.editResizeHeight.text?.toString()?.toIntOrNull()
            if (binding.editResizeHeight.hasFocus() && heightValue != null && heightValue > 0) {
                val linkedWidth = ((heightValue.toFloat() / source.height.toFloat()) * source.width).roundToInt().coerceAtLeast(1)
                updateResizeInputFields(linkedWidth, heightValue)
            } else if (widthValue != null && widthValue > 0) {
                val linkedHeight = ((widthValue.toFloat() / source.width.toFloat()) * source.height).roundToInt().coerceAtLeast(1)
                updateResizeInputFields(widthValue, linkedHeight)
            }
        }

        binding.editResizeWidth.doAfterTextChanged { text ->
            if (isUpdatingResizeInputs || isRestoringState || !binding.checkboxKeepAspect.isChecked) return@doAfterTextChanged
            val source = sourceBitmap ?: return@doAfterTextChanged
            val widthValue = text?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: return@doAfterTextChanged
            val linkedHeight = ((widthValue.toFloat() / source.width.toFloat()) * source.height).roundToInt().coerceAtLeast(1)
            updateResizeInputFields(widthValue, linkedHeight)
        }

        binding.editResizeHeight.doAfterTextChanged { text ->
            if (isUpdatingResizeInputs || isRestoringState || !binding.checkboxKeepAspect.isChecked) return@doAfterTextChanged
            val source = sourceBitmap ?: return@doAfterTextChanged
            val heightValue = text?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: return@doAfterTextChanged
            val linkedWidth = ((heightValue.toFloat() / source.height.toFloat()) * source.width).roundToInt().coerceAtLeast(1)
            updateResizeInputFields(linkedWidth, heightValue)
        }
    }

    private fun updateResizeInputFields(width: Int, height: Int) {
        isUpdatingResizeInputs = true
        val normalizedWidth = width.coerceAtMost(8192)
        val normalizedHeight = height.coerceAtMost(8192)
        val widthText = normalizedWidth.toString()
        val heightText = normalizedHeight.toString()
        if (binding.editResizeWidth.text?.toString() != widthText) {
            binding.editResizeWidth.setText(widthText)
        }
        if (binding.editResizeHeight.text?.toString() != heightText) {
            binding.editResizeHeight.setText(heightText)
        }
        isUpdatingResizeInputs = false
    }

    private fun handleExitRequest() {
        if (!hasUnsavedChanges()) {
            finish()
            return
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.photo_editor_unsaved_title)
            .setMessage(R.string.photo_editor_unsaved_message)
            .setPositiveButton(R.string.photo_editor_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel, null)
        DialogUi.showWithReadableButtons(builder, this, applyDrawerNightStyle = true)
    }

    private fun hasUnsavedChanges(): Boolean {
        val baseline = initialEditorState ?: return false
        return captureEditorState() != baseline
    }

    private fun showTextSizePopup() {
        val values = (10..120).step(2).toList()
        val labels = values.map { it.toString() }.toTypedArray()
        val selectedIndex = values.indexOfFirst { it >= overlayTextSizeSp.toInt() }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.photo_editor_text_size)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                val selected = values[which].toFloat()
                if (overlayTextSizeSp != selected) {
                    pushHistoryState()
                    overlayTextSizeSp = selected
                    updateTextToolbarState()
                    schedulePreviewRender()
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun showTextColorPopup() {
        showColorPalette(
            anchor = binding.btnTextColor,
            colors = textColorOptions()
        ) { selected ->
            if (overlayTextColor != selected) {
                pushHistoryState()
                overlayTextColor = selected
                updateTextToolbarState()
                schedulePreviewRender()
            }
        }
    }

    private fun showTextBackgroundPopup() {
        showColorPalette(
            anchor = binding.btnTextBackground,
            colors = textBackgroundOptions()
        ) { selected ->
            if (overlayTextBackgroundColor != selected) {
                pushHistoryState()
                overlayTextBackgroundColor = selected
                updateTextToolbarState()
                schedulePreviewRender()
            }
        }
    }

    private fun showTextFontPopup() {
        val options = listOf(
            getString(R.string.photo_editor_font_bold) to TextFontFamily.BOLD_DEFAULT,
            getString(R.string.photo_editor_font_sans) to TextFontFamily.SANS,
            getString(R.string.photo_editor_font_serif) to TextFontFamily.SERIF,
            getString(R.string.photo_editor_font_mono) to TextFontFamily.MONO
        )
        val labels = options.map { it.first }.toTypedArray()
        val selectedIndex = options.indexOfFirst { it.second == textFontFamily }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.photo_editor_text_font)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                val selected = options[which].second
                if (textFontFamily != selected) {
                    pushHistoryState()
                    textFontFamily = selected
                    updateTextToolbarState()
                    schedulePreviewRender()
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun updateTextToolbarState() {
        binding.btnTextSize.text = overlayTextSizeSp.toInt().toString()
        binding.btnTextColor.text = buildTextColorLabel(overlayTextColor)
        binding.btnTextBackground.text = buildTextBackgroundLabel(overlayTextBackgroundColor)
        updateToggleButtonState(binding.btnTextBold, textBold)
        updateToggleButtonState(binding.btnTextItalic, textItalic)
        val currentFontName = when (textFontFamily) {
            TextFontFamily.BOLD_DEFAULT -> getString(R.string.photo_editor_font_bold)
            TextFontFamily.SANS -> getString(R.string.photo_editor_font_sans)
            TextFontFamily.SERIF -> getString(R.string.photo_editor_font_serif)
            TextFontFamily.MONO -> getString(R.string.photo_editor_font_mono)
        }
        binding.btnTextFont.contentDescription = "${getString(R.string.photo_editor_text_font)}: $currentFontName"
    }

    private fun buildTextColorLabel(color: Int): SpannableString {
        return SpannableString("A").apply {
            setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun buildTextBackgroundLabel(backgroundColor: Int): SpannableString {
        val baseTextColor = ContextCompat.getColor(this, R.color.photo_editor_tool_on_surface)
        val lineColor = if (backgroundColor == Color.TRANSPARENT) {
            ContextCompat.getColor(this, R.color.photo_editor_toolbar_stroke)
        } else {
            backgroundColor
        }

        return SpannableString("A_").apply {
            setSpan(ForegroundColorSpan(baseTextColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(lineColor), 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun updateToggleButtonState(button: MaterialButton, isActive: Boolean) {
        val activeStrokeColor = ContextCompat.getColor(this, R.color.accent_blue)
        val inactiveStrokeColor = ContextCompat.getColor(this, R.color.photo_editor_toolbar_stroke)
        button.strokeColor = ColorStateList.valueOf(if (isActive) activeStrokeColor else inactiveStrokeColor)
        button.strokeWidth = if (isActive) dp(2f).toInt() else dp(1f).toInt()
        button.alpha = 1f
    }

    private fun textColorOptions(): List<Int> {
        return listOf(
            Color.WHITE,
            Color.BLACK,
            Color.RED,
            Color.GREEN,
            Color.BLUE,
            Color.YELLOW
        )
    }

    private fun textBackgroundOptions(): List<Int> {
        return listOf(
            Color.TRANSPARENT,
            Color.WHITE,
            Color.BLACK,
            Color.RED,
            Color.GREEN,
            Color.BLUE,
            Color.YELLOW
        )
    }

    private fun showColorPalette(anchor: View, colors: List<Int>, onColorSelected: (Int) -> Unit) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(12f)
                setColor(Color.parseColor("#EE222222"))
                setStroke(dp(1f).toInt(), Color.parseColor("#55FFFFFF"))
            }
        }

        val popup = PopupWindow(
            content,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = dp(8f)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        colors.forEachIndexed { index, color ->
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(26f).toInt(), dp(26f).toInt()).apply {
                    if (index > 0) marginStart = dp(8f).toInt()
                }
                background = createSwatchDrawable(color)
                setOnClickListener {
                    onColorSelected(color)
                    popup.dismiss()
                }
            }
            content.addView(swatch)
        }

        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = content.measuredWidth
        val popupHeight = content.measuredHeight
        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)

        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val desiredX = anchorLocation[0] + (anchor.width - popupWidth) / 2
        val clampedX = desiredX.coerceIn(dp(8f).toInt(), (screenWidth - popupWidth - dp(8f).toInt()).coerceAtLeast(0))
        val spaceBelow = screenHeight - (anchorLocation[1] + anchor.height)
        val spaceAbove = anchorLocation[1]

        if (spaceBelow >= popupHeight + dp(10f).toInt()) {
            popup.showAtLocation(anchor.rootView, Gravity.NO_GRAVITY, clampedX, anchorLocation[1] + anchor.height + dp(6f).toInt())
        } else if (spaceAbove >= popupHeight + dp(10f).toInt()) {
            popup.showAtLocation(anchor.rootView, Gravity.NO_GRAVITY, clampedX, anchorLocation[1] - popupHeight - dp(6f).toInt())
        } else {
            popup.showAtLocation(anchor.rootView, Gravity.NO_GRAVITY, clampedX, (screenHeight - popupHeight) / 2)
        }
    }

    private fun createSwatchDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (color == Color.TRANSPARENT) {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2f).toInt(), Color.WHITE)
            } else {
                setColor(color)
                setStroke(dp(1f).toInt(), Color.parseColor("#88FFFFFF"))
            }
        }
    }

    private fun setupTextDragOnPhoto() {
        binding.editorImageView.setOnExternalTouchHandler { event ->
            if (event.pointerCount > 1) {
                isTextDragging = false
                textDragCandidate = false
                hideTextDragIndicator()
                return@setOnExternalTouchHandler false
            }
            if (!canDragTextFromPhoto()) {
                isTextDragging = false
                textDragCandidate = false
                hideTextDragIndicator()
                return@setOnExternalTouchHandler false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val textBounds = calculateOverlayTextBoundsInView(expandTouch = true)
                    if (textBounds == null || !textBounds.contains(event.x, event.y)) {
                        isTextDragging = false
                        textDragCandidate = false
                        hideTextDragIndicator()
                        return@setOnExternalTouchHandler false
                    }
                    textDragStartX = event.x
                    textDragStartY = event.y
                    textDragStartTime = event.eventTime
                    textDragCandidate = true
                    isTextDragging = false
                    return@setOnExternalTouchHandler true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!textDragCandidate && !isTextDragging) return@setOnExternalTouchHandler false
                    if (!isTextDragging && textDragCandidate) {
                        val heldLongEnough = (event.eventTime - textDragStartTime) >= longPressTimeoutMs
                        val movedTooFar = hypot(event.x - textDragStartX, event.y - textDragStartY) > touchSlopPx * 2f
                        if (movedTooFar) {
                            textDragCandidate = false
                            return@setOnExternalTouchHandler false
                        }
                        if (!heldLongEnough) {
                            return@setOnExternalTouchHandler true
                        }
                        isTextDragging = true
                        textDragCandidate = false
                        pushHistoryState()
                        showTextDragIndicator()
                    }
                    if (isTextDragging) {
                        moveOverlayTextToTouch(event.x, event.y)
                        updateTextDragIndicator()
                    }
                    return@setOnExternalTouchHandler true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val handled = isTextDragging
                    isTextDragging = false
                    textDragCandidate = false
                    hideTextDragIndicator()
                    return@setOnExternalTouchHandler handled
                }
            }
            false
        }
    }

    private fun canDragTextFromPhoto(): Boolean {
        return activeCategory == ToolCategory.TEXT && overlayText.isNotBlank() && binding.cropOverlay.visibility != View.VISIBLE
    }

    private fun showTextDragIndicator() {
        updateTextDragIndicator()
        binding.textDragIndicator.visibility = View.VISIBLE
    }

    private fun updateTextDragIndicator() {
        val bounds = calculateOverlayTextBoundsInView(expandTouch = false) ?: return
        val width = bounds.width().toInt().coerceAtLeast(1)
        val height = bounds.height().toInt().coerceAtLeast(1)
        val left = bounds.left.toInt().coerceAtLeast(0)
        val top = bounds.top.toInt().coerceAtLeast(0)
        val params = binding.textDragIndicator.layoutParams as FrameLayout.LayoutParams
        params.width = width
        params.height = height
        params.leftMargin = left
        params.topMargin = top
        binding.textDragIndicator.layoutParams = params
        if (binding.textDragIndicator.visibility != View.VISIBLE) {
            binding.textDragIndicator.visibility = View.VISIBLE
        }
    }

    private fun hideTextDragIndicator() {
        binding.textDragIndicator.visibility = View.GONE
    }

    private fun calculateOverlayTextBoundsInView(expandTouch: Boolean): RectF? {
        if (overlayText.isBlank()) return null
        val drawable = binding.editorImageView.drawable ?: return null
        val imageRect = calculateDisplayedImageRect() ?: return null
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return null

        val textSizePx = overlayTextSizeSp * resources.displayMetrics.scaledDensity
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            typeface = resolveOverlayTypeface()
            textAlign = Paint.Align.CENTER
        }

        val textX = drawable.intrinsicWidth * (overlayTextXPercent / 100f)
        var baselineY = drawable.intrinsicHeight * (overlayTextYPercent / 100f)
        val lineHeight = paint.fontSpacing
        val metrics = paint.fontMetrics
        var unionRect: RectF? = null

        overlayText.lines().forEach { line ->
            val width = paint.measureText(line).coerceAtLeast(dp(12f)) + if (overlayTextBackgroundColor != Color.TRANSPARENT) dp(12f) else 0f
            val left = textX - width / 2f
            val right = textX + width / 2f
            val top = baselineY + metrics.ascent - if (overlayTextBackgroundColor != Color.TRANSPARENT) dp(3f) else 0f
            val bottom = baselineY + metrics.descent + if (overlayTextBackgroundColor != Color.TRANSPARENT) dp(3f) else 0f
            val lineRect = RectF(left, top, right, bottom)
            if (unionRect == null) {
                unionRect = RectF(lineRect)
            } else {
                unionRect?.union(lineRect)
            }
            baselineY += lineHeight
        }

        val textRectPx = unionRect ?: return null
        val mapped = RectF(
            imageRect.left + (textRectPx.left / drawable.intrinsicWidth) * imageRect.width(),
            imageRect.top + (textRectPx.top / drawable.intrinsicHeight) * imageRect.height(),
            imageRect.left + (textRectPx.right / drawable.intrinsicWidth) * imageRect.width(),
            imageRect.top + (textRectPx.bottom / drawable.intrinsicHeight) * imageRect.height()
        )

        val inset = if (expandTouch) dp(14f) else dp(6f)
        mapped.inset(-inset, -inset)
        mapped.intersect(0f, 0f, binding.editorImageView.width.toFloat(), binding.editorImageView.height.toFloat())
        return mapped
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun moveOverlayTextToTouch(x: Float, y: Float) {
        val imageRect = calculateDisplayedImageRect() ?: return
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) return

        val clampedX = x.coerceIn(imageRect.left, imageRect.right)
        val clampedY = y.coerceIn(imageRect.top, imageRect.bottom)
        overlayTextXPercent = (((clampedX - imageRect.left) / imageRect.width()) * 100f).roundToInt().coerceIn(0, 100)
        overlayTextYPercent = (((clampedY - imageRect.top) / imageRect.height()) * 100f).roundToInt().coerceIn(0, 100)
        schedulePreviewRender()
    }

    private fun simpleSeekListener(
        onStartChange: (() -> Unit)? = null,
        onChange: (Int) -> Unit
    ): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (isRestoringState) return
                onChange(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (!isRestoringState) {
                    onStartChange?.invoke()
                }
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
    }

    private fun loadSourceBitmap() {
        val uri = inputUri ?: return
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                decodeScaledBitmap(uri, maxSize = 2048)
            }
            if (bitmap == null) {
                Toast.makeText(this@PhotoEditorActivity, R.string.photo_editor_open_failed, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            sourceBitmap = bitmap
            undoHistory.clear()
            redoHistory.clear()
            updateHistoryButtonsState()
            appliedCropRectNormalized = null
            pendingCropRectNormalized = null
            resizeWidth = bitmap.width
            resizeHeight = bitmap.height
            updateResizeInputFields(bitmap.width, bitmap.height)
            updateTextToolbarState()
            initialEditorState = captureEditorState()
            schedulePreviewRender()
        }
    }

    private fun applyResizeFromInputs() {
        val source = sourceBitmap ?: return
        val targetWidth = binding.editResizeWidth.text?.toString()?.toIntOrNull()
        val targetHeightInput = binding.editResizeHeight.text?.toString()?.toIntOrNull()
        if (targetWidth == null || targetWidth <= 0) {
            Toast.makeText(this, R.string.photo_editor_resize_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        val targetHeight = if (binding.checkboxKeepAspect.isChecked) {
            ((targetWidth.toFloat() / source.width.toFloat()) * source.height).toInt().coerceAtLeast(1)
        } else {
            targetHeightInput?.takeIf { it > 0 }
        }

        if (targetHeight == null) {
            Toast.makeText(this, R.string.photo_editor_resize_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        pushHistoryState()

        resizeWidth = targetWidth.coerceAtMost(8192)
        resizeHeight = targetHeight.coerceAtMost(8192)
        updateResizeInputFields(resizeWidth, resizeHeight)
        schedulePreviewRender(includeResize = true)
        Toast.makeText(this, getString(R.string.photo_editor_resize_applied, resizeWidth, resizeHeight), Toast.LENGTH_SHORT).show()
    }

    private fun schedulePreviewRender(includeResize: Boolean = false) {
        val source = sourceBitmap ?: return
        previewRenderJob?.cancel()
        previewRenderJob = lifecycleScope.launch {
            val rendered = withContext(Dispatchers.Default) {
                applyEditsToBitmap(source, includeResize = includeResize)
            }
            if (rendered.isRecycled) return@launch
            previewBitmap?.takeIf { it !== source && !it.isRecycled }?.recycle()
            previewBitmap = rendered
            binding.editorImageView.rotation = 0f
            binding.editorImageView.scaleX = 1f
            binding.editorImageView.scaleY = 1f
            binding.editorImageView.colorFilter = null
            binding.editorImageView.setImageBitmap(rendered)
            binding.editorImageView.post { syncCropOverlayBounds() }
        }
    }

    private fun updateCropUiForCategory(category: ToolCategory) {
        if (category != ToolCategory.CROP) {
            binding.cropOverlay.visibility = View.GONE
            return
        }

        binding.cropOverlay.visibility = View.VISIBLE
        syncCropOverlayBounds()
        val selectedRect = pendingCropRectNormalized ?: appliedCropRectNormalized
        if (selectedRect == null) {
            binding.cropOverlay.resetToBounds()
        } else {
            val overlayRect = normalizedToOverlayRect(selectedRect)
            if (overlayRect == null) {
                binding.cropOverlay.resetToBounds()
            } else {
                binding.cropOverlay.setCropRect(overlayRect)
            }
        }
    }

    private fun cancelCropSelection() {
        pendingCropRectNormalized = appliedCropRectNormalized?.let { RectF(it) }
        if (activeCategory == ToolCategory.CROP) {
            val restored = pendingCropRectNormalized?.let { normalizedToOverlayRect(it) }
            if (restored == null) {
                binding.cropOverlay.resetToBounds()
            } else {
                binding.cropOverlay.setCropRect(restored)
            }
            setActiveCategory(ToolCategory.TRANSFORM)
        }
    }

    private fun commitCropSelection() {
        if (activeCategory != ToolCategory.CROP) return
        val selected = binding.cropOverlay.getCropRect()?.let { overlayRectToNormalizedCrop(it) }
        if (!sameRect(selected, appliedCropRectNormalized)) {
            pushHistoryState()
        }
        appliedCropRectNormalized = selected
        pendingCropRectNormalized = appliedCropRectNormalized?.let { RectF(it) }
        schedulePreviewRender()
        setActiveCategory(ToolCategory.TRANSFORM)
    }

    private fun undoLastStep() {
        if (undoHistory.isEmpty()) return
        redoHistory.addLast(captureEditorState())
        val previous = undoHistory.removeLast()
        restoreEditorState(previous)
        updateHistoryButtonsState()
    }

    private fun redoLastStep() {
        if (redoHistory.isEmpty()) return
        undoHistory.addLast(captureEditorState())
        val next = redoHistory.removeLast()
        restoreEditorState(next)
        updateHistoryButtonsState()
    }

    private fun pushHistoryState() {
        if (isRestoringState || sourceBitmap == null) return
        val current = captureEditorState()
        if (undoHistory.lastOrNull() == current) return
        undoHistory.addLast(current)
        redoHistory.clear()
        while (undoHistory.size > 100) {
            undoHistory.removeFirst()
        }
        updateHistoryButtonsState()
    }

    private fun captureEditorState(): EditorState {
        return EditorState(
            rotationSteps = rotationSteps,
            flipHorizontal = flipHorizontal,
            flipVertical = flipVertical,
            brightnessOffset = brightnessOffset,
            contrast = contrast,
            saturation = saturation,
            resizeWidth = resizeWidth,
            resizeHeight = resizeHeight,
            cropRectNormalized = appliedCropRectNormalized?.let { RectF(it) },
            overlayText = overlayText,
            overlayTextSizeSp = overlayTextSizeSp,
            overlayTextColor = overlayTextColor,
            overlayTextBackgroundColor = overlayTextBackgroundColor,
            textFontFamily = textFontFamily,
            textBold = textBold,
            textItalic = textItalic,
            overlayTextXPercent = overlayTextXPercent,
            overlayTextYPercent = overlayTextYPercent
        )
    }

    private fun restoreEditorState(state: EditorState) {
        isRestoringState = true
        rotationSteps = state.rotationSteps
        flipHorizontal = state.flipHorizontal
        flipVertical = state.flipVertical
        brightnessOffset = state.brightnessOffset
        contrast = state.contrast
        saturation = state.saturation
        resizeWidth = state.resizeWidth
        resizeHeight = state.resizeHeight
        appliedCropRectNormalized = state.cropRectNormalized?.let { RectF(it) }
        pendingCropRectNormalized = appliedCropRectNormalized?.let { RectF(it) }
        overlayText = state.overlayText
        overlayTextSizeSp = state.overlayTextSizeSp
        overlayTextColor = state.overlayTextColor
        overlayTextBackgroundColor = state.overlayTextBackgroundColor
        textFontFamily = state.textFontFamily
        textBold = state.textBold
        textItalic = state.textItalic
        overlayTextXPercent = state.overlayTextXPercent
        overlayTextYPercent = state.overlayTextYPercent

        binding.seekBrightness.progress = (brightnessOffset + 100f).toInt().coerceIn(0, 200)
        binding.seekContrast.progress = (contrast * 100f).toInt().coerceIn(0, 200)
        binding.seekSaturation.progress = (saturation * 100f).toInt().coerceIn(0, 200)
        updateAdjustValueLabels()
        updateResizeInputFields(resizeWidth, resizeHeight)
        binding.editOverlayText.setText(overlayText)
        updateTextToolbarState()

        isRestoringState = false
        schedulePreviewRender()
    }

    private fun updateHistoryButtonsState() {
        binding.btnUndo.isEnabled = undoHistory.isNotEmpty()
        binding.btnUndo.alpha = if (binding.btnUndo.isEnabled) 1f else 0.4f
        binding.btnRedo.isEnabled = redoHistory.isNotEmpty()
        binding.btnRedo.alpha = if (binding.btnRedo.isEnabled) 1f else 0.4f
    }

    private fun resolveOverlayTypeface(): Typeface {
        val family = when (textFontFamily) {
            TextFontFamily.BOLD_DEFAULT -> Typeface.DEFAULT
            TextFontFamily.SANS -> Typeface.SANS_SERIF
            TextFontFamily.SERIF -> Typeface.SERIF
            TextFontFamily.MONO -> Typeface.MONOSPACE
        }
        val style = when {
            textBold && textItalic -> Typeface.BOLD_ITALIC
            textBold -> Typeface.BOLD
            textItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(family, style)
    }

    private fun sameRect(a: RectF?, b: RectF?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom
    }

    private fun syncCropOverlayBounds() {
        if (binding.editorImageView.width <= 0 || binding.editorImageView.height <= 0) return
        if (binding.cropOverlay.width <= 0 || binding.cropOverlay.height <= 0) return
        val imageRect = getDisplayedImageRect(binding.editorImageView) ?: return
        val imageBoundsInOverlay = imageRectToOverlayRect(imageRect)
        binding.cropOverlay.setImageBounds(imageBoundsInOverlay)
        if (activeCategory == ToolCategory.CROP) {
            val selectedRect = pendingCropRectNormalized ?: appliedCropRectNormalized
            if (selectedRect == null) {
                binding.cropOverlay.resetToBounds()
            } else {
                val overlayRect = normalizedToOverlayRect(selectedRect)
                if (overlayRect == null) {
                    binding.cropOverlay.resetToBounds()
                } else {
                    binding.cropOverlay.setCropRect(overlayRect)
                }
            }
        }
    }

    // ZoomImageView и CropOverlayView могут иметь разные локальные координаты
    // (padding родителя, insets, translation), поэтому маппим прямоугольники явно.
    private fun getDisplayedImageRect(imageView: com.example.galerinio.presentation.ui.widget.ZoomImageView): RectF? {
        return imageView.getDisplayedImageRect()
    }

    private fun imageRectToOverlayRect(rectInImageView: RectF): RectF {
        val dx = binding.editorImageView.x - binding.cropOverlay.x
        val dy = binding.editorImageView.y - binding.cropOverlay.y
        return RectF(
            rectInImageView.left + dx,
            rectInImageView.top + dy,
            rectInImageView.right + dx,
            rectInImageView.bottom + dy
        )
    }

    private fun overlayRectToImageRect(rectInOverlay: RectF): RectF {
        val dx = binding.cropOverlay.x - binding.editorImageView.x
        val dy = binding.cropOverlay.y - binding.editorImageView.y
        return RectF(
            rectInOverlay.left + dx,
            rectInOverlay.top + dy,
            rectInOverlay.right + dx,
            rectInOverlay.bottom + dy
        )
    }

    private fun overlayRectToNormalizedCrop(cropRectView: RectF): RectF? {
        val imageView = binding.editorImageView
        val drawable = imageView.drawable ?: return null
        val bitmapWidth = drawable.intrinsicWidth.toFloat().takeIf { it > 0f } ?: return null
        val bitmapHeight = drawable.intrinsicHeight.toFloat().takeIf { it > 0f } ?: return null

        val rectInImageView = overlayRectToImageRect(cropRectView)

        val inverse = Matrix()
        if (!imageView.getContentMatrixCopy().invert(inverse)) return null
        inverse.mapRect(rectInImageView)

        rectInImageView.left = rectInImageView.left.coerceIn(0f, bitmapWidth)
        rectInImageView.top = rectInImageView.top.coerceIn(0f, bitmapHeight)
        rectInImageView.right = rectInImageView.right.coerceIn(0f, bitmapWidth)
        rectInImageView.bottom = rectInImageView.bottom.coerceIn(0f, bitmapHeight)
        if (rectInImageView.width() <= 0f || rectInImageView.height() <= 0f) return null

        return RectF(
            rectInImageView.left / bitmapWidth,
            rectInImageView.top / bitmapHeight,
            rectInImageView.right / bitmapWidth,
            rectInImageView.bottom / bitmapHeight
        )
    }

    private fun normalizedToOverlayRect(normalized: RectF): RectF? {
        val imageView = binding.editorImageView
        val drawable = imageView.drawable ?: return null
        val bitmapWidth = drawable.intrinsicWidth.toFloat().takeIf { it > 0f } ?: return null
        val bitmapHeight = drawable.intrinsicHeight.toFloat().takeIf { it > 0f } ?: return null

        val bitmapRect = RectF(
            normalized.left.coerceIn(0f, 1f) * bitmapWidth,
            normalized.top.coerceIn(0f, 1f) * bitmapHeight,
            normalized.right.coerceIn(0f, 1f) * bitmapWidth,
            normalized.bottom.coerceIn(0f, 1f) * bitmapHeight
        )
        if (bitmapRect.width() <= 0f || bitmapRect.height() <= 0f) return null

        val matrix = imageView.getContentMatrixCopy()
        matrix.mapRect(bitmapRect)
        return imageRectToOverlayRect(bitmapRect)
    }


    private fun calculateDisplayedImageRect(): RectF? {
        return binding.editorImageView.getDisplayedImageRect()
    }

    private fun buildColorMatrix(): ColorMatrix {
        val saturationMatrix = ColorMatrix().apply { setSaturation(saturation) }
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightnessOffset,
                0f, contrast, 0f, 0f, brightnessOffset,
                0f, 0f, contrast, 0f, brightnessOffset,
                0f, 0f, 0f, 1f, 0f
            )
        )
        saturationMatrix.postConcat(contrastMatrix)
        return saturationMatrix
    }

    private fun saveEditedImage() {
        val original = sourceBitmap ?: return
        lifecycleScope.launch {
            binding.btnSave.isEnabled = false
            val outputUri = withContext(Dispatchers.IO) {
                val edited = applyEditsToBitmap(original, includeResize = true)
                saveBitmapToMediaStore(edited)
            }
            binding.btnSave.isEnabled = true

            if (outputUri == null) {
                Toast.makeText(this@PhotoEditorActivity, R.string.photo_editor_save_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }

            setResult(
                RESULT_OK,
                Intent().apply {
                    putExtra(EXTRA_OUTPUT_URI, outputUri.toString())
                }
            )
            Toast.makeText(this@PhotoEditorActivity, R.string.photo_editor_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun applyEditsToBitmap(source: Bitmap, includeResize: Boolean): Bitmap {
        val matrix = Matrix().apply {
            postScale(
                if (flipHorizontal) -1f else 1f,
                if (flipVertical) -1f else 1f,
                source.width / 2f,
                source.height / 2f
            )
            if (rotationSteps != 0) {
                postRotate(rotationSteps * 90f, source.width / 2f, source.height / 2f)
            }
        }

        val transformed = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        val colorOutput = Bitmap.createBitmap(transformed.width, transformed.height, Bitmap.Config.ARGB_8888)
        val colorCanvas = Canvas(colorOutput)
        val paint = android.graphics.Paint().apply {
            isFilterBitmap = true
            colorFilter = ColorMatrixColorFilter(buildColorMatrix())
        }
        colorCanvas.drawBitmap(transformed, 0f, 0f, paint)
        if (transformed !== source) {
            transformed.recycle()
        }

        val cropped = applyCropSelection(colorOutput)
        if (cropped !== colorOutput) {
            colorOutput.recycle()
        }

        val withText = applyTextOverlay(cropped)
        if (withText !== cropped) {
            cropped.recycle()
        }

        if (!includeResize || resizeWidth <= 0 || resizeHeight <= 0) {
            return withText
        }
        if (withText.width == resizeWidth && withText.height == resizeHeight) {
            return withText
        }

        val resized = Bitmap.createScaledBitmap(withText, resizeWidth, resizeHeight, true)
        if (resized !== withText) {
            withText.recycle()
        }
        return resized
    }

    private fun applyCropSelection(source: Bitmap): Bitmap {
        val crop = appliedCropRectNormalized ?: return source
        if (crop.width() <= 0f || crop.height() <= 0f) return source

        val left = (crop.left * source.width).toInt().coerceIn(0, source.width - 1)
        val top = (crop.top * source.height).toInt().coerceIn(0, source.height - 1)
        val right = (crop.right * source.width).toInt().coerceIn(left + 1, source.width)
        val bottom = (crop.bottom * source.height).toInt().coerceIn(top + 1, source.height)

        val width = right - left
        val height = bottom - top
        if (width >= source.width && height >= source.height) return source
        return Bitmap.createBitmap(source, left, top, width, height)
    }

    private fun applyTextOverlay(source: Bitmap): Bitmap {
        if (overlayText.isBlank()) return source

        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val textSizePx = overlayTextSizeSp * resources.displayMetrics.scaledDensity
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = overlayTextColor
            textSize = textSizePx
            typeface = resolveOverlayTypeface()
            textAlign = Paint.Align.CENTER
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = overlayTextBackgroundColor
            style = Paint.Style.FILL
        }

        val x = (output.width * (overlayTextXPercent / 100f)).coerceIn(0f, output.width.toFloat())
        var y = (output.height * (overlayTextYPercent / 100f)).coerceIn(0f, output.height.toFloat())
        val lineHeight = textPaint.fontSpacing
        val linePaddingX = dp(6f)
        val linePaddingY = dp(2f)
        overlayText.lines().forEach { line ->
            if (overlayTextBackgroundColor != Color.TRANSPARENT) {
                val lineWidth = textPaint.measureText(line).coerceAtLeast(dp(12f))
                val metrics = textPaint.fontMetrics
                val top = y + metrics.ascent - linePaddingY
                val bottom = y + metrics.descent + linePaddingY
                canvas.drawRoundRect(
                    x - lineWidth / 2f - linePaddingX,
                    top,
                    x + lineWidth / 2f + linePaddingX,
                    bottom,
                    dp(6f),
                    dp(6f),
                    bgPaint
                )
            }
            canvas.drawText(line, x, y, textPaint)
            y += lineHeight
        }
        return output
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap): Uri? {
        val resolver = contentResolver
        val fileName = "edited_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Galerinio")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(outputUri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(outputUri, values, null, null)
            }
            outputUri
        } catch (_: IOException) {
            resolver.delete(outputUri, null, null)
            null
        }
    }

    private fun decodeScaledBitmap(uri: Uri, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSize)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var inSampleSize = 1
        var currentWidth = width
        var currentHeight = height

        while (currentWidth > maxSize || currentHeight > maxSize) {
            currentWidth /= 2
            currentHeight /= 2
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    override fun onDestroy() {
        previewRenderJob?.cancel()
        previewBitmap?.takeIf { it !== sourceBitmap && !it.isRecycled }?.recycle()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INPUT_URI = "extra_input_uri"
        const val EXTRA_OUTPUT_URI = "extra_output_uri"
    }
}

