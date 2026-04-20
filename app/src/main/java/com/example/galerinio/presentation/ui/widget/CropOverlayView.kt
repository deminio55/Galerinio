package com.example.galerinio.presentation.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val imageBounds = RectF()
    private val cropRect = RectF()

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D9FFFFFF")
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val handleRadius = dp(8f)
    private val touchPadding = dp(22f)
    private val minCropSize = dp(64f)

    private var dragMode = DragMode.NONE
    private var lastX = 0f
    private var lastY = 0f

    var onCropDoubleTap: (() -> Unit)? = null

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (cropRect.contains(e.x, e.y)) {
                    onCropDoubleTap?.invoke()
                    return true
                }
                return false
            }
        }
    )

    fun setImageBounds(bounds: RectF) {
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        imageBounds.set(bounds)
        if (cropRect.isEmpty) {
            resetCropRect()
        } else {
            clampRect(cropRect)
        }
        invalidate()
    }

    fun resetToImageBounds() {
        if (imageBounds.isEmpty) return
        cropRect.set(imageBounds)
        invalidate()
    }

    fun resetToBounds() {
        resetToImageBounds()
    }

    fun setCropRect(rect: RectF?) {
        if (rect == null || imageBounds.isEmpty) {
            resetCropRect()
            return
        }
        cropRect.set(rect)
        clampRect(cropRect)
        invalidate()
    }

    fun getCropRect(): RectF? {
        if (imageBounds.isEmpty || cropRect.isEmpty) return null
        return RectF(cropRect)
    }

    fun setCropRectNormalized(normalized: RectF?) {
        if (normalized == null || imageBounds.isEmpty) {
            resetCropRect()
            return
        }
        cropRect.set(
            imageBounds.left + normalized.left * imageBounds.width(),
            imageBounds.top + normalized.top * imageBounds.height(),
            imageBounds.left + normalized.right * imageBounds.width(),
            imageBounds.top + normalized.bottom * imageBounds.height()
        )
        clampRect(cropRect)
        invalidate()
    }

    fun getCropRectNormalized(): RectF? {
        if (imageBounds.isEmpty || cropRect.isEmpty) return null
        return RectF(
            ((cropRect.left - imageBounds.left) / imageBounds.width()).coerceIn(0f, 1f),
            ((cropRect.top - imageBounds.top) / imageBounds.height()).coerceIn(0f, 1f),
            ((cropRect.right - imageBounds.left) / imageBounds.width()).coerceIn(0f, 1f),
            ((cropRect.bottom - imageBounds.top) / imageBounds.height()).coerceIn(0f, 1f)
        )
    }

    fun resetCropRect() {
        if (imageBounds.isEmpty) return
        val dx = imageBounds.width() * 0.1f
        val dy = imageBounds.height() * 0.1f
        cropRect.set(
            imageBounds.left + dx,
            imageBounds.top + dy,
            imageBounds.right - dx,
            imageBounds.bottom - dy
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageBounds.isEmpty || cropRect.isEmpty) return

        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, dimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, dimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), dimPaint)

        canvas.drawRect(cropRect, borderPaint)

        val stepX = cropRect.width() / 3f
        val stepY = cropRect.height() / 3f
        canvas.drawLine(cropRect.left + stepX, cropRect.top, cropRect.left + stepX, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left + stepX * 2f, cropRect.top, cropRect.left + stepX * 2f, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + stepY, cropRect.right, cropRect.top + stepY, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + stepY * 2f, cropRect.right, cropRect.top + stepY * 2f, gridPaint)

        drawHandle(canvas, cropRect.left, cropRect.top)
        drawHandle(canvas, cropRect.right, cropRect.top)
        drawHandle(canvas, cropRect.left, cropRect.bottom)
        drawHandle(canvas, cropRect.right, cropRect.bottom)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handleRadius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageBounds.isEmpty || cropRect.isEmpty) return false
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = detectDragMode(event.x, event.y)
                if (dragMode == DragMode.NONE) return false
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (dragMode != DragMode.NONE) {
                    updateCropRect(dragMode, dx, dy)
                    lastX = event.x
                    lastY = event.y
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                }
                dragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun detectDragMode(x: Float, y: Float): DragMode {
        if (near(x, y, cropRect.left, cropRect.top)) return DragMode.TOP_LEFT
        if (near(x, y, cropRect.right, cropRect.top)) return DragMode.TOP_RIGHT
        if (near(x, y, cropRect.left, cropRect.bottom)) return DragMode.BOTTOM_LEFT
        if (near(x, y, cropRect.right, cropRect.bottom)) return DragMode.BOTTOM_RIGHT
        if (cropRect.contains(x, y)) return DragMode.MOVE
        return DragMode.NONE
    }

    private fun updateCropRect(mode: DragMode, dx: Float, dy: Float) {
        when (mode) {
            DragMode.MOVE -> {
                cropRect.offset(dx, dy)
                if (cropRect.left < imageBounds.left) cropRect.offset(imageBounds.left - cropRect.left, 0f)
                if (cropRect.top < imageBounds.top) cropRect.offset(0f, imageBounds.top - cropRect.top)
                if (cropRect.right > imageBounds.right) cropRect.offset(imageBounds.right - cropRect.right, 0f)
                if (cropRect.bottom > imageBounds.bottom) cropRect.offset(0f, imageBounds.bottom - cropRect.bottom)
            }

            DragMode.TOP_LEFT -> {
                cropRect.left = (cropRect.left + dx).coerceIn(imageBounds.left, cropRect.right - minCropSize)
                cropRect.top = (cropRect.top + dy).coerceIn(imageBounds.top, cropRect.bottom - minCropSize)
            }

            DragMode.TOP_RIGHT -> {
                cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minCropSize, imageBounds.right)
                cropRect.top = (cropRect.top + dy).coerceIn(imageBounds.top, cropRect.bottom - minCropSize)
            }

            DragMode.BOTTOM_LEFT -> {
                cropRect.left = (cropRect.left + dx).coerceIn(imageBounds.left, cropRect.right - minCropSize)
                cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minCropSize, imageBounds.bottom)
            }

            DragMode.BOTTOM_RIGHT -> {
                cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minCropSize, imageBounds.right)
                cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minCropSize, imageBounds.bottom)
            }

            DragMode.NONE -> Unit
        }
        clampRect(cropRect)
    }

    private fun clampRect(rect: RectF) {
        val minWidth = minCropSize.coerceAtMost(imageBounds.width())
        val minHeight = minCropSize.coerceAtMost(imageBounds.height())
        rect.left = rect.left.coerceIn(imageBounds.left, imageBounds.right - minWidth)
        rect.top = rect.top.coerceIn(imageBounds.top, imageBounds.bottom - minHeight)
        rect.right = rect.right.coerceIn(rect.left + minWidth, imageBounds.right)
        rect.bottom = rect.bottom.coerceIn(rect.top + minHeight, imageBounds.bottom)
    }

    private fun near(x: Float, y: Float, tx: Float, ty: Float): Boolean {
        return abs(x - tx) <= touchPadding && abs(y - ty) <= touchPadding
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private enum class DragMode {
        NONE,
        MOVE,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }
}

