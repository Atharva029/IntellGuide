package com.intellguide.saarthi.vision

import android.os.SystemClock

/**
 * Speech Alert Debouncer & Prioritizer.
 * Guarantees zero speech overlapping and speaks only the single most relevant obstacle in view.
 */
class SpeechAlertDebouncer(
    private val cooldownDurationMs: Long = 4000L // 4 seconds cooldown per obstacle
) {

    private val lastAlertTimestamps = mutableMapOf<String, Long>()
    private var lastSpokenGlobalTimestamp = 0L

    /**
     * Finds the single highest-priority obstacle that has passed cooldown.
     * Prevents chaos by never returning multiple alerts simultaneously.
     */
    fun getPrioritizedSpeechAlert(detectedObjects: List<DetectedObjectInfo>, isCurrentlySpeaking: Boolean): String? {
        if (detectedObjects.isEmpty() || isCurrentlySpeaking) return null

        val currentTime = SystemClock.elapsedRealtime()

        // Global speech cooldown: Do not start a new utterance within 2.5 seconds of the previous utterance
        if (currentTime - lastSpokenGlobalTimestamp < 2500L) {
            return null
        }

        // Prioritize: 1. Obstacles directly "ahead", 2. Highest confidence score
        val sortedCandidates = detectedObjects.sortedWith(
            compareByDescending<DetectedObjectInfo> { it.spatialDirection == "ahead" }
                .thenByDescending { it.isObstacleInPath }
                .thenByDescending { it.confidence }
        )

        for (candidate in sortedCandidates) {
            val key = "${candidate.label}_${candidate.spatialDirection}"
            val lastTime = lastAlertTimestamps[key] ?: 0L

            if (currentTime - lastTime >= cooldownDurationMs) {
                lastAlertTimestamps[key] = currentTime
                lastSpokenGlobalTimestamp = currentTime
                return formatSpokenPhrase(candidate)
            }
        }

        return null
    }

    private fun formatSpokenPhrase(obj: DetectedObjectInfo): String {
        return when (obj.spatialDirection) {
            "ahead" -> "${obj.label} detected ahead."
            "on your left" -> "${obj.label} on your left."
            "on your right" -> "${obj.label} on your right."
            else -> "${obj.label} detected."
        }
    }

    fun reset() {
        lastAlertTimestamps.clear()
        lastSpokenGlobalTimestamp = 0L
    }
}
