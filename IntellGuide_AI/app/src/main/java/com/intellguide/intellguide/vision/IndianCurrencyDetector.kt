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
    val confidence: Float,
    val xCenter: Float,
    val yCenter: Float,
    val w: Float,
    val h: Float
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

    // Confidence threshold for YOLO detections (lowered for live mobile camera frames in hand/table)
    private val confidenceThreshold = 0.20f

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
    // Actual output tensor shape read from model at load time
    private var outputTensorShape: IntArray = intArrayOf()
    // True if output is [1, 4+N, 8400], false if [1, 8400, 4+N]
    private var isFeatureFirst: Boolean = true

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

                    // --- Diagnostic: dump ALL tensor info ---
                    val inputShape = interpreter!!.getInputTensor(0).shape()
                    outputTensorShape = interpreter!!.getOutputTensor(0).shape()
                    Log.d(tag, "[YOLO INIT] Input tensor:  ${inputShape.contentToString()}")
                    Log.d(tag, "[YOLO INIT] Output tensor: ${outputTensorShape.contentToString()}")

                    // Detect output layout:
                    //   Format A (YOLOv8 standard): [1, 4+numClasses, 8400] → dim[1] < dim[2]
                    //   Format B (transposed):      [1, 8400, 4+numClasses] → dim[1] > dim[2]
                    if (outputTensorShape.size == 3) {
                        val dim1 = outputTensorShape[1]
                        val dim2 = outputTensorShape[2]
                        if (dim1 < dim2) {
                            // Format A: features are rows
                            isFeatureFirst = true
                            numClasses = dim1 - 4
                        } else {
                            // Format B: anchors are rows
                            isFeatureFirst = false
                            numClasses = dim2 - 4
                        }
                    }

                    Log.d(tag, "[YOLO INIT] Layout: ${if (isFeatureFirst) "[1, 4+N, 8400]" else "[1, 8400, 4+N]"} | Classes: $numClasses")
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
     * Automatically handles two common YOLO TFLite output formats:
     *   Format A: [1, 4+numClasses, 8400]  (standard YOLOv8 export, isFeatureFirst=true)
     *   Format B: [1, 8400, 4+numClasses]  (transposed variant, isFeatureFirst=false)
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

        val detections = mutableListOf<YoloDetectionBox>()
        val numAnchors = 8400

        try {
            if (isFeatureFirst) {
                // Format A: [1, 4+numClasses, 8400]
                val outputBuffer = Array(1) { Array(4 + numClasses) { FloatArray(numAnchors) } }
                interp.run(inputBuffer, outputBuffer)
                val raw = outputBuffer[0]
                var maxScore = 0f
                for (anchor in 0 until numAnchors) {
                    var bestScore = 0f
                    var bestClass = -1
                    for (c in 0 until numClasses) {
                        val score = raw[4 + c][anchor]
                        if (score > bestScore) { bestScore = score; bestClass = c }
                    }
                    if (bestScore > maxScore) maxScore = bestScore
                    if (bestScore >= confidenceThreshold && bestClass >= 0) {
                        val cx = raw[0][anchor]
                        val cy = raw[1][anchor]
                        val w = raw[2][anchor]
                        val h = raw[3][anchor]
                        detections.add(YoloDetectionBox(bestClass, bestScore, cx, cy, w, h))
                    }
                }
                Log.d(tag, "[YOLO FormatA] raw candidates (>= $confidenceThreshold): ${detections.size} | max raw score in frame: ${"%.3f".format(maxScore)}")
            } else {
                // Format B: [1, 8400, 4+numClasses]
                val outputBuffer = Array(1) { Array(numAnchors) { FloatArray(4 + numClasses) } }
                interp.run(inputBuffer, outputBuffer)
                val raw = outputBuffer[0]
                var maxScore = 0f
                for (anchor in 0 until numAnchors) {
                    var bestScore = 0f
                    var bestClass = -1
                    for (c in 0 until numClasses) {
                        val score = raw[anchor][4 + c]
                        if (score > bestScore) { bestScore = score; bestClass = c }
                    }
                    if (bestScore > maxScore) maxScore = bestScore
                    if (bestScore >= confidenceThreshold && bestClass >= 0) {
                        val cx = raw[anchor][0]
                        val cy = raw[anchor][1]
                        val w = raw[anchor][2]
                        val h = raw[anchor][3]
                        detections.add(YoloDetectionBox(bestClass, bestScore, cx, cy, w, h))
                    }
                }
                Log.d(tag, "[YOLO FormatB] raw candidates (>= $confidenceThreshold): ${detections.size} | max raw score in frame: ${"%.3f".format(maxScore)}")
            }
        } catch (e: Exception) {
            Log.e(tag, "[YOLO] run() failed — tensor mismatch? outputShape=${outputTensorShape.contentToString()}", e)
            return emptyList()
        }

        // Apply Spatial Non-Maximum Suppression (IoU threshold = 0.45)
        // Keeps separate notes (even if same denomination) while eliminating redundant overlapping boxes for the same note
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val nmsResults = mutableListOf<YoloDetectionBox>()

        for (candidate in sortedDetections) {
            var isOverlapping = false
            for (kept in nmsResults) {
                if (calculateIoU(candidate, kept) > 0.45f) {
                    isOverlapping = true
                    break
                }
            }
            if (!isOverlapping) {
                nmsResults.add(candidate)
            }
            if (nmsResults.size >= 5) break // Max 5 notes per frame
        }

        Log.d(tag, "[YOLO] Spatial NMS Final (${nmsResults.size} notes): ${nmsResults.map { "₹${classIndexToDenomination[it.classIndex]} conf=${"%.2f".format(it.confidence)}" }}")
        return nmsResults
    }

    /**
     * Calculate Intersection over Union (IoU) between two bounding boxes
     */
    private fun calculateIoU(a: YoloDetectionBox, b: YoloDetectionBox): Float {
        val aMinX = a.xCenter - a.w / 2f
        val aMaxX = a.xCenter + a.w / 2f
        val aMinY = a.yCenter - a.h / 2f
        val aMaxY = a.yCenter + a.h / 2f

        val bMinX = b.xCenter - b.w / 2f
        val bMaxX = b.xCenter + b.w / 2f
        val bMinY = b.yCenter - b.h / 2f
        val bMaxY = b.yCenter + b.h / 2f

        val interMinX = maxOf(aMinX, bMinX)
        val interMaxX = minOf(aMaxX, bMaxX)
        val interMinY = maxOf(aMinY, bMinY)
        val interMaxY = minOf(aMaxY, bMaxY)

        val interWidth = maxOf(0f, interMaxX - interMinX)
        val interHeight = maxOf(0f, interMaxY - interMinY)
        val interArea = interWidth * interHeight

        val aArea = a.w * a.h
        val bArea = b.w * b.h
        val unionArea = aArea + bArea - interArea

        return if (unionArea <= 0f) 0f else interArea / unionArea
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
