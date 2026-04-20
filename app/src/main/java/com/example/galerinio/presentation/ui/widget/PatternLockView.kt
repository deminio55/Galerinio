package com.example.galerinio.presentation.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Lightweight 3x3 pattern lock view.
 * Reports selected cell indices [0..8] in drawing order.
 */
class PatternLockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onPatternDetected: ((List<Int>) -> Unit)? = null

    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#80FFFFFF")
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val selected = mutableListOf<Int>()
    private var drawing = false
    private var currentX = 0f
    private var currentY = 0f

    private var cellRadius = 0f
    private var hitRadius = 0f
    private var hGap = 0f
    private var vGap = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        hGap = w / 4f
        vGap = h / 4f
        cellRadius = minOf(hGap, vGap) * 0.18f
        hitRadius = minOf(hGap, vGap) * 0.45f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (index in 0 until 9) {
            val x = cellCenterX(index)
            val y = cellCenterY(index)
            val paint = if (selected.contains(index)) activePaint else normalPaint
            canvas.drawCircle(x, y, cellRadius, paint)
        }

        if (selected.isNotEmpty()) {
            val path = Path()
            val first = selected.first()
            path.moveTo(cellCenterX(first), cellCenterY(first))
            for (i in 1 until selected.size) {
                val index = selected[i]
                path.lineTo(cellCenterX(index), cellCenterY(index))
            }
            if (drawing) {
                path.lineTo(currentX, currentY)
            }
            canvas.drawPath(path, linePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearPattern()
                drawing = true
                currentX = event.x
                currentY = event.y
                pickCell(event.x, event.y)?.let { appendCell(it) }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!drawing) return false
                currentX = event.x
                currentY = event.y
                pickCell(event.x, event.y)?.let { appendCell(it) }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (!drawing) return false
                drawing = false
                invalidate()
                onPatternDetected?.invoke(selected.toList())
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun clearPattern() {
        selected.clear()
        invalidate()
    }

    private fun appendCell(index: Int) {
        if (selected.contains(index)) return
        if (selected.isNotEmpty()) {
            // Auto-insert middle node for straight skip (e.g. 0 -> 2 inserts 1)
            val prev = selected.last()
            getMiddleCell(prev, index)?.let { mid ->
                if (!selected.contains(mid)) selected.add(mid)
            }
        }
        selected.add(index)
    }

    private fun pickCell(x: Float, y: Float): Int? {
        for (index in 0 until 9) {
            val cx = cellCenterX(index)
            val cy = cellCenterY(index)
            if (hypot((x - cx).toDouble(), (y - cy).toDouble()) <= hitRadius) {
                return index
            }
        }
        return null
    }

    private fun cellCenterX(index: Int): Float = ((index % 3) + 1) * hGap

    private fun cellCenterY(index: Int): Float = ((index / 3) + 1) * vGap

    private fun getMiddleCell(a: Int, b: Int): Int? {
        val ar = a / 3
        val ac = a % 3
        val br = b / 3
        val bc = b % 3
        if ((ar + br) % 2 != 0 || (ac + bc) % 2 != 0) return null
        val mr = (ar + br) / 2
        val mc = (ac + bc) / 2
        val middle = mr * 3 + mc
        return if (middle == a || middle == b) null else middle
    }
}

