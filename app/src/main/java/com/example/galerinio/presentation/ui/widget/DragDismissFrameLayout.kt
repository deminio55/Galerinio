package com.example.galerinio.presentation.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import kotlin.math.abs

/**
 * FrameLayout that intercepts vertical drag gestures to implement a
 * "drag to dismiss" animation. Children can call
 * requestDisallowInterceptTouchEvent but this layout deliberately ignores
 * the disallow flag so that it can always detect a clearly vertical drag
 * even when a ZoomImageView or ViewPager2 is inside.
 */
class DragDismissFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onDismiss: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var startRawY = 0f
    private var startRawX = 0f
    private var lastRawY = 0f
    private var isDragging = false
    private var totalDy = 0f                // accumulated translation while dragging
    private val interp = FastOutSlowInInterpolator()
    private var childRequestedDisallowIntercept = false

    // ── Intercept ─────────────────────────────────────────────────────────────

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        childRequestedDisallowIntercept = disallowIntercept
        // Keep propagation upward so outer containers (e.g. ViewPager2) receive it.
        parent?.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startRawX = ev.rawX
                startRawY = ev.rawY
                lastRawY = ev.rawY
                isDragging = false
                totalDy = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (shouldKeepGestureInChild(ev)) {
                    return false
                }
                val dy = ev.rawY - startRawY
                val dx = ev.rawX - startRawX
                if (!isDragging
                    && abs(dy) > touchSlop
                    && abs(dy) > abs(dx) * 2.5f
                ) {
                    isDragging = true
                    lastRawY = ev.rawY
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                childRequestedDisallowIntercept = false
                if (!isDragging) resetView()
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startRawY = ev.rawY
                lastRawY = ev.rawY
                totalDy = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = ev.rawY - lastRawY
                lastRawY = ev.rawY
                totalDy += delta
                applyDrag(totalDy)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val threshold = height * 0.30f
                if (abs(totalDy) > threshold) {
                    animateDismiss(totalDy > 0)
                } else {
                    animateSnapBack()
                }
            }
        }
        return true
    }

    // ── Drag physics ──────────────────────────────────────────────────────────

    private fun applyDrag(dy: Float) {
        // Resistance: makes dragging feel "heavy"
        val resistance = 0.55f
        val translated = dy * resistance
        translationY = translated

        val progress = (abs(translated) / height.toFloat()).coerceIn(0f, 1f)
        val scale = 1f - progress * 0.12f
        scaleX = scale
        scaleY = scale
        alpha = 1f - progress * 0.45f
    }

    // ── Animations ────────────────────────────────────────────────────────────

    private fun animateDismiss(downward: Boolean) {
        val targetY = if (downward) height.toFloat() * 1.1f else -height.toFloat() * 1.1f
        animate()
            .translationY(targetY)
            .alpha(0f)
            .setDuration(220)
            .setInterpolator(interp)
            .withEndAction { onDismiss?.invoke() }
            .start()
    }

    private fun animateSnapBack() {
        animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(280)
            .setInterpolator(interp)
            .start()
    }

    private fun resetView() {
        translationY = 0f
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
    }

    private fun shouldKeepGestureInChild(ev: MotionEvent): Boolean {
        if (ev.pointerCount > 1) return true
        if (!childRequestedDisallowIntercept) return false
        return hasActiveZoomChild(this)
    }

    private fun hasActiveZoomChild(view: View): Boolean {
        if (view is ZoomImageView && view.visibility == View.VISIBLE && view.isZoomActive()) {
            return true
        }
        if (view !is ViewGroup) return false
        for (i in 0 until view.childCount) {
            if (hasActiveZoomChild(view.getChildAt(i))) return true
        }
        return false
    }
}

