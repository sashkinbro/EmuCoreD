package com.sbro.emucored.data

object PerformanceOverlayMetrics {
    const val FPS = 1 shl 0
    const val SPEED = 1 shl 1
    const val RENDERER = 1 shl 2
    const val FRAME_TIME = 1 shl 3
    const val RESOLUTION = 1 shl 4
    const val HOST_CPU = 1 shl 5
    const val HOST_GPU = 1 shl 6
    const val AUDIO = 1 shl 7

    const val ALL = FPS or SPEED or RENDERER or FRAME_TIME or RESOLUTION or HOST_CPU or HOST_GPU or AUDIO

    // Audio remains opt-in; the default mask only contains metrics the runtime
    // currently publishes (FPS/Speed, renderer+resolution, frame time, host
    // CPU/GPU names).
    const val DEFAULT = FPS or SPEED or RENDERER or FRAME_TIME or RESOLUTION or HOST_CPU or HOST_GPU

    fun sanitize(mask: Int): Int = mask and ALL

    fun isEnabled(mask: Int, metric: Int): Boolean = sanitize(mask) and metric != 0
}
