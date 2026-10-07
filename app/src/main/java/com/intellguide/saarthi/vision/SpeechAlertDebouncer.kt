package com.intellguide.saarthi.vision

import android.os.SystemClock

/**
 * Intelligent Navigation Hazard Aggregator & Speech De-Escalation Engine.
 * Filters out tabletop noise, prioritizes immediate walking hazards,
 * and formats calm, consolidated single-sentence guidance.
 */
class SpeechAlertDebouncer(
    private val cooldownDurationMs: Long = 4500L // 4.5 seconds of silence between speech alerts
) {

    private val lastAlertTimestamps = mutableMapOf<String, Long>()
    private var lastSpokenGlobalTimestamp = 0L

    /**
     * Evaluates detected objects and produces a single, calm, non-overlapping guidance sentence.
     * Returns null if no critical hazards are in the walking path or during the quiet window.
     */
    fun getPrioritizedSpeechAlert(
        detectedObjects: List<DetectedObjectInfo>,
        isCurrentlySpeaking: Boolean
    ): String? {
        if (detectedObjects.isEmpty() || isCurrentlySpeaking) return null

        val currentTime = SystemClock.elapsedRealtime()

        // 1. Enforce strict 4.5s global quiet window between any speech utterances
        if (currentTime - lastSpokenGlobalTimestamp < cooldownDurationMs) {
            return null
        }

        // 2. Filter strictly for Walking Collision Hazards (Discards cups, laptops, books, vases)
        val walkingHazards = detectedObjects.filter { obj ->
            obj.hazardCategory == HazardCategory.CRITICAL_WALKING_HAZARD &&
            (obj.isInWalkingCorridor || obj.isCloseProximity)
        }

        if (walkingHazards.isEmpty()) return null

        // 3. Sort hazards by urgency:
        // Immediate close obstacles directly ahead -> Medium distance ahead -> Side obstacles
        val sortedHazards = walkingHazards.sortedWith(
            compareByDescending<DetectedObjectInfo> { it.spatialDirection == "ahead" && it.isCloseProximity }
                .thenByDescending { it.spatialDirection == "ahead" }
                .thenByDescending { it.isCloseProximity }
                .thenByDescending { it.confidence }
        )

        // 4. Check if the top hazard has passed its individual category cooldown
        val topHazard = sortedHazards.firstOrNull() ?: return null
        val topKey = "${topHazard.label}_${topHazard.spatialDirection}"
        val lastTopTime = lastAlertTimestamps[topKey] ?: 0L

        if (currentTime - lastTopTime < cooldownDurationMs) {
            return null
        }

        // Update timestamps
        lastAlertTimestamps[topKey] = currentTime
        lastSpokenGlobalTimestamp = currentTime

        // 5. Generate clean consolidated sentence
        return if (sortedHazards.size >= 2 && sortedHazards[1].spatialDirection != topHazard.spatialDirection) {
            val secondHazard = sortedHazards[1]
            lastAlertTimestamps["${secondHazard.label}_${secondHazard.spatialDirection}"] = currentTime
            formatConsolidatedPhrase(topHazard, secondHazard)
        } else {
            formatSingleHazardPhrase(topHazard)
        }
    }

    private fun formatSingleHazardPhrase(obj: DetectedObjectInfo): String {
        return when {
            obj.isCloseProximity && obj.spatialDirection == "ahead" -> "Caution: ${obj.label} directly ahead."
            obj.spatialDirection == "ahead" -> "${obj.label} detected ahead."
            obj.spatialDirection == "on your left" -> "${obj.label} on your left."
            obj.spatialDirection == "on your right" -> "${obj.label} on your right."
            else -> "${obj.label} in path."
        }
    }

    private fun formatConsolidatedPhrase(first: DetectedObjectInfo, second: DetectedObjectInfo): String {
        val firstPart = when (first.spatialDirection) {
            "ahead" -> "${first.label} ahead"
            "on your left" -> "${first.label} on your left"
            "on your right" -> "${first.label} on your right"
            else -> first.label
        }

        val secondPart = when (second.spatialDirection) {
            "ahead" -> "${second.label} ahead"
            "on your left" -> "${second.label} on your left"
            "on your right" -> "${second.label} on your right"
            else -> second.label
        }

        return "$firstPart, and $secondPart."
    }

    fun reset() {
        lastAlertTimestamps.clear()
        lastSpokenGlobalTimestamp = 0L
    }
}
