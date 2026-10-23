package com.hcc.dvdplayer.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.hcc.dvdplayer.logic.PreviewSizeChooser

/**
 * Sizes its single child to the video aspect and centers it in the full screen.
 *
 * The child is measured from the screen, not from the capture's pixel size, so
 * a 640x480 frame scales up until it touches an edge. [setCropToFill] covers
 * the screen and lets the extra hang off the edges. Neither mode stretches.
 */
class AspectFitLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private var aspectWidth = 16
    private var aspectHeight = 9
    private var cropToFill = false

    fun setAspect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == aspectWidth && height == aspectHeight) return
        aspectWidth = width
        aspectHeight = height
        requestLayout()
    }

    fun setCropToFill(crop: Boolean) {
        if (crop == cropToFill) return
        cropToFill = crop
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val child = getChildAt(0) ?: return
        val (childWidth, childHeight) = PreviewSizeChooser.frameSize(
            width,
            height,
            aspectWidth,
            aspectHeight,
            crop = cropToFill,
        )
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
