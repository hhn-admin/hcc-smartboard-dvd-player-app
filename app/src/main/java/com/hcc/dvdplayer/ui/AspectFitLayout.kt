package com.hcc.dvdplayer.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.hcc.dvdplayer.logic.PreviewSizeChooser

/**
 * Sizes every child to the video aspect and centers them in the full screen.
 *
 * The children are measured from the screen, not from the capture's pixel size,
 * so a 640x480 frame scales up until it touches an edge. [setCropToFill] covers
 * the screen and lets the extra hang off the edges. Neither mode stretches.
 * A freeze still is a second child and uses the same rectangle as the video.
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
        if (childCount == 0) return
        val (childWidth, childHeight) = PreviewSizeChooser.frameSize(
            width,
            height,
            aspectWidth,
            aspectHeight,
            crop = cropToFill,
        )
        val widthSpec = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) {
            getChildAt(index).measure(widthSpec, heightSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            val childLeft = (width - childWidth) / 2
            val childTop = (height - childHeight) / 2
            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
        }
    }
}
