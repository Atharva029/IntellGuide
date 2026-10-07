package com.intellguide.saarthi.vision

import android.graphics.RectF

/**
 * Encapsulates a detected real-world object with spatial positioning and hazard metadata.
 */
data class DetectedObjectInfo(
    val id: Int,
    val label: String,
    val confidence: Float,
    val boundingBox: RectF, // Normalized coordinates [0.0, 1.0] (left, top, right, bottom)
    val spatialDirection: String, // "ahead", "on your left", "on your right"
    val isObstacleInPath: Boolean = false,
    val boxArea: Float = 0f,
    val isInWalkingCorridor: Boolean = false,
    val isCloseProximity: Boolean = false,
    val hazardCategory: HazardCategory = HazardCategory.CRITICAL_WALKING_HAZARD
) {
    val confidencePercentage: Int
        get() = (confidence * 100).toInt()

    val isImmediateCollisionRisk: Boolean
        get() = hazardCategory == HazardCategory.CRITICAL_WALKING_HAZARD && (isInWalkingCorridor || isCloseProximity)
}
