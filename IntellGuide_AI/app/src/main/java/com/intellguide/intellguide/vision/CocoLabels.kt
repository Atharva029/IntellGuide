package com.intellguide.intellguide.vision

import java.util.Locale

/**
 * Standard COCO (Common Objects in Context) 80-class taxonomy helper.
 */
object CocoLabels {

    val LABELS = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
        "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
        "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    /**
     * Formats class name nicely for speech output and UI badges (e.g. "traffic light" -> "Traffic Light").
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
        val lowercase = label.lowercase()
        return lowercase in listOf(
            "person", "chair", "table", "dining table", "couch", "bed", "car", "bicycle",
            "motorcycle", "bus", "truck", "bench", "stairs", "door", "dog", "fire hydrant"
        )
    }
}
