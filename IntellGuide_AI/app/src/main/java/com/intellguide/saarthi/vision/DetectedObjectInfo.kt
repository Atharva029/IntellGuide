package com.intellguide.saarthi.vision

import android.graphics.RectF

/**
 * Encapsulates a detected real-world object with spatial positioning metadata.
 */
data class DetectedObjectInfo(
    val id: Int,
    val label: String,
    val confidence: Float,
    val boundingBox: RectF, // Normalized coordinates [0.0, 1.0] (left, top, right, bottom)
    val spatialDirection: String, // "ahead", "on your left", "on your right"
    val isObstacleInPath: Boolean = false
) {
    val confidencePercentage: Int
        get() = (confidence * 100).toInt()
}
