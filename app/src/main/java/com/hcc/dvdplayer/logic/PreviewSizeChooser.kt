package com.hcc.dvdplayer.logic

import kotlin.math.abs

/**
 * Picks a UVC mode that a USB 2.0 host can actually carry.
 * HDMI capture dongles should be asked for MJPEG at or below 1080p; uncompressed
 * 1080p is about 60 MB/s and routinely fails negotiation on tablet USB hosts.
 */
data class VideoMode(
    val type: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val fpsOptions: List<Int> = emptyList(),
)

object PreviewSizeChooser {
    const val MJPEG_FRAME = 0x07
    const val MJPEG_FORMAT = 0x06
    const val MJPEG_LEGACY = 1
    const val MAX_WIDTH = 1920
    const val MAX_HEIGHT = 1080
    const val TARGET_FPS = 30

    fun choose(modes: List<VideoMode>, preferMjpeg: Boolean = true): VideoMode? {
        val usable = modes.filter { it.width > 0 && it.height > 0 }
        if (usable.isEmpty()) return null

        val mjpeg = usable.filter { isMjpeg(it.type) }
        val pool = if (preferMjpeg && mjpeg.isNotEmpty()) mjpeg else usable
        val withinCap = pool.filter { it.width <= MAX_WIDTH && it.height <= MAX_HEIGHT }
        val selected = if (withinCap.isNotEmpty()) {
            withinCap.maxWith(areaThenCloserFps())
        } else {
            pool.minWith(compareBy<VideoMode> { it.width.toLong() * it.height }
                .thenBy { abs(preferredFps(it) - TARGET_FPS) })
        }
        return selected.copy(fps = preferredFps(selected))
    }

    fun preferredFps(mode: VideoMode): Int {
        val options = (mode.fpsOptions + mode.fps).filter { it in 1..60 }.distinct()
        if (options.isEmpty()) return TARGET_FPS
        return options.minWith(
            compareBy<Int> { abs(it - TARGET_FPS) }
                .thenBy { if (it >= TARGET_FPS) 0 else 1 }
                .thenByDescending { it },
        )
    }

    fun fitInside(containerWidth: Int, containerHeight: Int, videoWidth: Int, videoHeight: Int): Pair<Int, Int> {
        if (containerWidth <= 0 || containerHeight <= 0) return 0 to 0
        if (videoWidth <= 0 || videoHeight <= 0) return containerWidth to containerHeight
        val videoAspect = videoWidth.toDouble() / videoHeight.toDouble()
        val containerAspect = containerWidth.toDouble() / containerHeight.toDouble()
        return if (videoAspect > containerAspect) {
            val height = (containerWidth / videoAspect).toInt().coerceAtLeast(1)
            containerWidth to height
        } else {
            val width = (containerHeight * videoAspect).toInt().coerceAtLeast(1)
            width to containerHeight
        }
    }

    fun isMjpeg(type: Int): Boolean {
        return type == MJPEG_FRAME || type == MJPEG_FORMAT || type == MJPEG_LEGACY
    }

    private fun areaThenCloserFps(): Comparator<VideoMode> {
        return Comparator { left, right ->
            val area = (left.width.toLong() * left.height).compareTo(right.width.toLong() * right.height)
            if (area != 0) {
                area
            } else {
                val leftDelta = abs(preferredFps(left) - TARGET_FPS)
                val rightDelta = abs(preferredFps(right) - TARGET_FPS)
                rightDelta.compareTo(leftDelta)
            }
        }
    }
}
