package com.intellguide.saarthi.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.Locale

/**
 * True 80-Class Object Detection Engine (TensorFlow Lite / SSD-MobileNet / YOLO).
 * Eliminates generic labels and misclassifications by running a dedicated COCO detector.
 */
class TFLiteObjectDetector(
    private val context: Context,
    private val confidenceThreshold: Float = 0.55f, // High threshold to eliminate phantom/ghost boxes
    private val maxResults: Int = 3                 // Focus on top obstacles only
) {

    private val tag = "TFLiteDetector"
    private var tfliteDetector: ObjectDetector? = null
    var isModelLoaded: Boolean = false
        private set

    init {
        initializeTfliteModel()
    }

    private fun initializeTfliteModel() {
        val modelCandidates = listOf(
            "ssd_mobilenet.tflite",
            "efficientdet_lite0.tflite",
            "yolov8n.tflite",
            "model.tflite"
        )

        for (modelName in modelCandidates) {
            if (hasAsset(modelName)) {
                try {
                    val baseOptions = BaseOptions.builder().setNumThreads(2).build()
                    val options = ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setMaxResults(maxResults)
                        .setScoreThreshold(confidenceThreshold)
                        .build()

                    tfliteDetector = ObjectDetector.createFromFileAndOptions(context, modelName, options)
                    isModelLoaded = true
                    Log.d(tag, "Loaded 80-class TFLite Model from asset: $modelName")
                    return
                } catch (e: Exception) {
                    Log.e(tag, "Failed loading $modelName", e)
                }
            }
        }

        isModelLoaded = false
        Log.w(tag, "No .tflite model file found in assets. Please place ssd_mobilenet.tflite in app/src/main/assets/")
    }

    private fun hasAsset(name: String): Boolean {
        return try {
            context.assets.list("")?.contains(name) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Analyzes incoming CameraX frame and returns high-confidence COCO detections.
     */
    @OptIn(ExperimentalGetImage::class)
    fun detectObjects(imageProxy: ImageProxy, rotationDegrees: Int): List<DetectedObjectInfo> {
        if (!isModelLoaded || tfliteDetector == null) {
            return emptyList() // Do not show phantom/fake boxes if model is not present
        }

        val detectedList = mutableListOf<DetectedObjectInfo>()

        try {
            val bitmap = imageProxy.toBitmap()
            val tensorImage = TensorImage.fromBitmap(bitmap)

            val imageProcessor = ImageProcessor.Builder()
                .add(Rot90Op(-rotationDegrees / 90))
                .build()
            val processedImage = imageProcessor.process(tensorImage)

            val imageWidth = processedImage.width.toFloat()
            val imageHeight = processedImage.height.toFloat()

            val results: List<Detection> = tfliteDetector!!.detect(processedImage)

            results.forEachIndexed { index, detection ->
                val topCategory = detection.categories.maxByOrNull { it.score }
                if (topCategory != null && topCategory.score >= confidenceThreshold) {
                    val rawLabel = topCategory.label
                    val formattedLabel = CocoLabels.formatLabel(rawLabel)
                    val box = detection.boundingBox

                    // Normalize bounding box coordinates to [0.0, 1.0]
                    val normalizedBox = RectF(
                        (box.left / imageWidth).coerceIn(0f, 1f),
                        (box.top / imageHeight).coerceIn(0f, 1f),
                        (box.right / imageWidth).coerceIn(0f, 1f),
                        (box.bottom / imageHeight).coerceIn(0f, 1f)
                    )

                    val spatialDirection = calculateSpatialDirection(normalizedBox)
                    val isObstacle = CocoLabels.isNavigationalObstacle(rawLabel)

                    detectedList.add(
                        DetectedObjectInfo(
                            id = index + 1,
                            label = formattedLabel,
                            confidence = topCategory.score,
                            boundingBox = normalizedBox,
                            spatialDirection = spatialDirection,
                            isObstacleInPath = isObstacle
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Inference error", e)
        }

        return detectedList
    }

    private fun calculateSpatialDirection(box: RectF): String {
        val centerX = (box.left + box.right) / 2f
        return when {
            centerX < 0.35f -> "on your left"
            centerX > 0.65f -> "on your right"
            else -> "ahead"
        }
    }

    fun close() {
        try {
            tfliteDetector?.close()
            tfliteDetector = null
            isModelLoaded = false
        } catch (e: Exception) {
            Log.e(tag, "Error closing TFLite ObjectDetector", e)
        }
    }
}
