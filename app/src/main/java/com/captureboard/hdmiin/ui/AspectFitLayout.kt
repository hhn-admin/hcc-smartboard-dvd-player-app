package com.captureboard.hdmiin.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.captureboard.hdmiin.logic.PreviewSizeChooser

/**
 * Sizes its single child to the video aspect ratio and centers it, so a 16:9
 * HDMI frame is not stretched on a 16:10 panel.
 */
class AspectFitLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private var aspectWidth = 16
    private var aspectHeight = 9

    fun setAspect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == aspectWidth && height == aspectHeight) return
        aspectWidth = width
        aspectHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val child = getChildAt(0) ?: return
        val (childWidth, childHeight) = PreviewSizeChooser.fitInside(width, height, aspectWidth, aspectHeight)
        child.measure(
            MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        val width = right - left
        val height = bottom - top
        val childWidth = child.measuredWidth
        val childHeight = child.measuredHeight
        val childLeft = (width - childWidth) / 2
        val childTop = (height - childHeight) / 2
        child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
    }
}
