package com.intellguide.intellguide.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale

/**
 * Result representing single or multi-note identified Indian Rupee banknotes.
 */
data class CurrencyDetectionResult(
    val denomination: Int,
    val label: String,
    val colorSignature: String,
    val confidence: Float,
    val spokenAlert: String,
    val detectionSource: String
)

/**
 * YOLOv26n currency detection result box
 */
private data class YoloDetectionBox(
    val classIndex: Int,
    val confidence: Float
)

/**
 * Phase 7 — Indian Currency Recognition Engine.
 *
 * Uses the raw TFLite Interpreter API to directly run your custom YOLOv8/v26n model.
 * This works with any Ultralytics-exported .tflite model without requiring Task Vision metadata.
 *
 * YOLO Output Format: [1, num_classes+4, 8400] (YOLOv8/v26 standard output)
 *
 * Class Label Map (edit if your training dataset used different names):
 *   Class 0 → ₹10
 *   Class 1 → ₹20
 *   Class 2 → ₹50
 *   Class 3 → ₹100
 *   Class 4 → ₹200
 *   Class 5 → ₹500
 */
class IndianCurrencyDetector(private val context: Context) {

    private val tag = "CurrencyDetector"

    // Input image size for your YOLO model (adjust if your model uses a different size)
    private val yoloInputSize = 640

    // Confidence threshold for YOLO detections
    private val confidenceThreshold = 0.45f

    // Class index → Indian Rupee denomination map
    // Exact class order from trained YOLOv26n dataset:
    // {0: '10', 1: '100', 2: '20', 3: '200', 4: '2000', 5: '50', 6: '500'}
    private val classIndexToDenomination = mapOf(
        0 to 10,
        1 to 100,
        2 to 20,
        3 to 200,
        4 to 2000,
        5 to 50,
        6 to 500
    )

    private var interpreter: Interpreter? = null
    var isTfliteModelLoaded: Boolean = false
        private set
    var modelType: String = "None"
        private set
    var numClasses: Int = 7  // {0:10, 1:100, 2:20, 3:200, 4:2000, 5:50, 6:500}
        private set

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    init {
        initializeInterpreter()
    }

    private fun initializeInterpreter() {
        val modelCandidates = listOf(
            "currency_model.tflite",
            "rupee_classifier.tflite",
            "indian_currency.tflite",
            "currency.tflite"
        )

        for (modelName in modelCandidates) {
            if (hasAsset(modelName)) {
                try {
                    val modelBuffer = loadModelFromAsset(modelName)
                    val options = Interpreter.Options().apply {
                        numThreads = 2
                        useXNNPACK = true
                    }
                    interpreter = Interpreter(modelBuffer, options)
                    isTfliteModelLoaded = true
                    modelType = "YOLO Raw Interpreter"

                    // Read output tensor shape to determine number of classes
                    val outputShape = interpreter!!.getOutputTensor(0).shape()
                    Log.d(tag, "Model output tensor shape: ${outputShape.contentToString()}")

                    // YOLOv8/v26 output: [1, 4+numClasses, 8400]
                    if (outputShape.size >= 2) {
                        val rawNumClasses = outputShape[1] - 4
                        if (rawNumClasses > 0) numClasses = rawNumClasses
                    }

                    Log.d(tag, "Loaded YOLOv26n model: $modelName | Classes: $numClasses | Input: ${yoloInputSize}x${yoloInputSize}")
                    return
                } catch (e: Exception) {
                    Log.e(tag, "Failed loading $modelName as raw YOLO Interpreter", e)
                }
            }
        }

        isTfliteModelLoaded = false
        modelType = "OCR Engine"
        Log.i(tag, "No YOLO .tflite model loaded. ML Kit OCR Engine active as fallback.")
    }

