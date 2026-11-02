package com.example.daqinbandithelper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** 全屏透明层，手指拖动框选模板区域。松手即回调矩形（屏幕坐标）。 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false

    private val stroke = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val dim = Paint().apply {
        color = 0x40000000.toInt()
    }

    var onRect: ((Rect) -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                curX = event.x
                curY = event.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                curX = event.x
                curY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                emitRect()
            }
        }
        return true
    }

    private fun currentRect(): Rect {
        val left = min(startX, curX).toInt()
        val top = min(startY, curY).toInt()
        val right = max(startX, curX).toInt()
        val bottom = max(startY, curY).toInt()
        return Rect(left, top, right, bottom)
    }

    private fun emitRect() {
        val r = currentRect()
        if (r.width() > 20 && r.height() > 20) {
            onRect?.invoke(r)
        }
    }

    /** 供「保存模板」按钮再次触发 */
    fun triggerCrop() = emitRect()

    override fun onDraw(canvas: Canvas) {
        if (dragging || curX != startX || curY != startY) {
            val r = currentRect()
            canvas.drawRect(r, dim)
            canvas.drawRect(r, stroke)
        }
    }
}
