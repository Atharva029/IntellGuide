package com.intellguide.intellguide.camera

/**
 * Real-time performance metrics for the camera sampling pipeline.
 */
data class FrameMetrics(
    val effectiveFps: Float = 0f,
    val latencyMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val totalFramesProcessed: Long = 0L
)