    private fun loadModelFromAsset(modelName: String): MappedByteBuffer {
        val fd = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    private fun hasAsset(name: String): Boolean {
        return try {
            context.assets.list("")?.contains(name) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Processes incoming CameraX ImageProxy frame for Indian Currency recognition.
     */
    @OptIn(ExperimentalGetImage::class)
    fun processFrame(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        onResult: (CurrencyDetectionResult?) -> Unit
    ) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            onResult(null)
            return
        }

        val bitmap = imageProxy.toBitmap()

        // 1. Try YOLOv26n Raw Interpreter
        if (isTfliteModelLoaded && interpreter != null) {
            try {
                val detections = runYoloInference(bitmap)
                if (detections.isNotEmpty()) {
                    val detectedDenominations = detections.mapNotNull { box ->
                        classIndexToDenomination[box.classIndex]
                    }

                    if (detectedDenominations.isNotEmpty()) {
                        val maxConfidence = detections.maxOf { it.confidence }
                        val result = buildMultiNoteResult(detectedDenominations, maxConfidence, "YOLOv26n Custom Model")
                        imageProxy.close()
                        onResult(result)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "YOLOv26n inference error", e)
            }
        }

        // 2. ML Kit OCR Fallback
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val matchedDenomination = detectRupeeDenominationFromText(visionText.text)
                if (matchedDenomination != null) {
                    onResult(buildResult(matchedDenomination, 0.88f, "ML Kit OCR Engine"))
                } else {
                    onResult(null)
                }
                imageProxy.close()
            }
            .addOnFailureListener { e ->
                Log.e(tag, "ML Kit OCR failure", e)
                imageProxy.close()
                onResult(null)
            }
    }

    /**
     * Runs YOLOv8/v26n TFLite inference using the raw Interpreter API.
     * Handles standard YOLOv8 output format: [1, 4+numClasses, 8400]
     */
    private fun runYoloInference(bitmap: Bitmap): List<YoloDetectionBox> {
        val interp = interpreter ?: return emptyList()

        // 1. Pre-process: Resize and normalize to [0.0, 1.0] float32
        val resized = Bitmap.createScaledBitmap(bitmap, yoloInputSize, yoloInputSize, true)
        val inputBuffer = ByteBuffer.allocateDirect(1 * yoloInputSize * yoloInputSize * 3 * 4)
            .apply { order(ByteOrder.nativeOrder()) }

        for (y in 0 until yoloInputSize) {
            for (x in 0 until yoloInputSize) {
                val pixel = resized.getPixel(x, y)
                inputBuffer.putFloat(Color.red(pixel) / 255.0f)
                inputBuffer.putFloat(Color.green(pixel) / 255.0f)
                inputBuffer.putFloat(Color.blue(pixel) / 255.0f)
            }
        }
        inputBuffer.rewind()

        // 2. Prepare output buffer — YOLOv8/v26 output shape: [1, 4+numClasses, 8400]
        val numAnchors = 8400
        val outputBuffer = Array(1) { Array(4 + numClasses) { FloatArray(numAnchors) } }

        // 3. Run inference
        interp.run(inputBuffer, outputBuffer)

        // 4. Post-process: Extract detections above threshold using NMS
        val detections = mutableListOf<YoloDetectionBox>()
        val raw = outputBuffer[0]

        for (anchor in 0 until numAnchors) {
            var bestClassScore = confidenceThreshold
            var bestClassIndex = -1

            for (c in 0 until numClasses) {
                val score = raw[4 + c][anchor]
                if (score > bestClassScore) {
                    bestClassScore = score
                    bestClassIndex = c
                }
            }

            if (bestClassIndex >= 0) {
                detections.add(YoloDetectionBox(bestClassIndex, bestClassScore))
            }
        }

        // 5. Group by class, keep highest-confidence detection per class (simple NMS)
        return detections
            .groupBy { it.classIndex }
            .map { (_, group) -> group.maxByOrNull { it.confidence }!! }
            .sortedByDescending { it.confidence }
            .take(5) // Max 5 notes per frame
    }

    private fun detectRupeeDenominationFromText(rawText: String): Int? {
        if (rawText.isBlank()) return null

        val cleanText = rawText.uppercase(Locale.ROOT)
            .replace("₹", " ").replace("RS", " ").replace(".", " ")

        val hasRbiMarker = cleanText.contains("RESERVE") || cleanText.contains("BANK") ||
                cleanText.contains("INDIA") || cleanText.contains("BHARAT") ||
                cleanText.contains("RUPEES") || cleanText.contains("GUARANTEED")

        val tokens = cleanText.split(Regex("\\s+"))

        for (denom in listOf(500, 200, 100, 50, 20, 10)) {
            val denomStr = denom.toString()
            if (tokens.contains(denomStr) || cleanText.contains(denomStr)) {
                if (hasRbiMarker) return denom
            }
        }
        return null
    }

    private fun buildResult(denom: Int, confidence: Float, source: String): CurrencyDetectionResult {
        val (color, label) = when (denom) {
            10 -> "Chocolate Brown" to "10 Rupee Note"
            20 -> "Greenish Yellow" to "20 Rupee Note"
            50 -> "Fluorescent Blue" to "50 Rupee Note"
            100 -> "Lavender" to "100 Rupee Note"
            200 -> "Bright Yellow" to "200 Rupee Note"
            500 -> "Stone Grey" to "500 Rupee Note"
            2000 -> "Magenta Pink" to "2000 Rupee Note"
            else -> "Indian Banknote" to "$denom Rupee Note"
        }
        return CurrencyDetectionResult(
            denomination = denom,
            label = label,
            colorSignature = color,
            confidence = confidence,
            spokenAlert = "This is a $denom rupee note.",
            detectionSource = source
        )
    }

    private fun buildMultiNoteResult(denominations: List<Int>, confidence: Float, source: String): CurrencyDetectionResult {
        if (denominations.size == 1) return buildResult(denominations[0], confidence, source)

        val totalSum = denominations.sum()
        val counts = denominations.groupingBy { it }.eachCount()
        val summaryParts = counts.entries.joinToString(" and ") { (denom, count) ->
            if (count == 1) "one $denom" else "$count $denom"
        }
        return CurrencyDetectionResult(
            denomination = totalSum,
            label = "Total: ₹$totalSum (${denominations.size} Notes)",
            colorSignature = summaryParts,
            confidence = confidence,
            spokenAlert = "Detected ${denominations.size} notes: $summaryParts rupees. Total value is $totalSum rupees.",
            detectionSource = source
        )
    }

    fun close() {
        try {
            interpreter?.close()
            interpreter = null
            textRecognizer.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing currency detector", e)
        }
    }
}
