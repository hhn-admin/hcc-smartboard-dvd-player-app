package com.hcc.dvdplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * A short bar that grows with the capture card's PCM peak. Silence leaves it
 * empty so a missing audio path is obvious without reading a status line.
 */
class AudioMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var target = 0f
    private var displayed = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7EB6FF.toInt() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }

    fun setLevel(value: Float) {
        target = value.coerceIn(0f, 1f)
        if (target > displayed) displayed = target
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return
        canvas.drawRoundRect(0f, 0f, width, height, height / 2f, height / 2f, track)
        val filled = width * displayed
        if (filled > 1f) {
            canvas.drawRoundRect(0f, 0f, filled, height, height / 2f, height / 2f, fill)
        }
        if (abs(displayed - target) > 0.01f) {
            displayed += (target - displayed) * 0.35f
            postInvalidateOnAnimation()
        } else {
            displayed = target
        }
    }
}
