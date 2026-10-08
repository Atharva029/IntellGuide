package com.intellguide.saarthi.vision

import java.util.Locale

/**
 * Categorization levels for detected objects during navigation.
 */
enum class HazardCategory {
    CRITICAL_WALKING_HAZARD, // Large physical path blockers (Person, Chair, Table, Stairs, Vehicle)
    SECONDARY_TABLETOP_ITEM  // Small items (Cup, Bottle, Laptop, Mouse, Food) - Silenced while walking
}

/**
 * Standard COCO (Common Objects in Context) 80-class taxonomy and assistive hazard classifier.
 */
object CocoLabels {

    // 1. Critical Physical Navigation Hazards (Spoken during walking navigation)
    private val WALKING_HAZARDS = setOf(
        "person", "chair", "couch", "sofa", "bed", "dining table", "table", "bench",
        "door", "stairs", "car", "motorcycle", "bicycle", "bus", "truck", "dog",
        "fire hydrant", "traffic light", "stop sign"
    )

    // 2. Secondary Non-Hazard Items (Muted from audio speech during walking navigation)
    private val SECONDARY_ITEMS = setOf(
        "laptop", "cell phone", "bottle", "cup", "wine glass", "fork", "knife", "spoon",
        "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog",
        "pizza", "donut", "cake", "potted plant", "tv", "mouse", "remote", "keyboard",
        "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase",
        "scissors", "teddy bear", "hair drier", "toothbrush", "backpack", "umbrella",
        "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket"
    )

    /**
     * Determines whether an object class represents an immediate collision risk.
     */
    fun isWalkingHazard(rawLabel: String): Boolean {
        val normalized = rawLabel.lowercase().trim()
        return normalized in WALKING_HAZARDS || WALKING_HAZARDS.any { normalized.contains(it) }
    }

    /**
     * Categorizes detected class into Hazard vs Secondary Tabletop item.
     */
    fun getHazardCategory(rawLabel: String): HazardCategory {
        return if (isWalkingHazard(rawLabel)) {
            HazardCategory.CRITICAL_WALKING_HAZARD
        } else {
            HazardCategory.SECONDARY_TABLETOP_ITEM
        }
    }

    /**
     * Formats class name nicely for speech output and UI badges.
     */
    fun formatLabel(rawName: String): String {
        return rawName.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }

    /**
     * Determines if a class is an immediate physical navigation obstacle.
     */
    fun isNavigationalObstacle(label: String): Boolean {
        return isWalkingHazard(label)
    }
}
