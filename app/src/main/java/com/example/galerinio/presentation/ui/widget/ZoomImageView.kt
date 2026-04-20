package com.example.galerinio.presentation.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.min

class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    // Own matrix — never rely on getImageMatrix() which returns null
    // when matrix is identity (ScaleType.MATRIX + identity → mDrawMatrix = null)
    private val mat = Matrix()
    private val matTmp = FloatArray(9)

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, TapListener())

    private var minScale = 1f
    private var maxScale = 5f
    private var currentScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var zoomEnabled = true
    private var scaleAnimator: ValueAnimator? = null
    private var onSingleTapListener: (() -> Unit)? = null
    private var externalTouchHandler: ((MotionEvent) -> Boolean)? = null

    init {
        // Force MATRIX scale type so our mat is always used for drawing
        super.setScaleType(ScaleType.MATRIX)
        super.setImageMatrix(mat)
        isClickable = true
        isFocusable = true
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Always keep MATRIX scale type regardless of XML attribute */
    override fun setScaleType(scaleType: ScaleType) {
        super.setScaleType(ScaleType.MATRIX)
    }

    fun setZoomEnabled(enabled: Boolean) {
        zoomEnabled = enabled
        if (!enabled) {
            scaleAnimator?.cancel()
            post { fitImageToView() }
        }
    }

    fun setOnSingleTapListener(listener: (() -> Unit)?) {
        onSingleTapListener = listener
    }

    fun setOnExternalTouchHandler(handler: ((MotionEvent) -> Boolean)?) {
        externalTouchHandler = handler
    }

    fun resetZoom() {
        scaleAnimator?.cancel()
        if (width > 0 && height > 0) {
            fitImageToView()
        } else {
            post { fitImageToView() }
        }
    }

    fun isZoomActive(): Boolean {
        return zoomEnabled && (currentScale > minScale + 0.01f || scaleDetector.isInProgress)
    }

    /** Returns the currently displayed drawable rect in this view's coordinates. */
    fun getDisplayedImageRect(): RectF? {
        val d = drawable ?: return null
        val iw = d.intrinsicWidth.toFloat().takeIf { it > 0f } ?: return null
        val ih = d.intrinsicHeight.toFloat().takeIf { it > 0f } ?: return null
        val rect = RectF(0f, 0f, iw, ih)
        mat.mapRect(rect)
        // Keep rect inside drawable content area (important when padding is used).
        val content = RectF(contentLeftF(), contentTopF(), contentRightF(), contentBottomF())
        if (!rect.intersect(content)) return null
        return rect
    }

    fun getContentMatrixCopy(): Matrix = Matrix(mat)

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        // If the view is already laid out, compute the fit-matrix synchronously so
        // the drawable is NEVER rendered with a stale (wrong-dimension) matrix —
        // not even for a single frame.  Async post{} is kept only as a fallback for
        // the very first bind that happens before the view has been measured.
        if (width > 0 && height > 0) {
            fitImageToView()
        } else {
            post { fitImageToView() }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) fitImageToView()
    }

    // ── Touch handling ─────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (externalTouchHandler?.invoke(event) == true) {
            return true
        }
        gestureDetector.onTouchEvent(event)
        if (zoomEnabled) scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scaleAnimator?.cancel()
                lastX = event.x
                lastY = event.y
                // Disable ViewPager2 swiping for the whole touch sequence.
                // This is the only reliable way to prevent ViewPager2 from
                // stealing ACTION_POINTER_DOWN and double-tap events.
                setPagerScrollEnabled(false)
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Re-anchor movement baseline when a new finger is added.
                lastX = event.focusXCompat()
                lastY = event.focusYCompat()
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Keep panning smooth after pinch when one finger is lifted.
                lastX = event.focusXCompat(excludeActionIndex = true)
                lastY = event.focusYCompat(excludeActionIndex = true)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY

                if (zoomEnabled && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    if (currentScale > minScale + 0.01f) {
                        // Panning while zoomed in
                        mat.postTranslate(dx, dy)
                        fixTranslation()
                        applyMatrix()
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else {
                        // At 1× — release pager for horizontal swipe
                        if (abs(dx) > abs(dy) * 1.2f) {
                            setPagerScrollEnabled(true)
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                } else if (!zoomEnabled && event.pointerCount == 1 && abs(dx) > abs(dy) * 1.2f) {
                    // Video/no-zoom — allow pager horizontal swipe
                    setPagerScrollEnabled(true)
                    parent?.requestDisallowInterceptTouchEvent(false)
                }

                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                performClick()
                if (currentScale <= minScale + 0.01f) {
                    setPagerScrollEnabled(true)
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                lastX = 0f
                lastY = 0f
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    // ── ViewPager2 control ─────────────────────────────────────────────────────

    private fun setPagerScrollEnabled(enabled: Boolean) {
        var v = parent
        while (v != null) {
            if (v is ViewPager2) {
                v.isUserInputEnabled = enabled
                return
            }
            v = (v as? android.view.View)?.parent
        }
    }

    // ── Animation ─────────────────────────────────────────────────────────────

    private fun animateToScale(target: Float, focusX: Float, focusY: Float) {
        val to = target.coerceIn(minScale, maxScale)
        if (abs(to - currentScale) < 0.01f) return

        scaleAnimator?.cancel()
        val from = currentScale
        var prev = from
        scaleAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val next = anim.animatedValue as Float
                val factor = next / prev
                if (abs(factor - 1f) > 0.0001f) {
                    mat.postScale(factor, factor, focusX, focusY)
                    currentScale = next
                    fixTranslation()
                    applyMatrix()
                }
                prev = next
                // Re-enable pager once back to 1×
                if (next <= minScale + 0.01f) {
                    setPagerScrollEnabled(true)
                }
            }
            start()
        }
    }

    // ── Gesture listeners ─────────────────────────────────────────────────────

    private inner class TapListener : GestureDetector.SimpleOnGestureListener() {
        // onDown must return true — otherwise GestureDetector drops all events
        // and onDoubleTap / onSingleTapConfirmed will never fire
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTapListener?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!zoomEnabled) return false
            val target = if (currentScale > minScale + 0.05f) minScale else 2.5f
            animateToScale(target, e.x, e.y)
            return true
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
            val factor = target / currentScale
            if (abs(factor - 1f) < 0.001f) return true

            mat.postScale(factor, factor, detector.focusX, detector.focusY)
            currentScale = target
            fixTranslation()
            applyMatrix()
            parent?.requestDisallowInterceptTouchEvent(currentScale > minScale)
            return true
        }
    }

    // ── Matrix helpers ────────────────────────────────────────────────────────

    /** Apply mat to the ImageView so it redraws */
    private fun applyMatrix() {
        super.setImageMatrix(mat)
    }

    private fun fitImageToView() {
        val d = drawable ?: return
        val contentLeft = contentLeftF()
        val contentTop = contentTopF()
        val contentWidth = contentWidthF()
        val contentHeight = contentHeightF()
        if (contentWidth <= 0f || contentHeight <= 0f) return

        val iw = d.intrinsicWidth.toFloat().takeIf { it > 0f } ?: return
        val ih = d.intrinsicHeight.toFloat().takeIf { it > 0f } ?: return

        val scale = min(contentWidth / iw, contentHeight / ih)
        val dx = contentLeft + (contentWidth - iw * scale) / 2f
        val dy = contentTop + (contentHeight - ih * scale) / 2f

        mat.reset()
        mat.postScale(scale, scale)
        mat.postTranslate(dx, dy)
        currentScale = 1f
        applyMatrix()
    }

    private fun fixTranslation() {
        val d = drawable ?: return
        val contentLeft = contentLeftF()
        val contentTop = contentTopF()
        val contentWidth = contentWidthF()
        val contentHeight = contentHeightF()
        if (contentWidth <= 0f || contentHeight <= 0f) return

        mat.getValues(matTmp)
        val sw = d.intrinsicWidth * matTmp[Matrix.MSCALE_X]
        val sh = d.intrinsicHeight * matTmp[Matrix.MSCALE_Y]
        var tx = matTmp[Matrix.MTRANS_X]
        var ty = matTmp[Matrix.MTRANS_Y]

        tx = when {
            sw <= contentWidth -> contentLeft + (contentWidth - sw) / 2f
            tx > contentLeft -> contentLeft
            tx < contentLeft + contentWidth - sw -> contentLeft + contentWidth - sw
            else -> tx
        }
        ty = when {
            sh <= contentHeight -> contentTop + (contentHeight - sh) / 2f
            ty > contentTop -> contentTop
            ty < contentTop + contentHeight - sh -> contentTop + contentHeight - sh
            else -> ty
        }

        matTmp[Matrix.MTRANS_X] = tx
        matTmp[Matrix.MTRANS_Y] = ty
        mat.setValues(matTmp)
    }

    private fun MotionEvent.focusXCompat(excludeActionIndex: Boolean = false): Float {
        if (pointerCount == 0) return 0f
        val skip = if (excludeActionIndex && actionMasked == MotionEvent.ACTION_POINTER_UP) actionIndex else -1
        var sum = 0f
        var count = 0
        for (i in 0 until pointerCount) {
            if (i == skip) continue
            sum += getX(i)
            count++
        }
        return if (count > 0) sum / count else getX(0)
    }

    private fun MotionEvent.focusYCompat(excludeActionIndex: Boolean = false): Float {
        if (pointerCount == 0) return 0f
        val skip = if (excludeActionIndex && actionMasked == MotionEvent.ACTION_POINTER_UP) actionIndex else -1
        var sum = 0f
        var count = 0
        for (i in 0 until pointerCount) {
            if (i == skip) continue
            sum += getY(i)
            count++
        }
        return if (count > 0) sum / count else getY(0)
    }

    private fun contentLeftF(): Float = paddingLeft.toFloat()

    private fun contentTopF(): Float = paddingTop.toFloat()

    private fun contentRightF(): Float = (width - paddingRight).toFloat()

    private fun contentBottomF(): Float = (height - paddingBottom).toFloat()

    private fun contentWidthF(): Float = (width - paddingLeft - paddingRight).toFloat()

    private fun contentHeightF(): Float = (height - paddingTop - paddingBottom).toFloat()
}
