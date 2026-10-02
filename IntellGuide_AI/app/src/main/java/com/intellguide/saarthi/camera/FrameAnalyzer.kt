package com.intellguide.saarthi.camera

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Intelligent ImageAnalysis Analyzer for CameraX with controlled frame rate sampling.
 * Throttles the 30/60 FPS camera stream to a power-efficient 5-10 FPS for AI vision models.
 */
class FrameAnalyzer(
    private val targetFps: Float = 6.0f,
    private val onFrameSampled: (imageProxy: ImageProxy, rotationDegrees: Int) -> Unit = { _, _ -> },
    private val onMetricsUpdated: (metrics: FrameMetrics) -> Unit = {}
) : ImageAnalysis.Analyzer {

    private val tag = "FrameAnalyzer"
    private val frameIntervalMs = (1000f / targetFps).toLong() // e.g. ~166ms for 6 FPS

    private var lastAnalyzedTimestamp = 0L
    private var frameCount = 0L
    private var fpsWindowStartTime = SystemClock.elapsedRealtime()
    private var framesInWindow = 0

    override fun analyze(imageProxy: ImageProxy) {
        val currentTimestamp = SystemClock.elapsedRealtime()

        // 1. Frame Throttling Check: Drop frames arriving faster than our target sampling rate
        if (currentTimestamp - lastAnalyzedTimestamp < frameIntervalMs) {
            imageProxy.close()
            return
        }

        lastAnalyzedTimestamp = currentTimestamp
        val startTime = SystemClock.elapsedRealtime()

        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val width = imageProxy.width
            val height = imageProxy.height

            // 2. Delegate to the active AI Vision consumer (or Phase 2 placeholder)
            onFrameSampled(imageProxy, rotationDegrees)

            // 3. Update Performance Metrics
            val latency = SystemClock.elapsedRealtime() - startTime
            frameCount++
            framesInWindow++

            val windowDuration = currentTimestamp - fpsWindowStartTime
            if (windowDuration >= 1000L) {
                val effectiveFps = (framesInWindow * 1000f) / windowDuration
                val metrics = FrameMetrics(
                    effectiveFps = effectiveFps,
                    latencyMs = latency,
                    width = width,
                    height = height,
                    totalFramesProcessed = frameCount
                )
                onMetricsUpdated(metrics)

                // Reset 1-second rolling window
                framesInWindow = 0
                fpsWindowStartTime = currentTimestamp
            }
        } catch (e: Exception) {
            Log.e(tag, "Error analyzing camera frame", e)
        } finally {
            // CRITICAL: Always close ImageProxy so CameraX can reuse the buffer
            imageProxy.close()
        }
    }
}
