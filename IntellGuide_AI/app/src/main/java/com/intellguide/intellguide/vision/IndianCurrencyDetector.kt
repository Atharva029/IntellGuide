package com.intellguide.intellguide.vision

import android.content.Context
import android.graphics.Bitmap
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
 * Result representing single or multi-note/coin identified Indian Rupee set.
 */
data class CurrencyDetectionResult(
    val denomination: Int,           // e.g., 100, 750 (total sum if multi-item)
    val isCoin: Boolean = false,      // true if coin set
    val label: String,                // "500 Rupee Note" or "Total: ₹750 (4 Items)"
    val colorSignature: String,       // Summary list of detected notes/coins
    val confidence: Float,            // 0.0 to 1.0
    val spokenAlert: String,          // "Detected 4 items: One 500, two 100 notes, and one 50 coin. Total value is 750 rupees."
    val detectionSource: String       // "YOLO Multi-Box Model" or "Spatial OCR Block Engine"
)

private data class YoloBox(
    val classIndex: Int,
    val confidence: Float,
    val xCenter: Float,
    val yCenter: Float,
    val w: Float,
    val h: Float
)

/**
 * Phase 7 — Advanced Indian Currency & Coin Multi-Object Engine.
 * Supports detecting 1 to 10+ banknotes and coins simultaneously in any layout (side-by-side, stacked, or spread).
 *
 * Engine Pipeline:
 * 1. Raw YOLO TFLite Interpreter (runs multi-box detection for 1..10+ items simultaneously if asset model present).
 * 2. Spatial TextBlock ML Kit OCR Analyzer (groups distinct spatial text blocks across the frame for 1..10+ notes/coins).
 * 3. Currency Aggregator (sums values and formats natural human voice alerts).
 */
class IndianCurrencyDetector(private val context: Context) {

    private val tag = "CurrencyDetector"

    private var interpreter: Interpreter? = null
    var isTfliteModelLoaded: Boolean = false
        private set

    private val yoloInputSize = 640
    private val confidenceThreshold = 0.25f

    // Standard YOLO Class Index -> Indian Currency mapping:
    // 0: ₹10, 1: ₹20, 2: ₹50, 3: ₹100, 4: ₹200, 5: ₹500, 6: ₹1 coin, 7: ₹2 coin, 8: ₹5 coin, 9: ₹10 coin, 10: ₹20 coin
    private val classIndexToItem = mapOf(
        0 to Pair(10, false),
        1 to Pair(20, false),
        2 to Pair(50, false),
        3 to Pair(100, false),
        4 to Pair(200, false),
        5 to Pair(500, false),
        6 to Pair(1, true),
        7 to Pair(2, true),
        8 to Pair(5, true),
        9 to Pair(10, true),
        10 to Pair(20, true)
    )

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    init {
        initializeTfliteInterpreter()
    }

    private fun initializeTfliteInterpreter() {
        val modelCandidates = listOf(
            "currency_model.tflite",
            "rupee_classifier.tflite",
            "indian_currency.tflite",
            "yolo_currency.tflite",
            "currency.tflite"
        )

        for (modelName in modelCandidates) {
            if (hasAsset(modelName)) {
                try {
                    val buffer = loadModelFromAsset(modelName)
                    val options = Interpreter.Options().apply {
                        numThreads = 2
                        useXNNPACK = true
                    }
                    interpreter = Interpreter(buffer, options)
                    isTfliteModelLoaded = true
                    Log.d(tag, "Loaded custom YOLO Currency TFLite Interpreter: $modelName")
                    return
                } catch (e: Exception) {
                    Log.e(tag, "Failed loading $modelName as YOLO interpreter", e)
                }
            }
        }

        isTfliteModelLoaded = false
        Log.i(tag, "No YOLO .tflite asset found. Spatial TextBlock Multi-Note Engine active.")
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
     * Processes incoming CameraX ImageProxy frame for single or multi-item (1 to 10+ notes/coins) recognition.
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

        // 1. Try YOLO Multi-Box Object Detection TFLite Model
        if (isTfliteModelLoaded && interpreter != null) {
            try {
                val yoloBoxes = runYoloInference(bitmap)
                if (yoloBoxes.isNotEmpty()) {
                    val detectedItems = yoloBoxes.mapNotNull { box -> classIndexToItem[box.classIndex] }
                    if (detectedItems.isNotEmpty()) {
                        val maxConfidence = yoloBoxes.maxOf { it.confidence }
                        val result = buildMultiItemResult(detectedItems, maxConfidence, "YOLO Multi-Box Model")
                        imageProxy.close()
                        onResult(result)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "YOLO TFLite inference error", e)
            }
        }

        // 2. Spatial TextBlock ML Kit OCR Engine (Analyzes distinct spatial regions across the frame for 1..10+ items)
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val detectedList = mutableListOf<Pair<Int, Boolean>>()

                // Analyze each spatial TextBlock individually to detect multiple notes/coins in different positions
                for (block in visionText.textBlocks) {
                    val blockText = block.text
                    val item = parseDenominationFromTextBlock(blockText)
                    if (item != null) {
                        detectedList.add(item)
                    }
                }

                // If block iteration didn't catch, fallback to full text pattern analysis
                if (detectedList.isEmpty()) {
                    val fallbackList = parseDenominationsFromFullText(visionText.text)
                    detectedList.addAll(fallbackList)
                }

                if (detectedList.isNotEmpty()) {
                    val result = buildMultiItemResult(detectedList, 0.88f, "Spatial OCR Block Engine")
                    onResult(result)
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
     * Parses banknote or coin denomination from an individual spatial TextBlock in the camera frame.
     */
    private fun parseDenominationFromTextBlock(blockText: String): Pair<Int, Boolean>? {
        if (blockText.isBlank()) return null
        val cleanText = blockText.uppercase(Locale.ROOT).replace("₹", " ").replace("RS", " ")

        val hasRbiMarker = cleanText.contains("RESERVE") || cleanText.contains("BANK") ||
                cleanText.contains("INDIA") || cleanText.contains("BHARAT") ||
                cleanText.contains("RUPEES") || cleanText.contains("GUARANTEED")

        val isCoinMarker = cleanText.contains("COIN") || cleanText.contains("SATYAMEVA") || cleanText.contains("JAYATE")

        val tokens = cleanText.split(Regex("\\s+"))

        // Check Banknotes (500, 200, 100, 50, 20, 10)
        for (denom in listOf(500, 200, 100, 50, 20, 10)) {
            val denomStr = denom.toString()
            if (tokens.contains(denomStr)) {
                if (hasRbiMarker || cleanText.contains("PROMISE") || cleanText.contains("GOVERNOR") || cleanText.contains("CENTRAL")) {
                    return Pair(denom, false) // Banknote
                }
            }
        }

        // Check Coins (20, 10, 5, 2, 1)
        for (denom in listOf(20, 10, 5, 2, 1)) {
            val denomStr = denom.toString()
            if (tokens.contains(denomStr)) {
                if (isCoinMarker || cleanText.contains("SATYAMEVA") || cleanText.contains("JAYATE") || cleanText.contains("INDIA")) {
                    return Pair(denom, true) // Coin
                }
            }
        }

        return null
    }

    private fun parseDenominationsFromFullText(fullText: String): List<Pair<Int, Boolean>> {
        if (fullText.isBlank()) return emptyList()
        val cleanText = fullText.uppercase(Locale.ROOT).replace("₹", " ").replace("RS", " ")
        val hasRbiMarker = cleanText.contains("RESERVE") || cleanText.contains("BANK") || cleanText.contains("INDIA") || cleanText.contains("BHARAT") || cleanText.contains("RUPEES")
        val tokens = cleanText.split(Regex("\\s+"))

        val detected = mutableListOf<Pair<Int, Boolean>>()
        for (denom in listOf(500, 200, 100, 50, 20, 10)) {
            if (tokens.contains(denom.toString()) && hasRbiMarker) {
                detected.add(Pair(denom, false))
            }
        }
        return detected
    }

    /**
     * Runs YOLO TFLite multi-box object detection inference.
     */
    private fun runYoloInference(bitmap: Bitmap): List<YoloBox> {
        val interp = interpreter ?: return emptyList()
        val resized = Bitmap.createScaledBitmap(bitmap, yoloInputSize, yoloInputSize, true)
        val intValues = IntArray(yoloInputSize * yoloInputSize)
        resized.getPixels(intValues, 0, yoloInputSize, 0, 0, yoloInputSize, yoloInputSize)

        val inputBuffer = ByteBuffer.allocateDirect(1 * yoloInputSize * yoloInputSize * 3 * 4)
            .apply { order(ByteOrder.nativeOrder()) }

        for (pixelValue in intValues) {
            inputBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixelValue and 0xFF) / 255.0f)
        }
        inputBuffer.rewind()

        val numAnchors = 8400
        val numClasses = 11
        val outputBuffer = Array(1) { Array(4 + numClasses) { FloatArray(numAnchors) } }

        return try {
            interp.run(inputBuffer, outputBuffer)
            val raw = outputBuffer[0]
            val candidates = mutableListOf<YoloBox>()

            for (anchor in 0 until numAnchors) {
                var bestScore = 0f
                var bestClass = -1
                for (c in 0 until numClasses) {
                    val score = raw[4 + c][anchor]
                    if (score > bestScore) {
                        bestScore = score
                        bestClass = c
                    }
                }
                if (bestScore >= confidenceThreshold && bestClass >= 0) {
                    val cx = raw[0][anchor]
                    val cy = raw[1][anchor]
                    val w = raw[2][anchor]
                    val h = raw[3][anchor]
                    candidates.add(YoloBox(bestClass, bestScore, cx, cy, w, h))
                }
            }
            candidates.sortedByDescending { it.confidence }.take(10) // Up to 10 items
        } catch (e: Exception) {
            Log.e(tag, "YOLO inference failed", e)
            emptyList()
        }
    }

    private fun buildMultiItemResult(
        items: List<Pair<Int, Boolean>>,
        confidence: Float,
        source: String
    ): CurrencyDetectionResult {
        if (items.size == 1) {
            val (denom, isCoin) = items[0]
            val label = if (isCoin) "$denom Rupee Coin" else "$denom Rupee Note"
            val alert = if (isCoin) "This is a $denom rupee coin." else "This is a $denom rupee note."
            val color = if (isCoin) "Metallic Coin" else getNoteColor(denom)

            return CurrencyDetectionResult(
                denomination = denom,
                isCoin = isCoin,
                label = label,
                colorSignature = color,
                confidence = confidence,
                spokenAlert = alert,
                detectionSource = source
            )
        }

        // Multiple items (1 to 10+ items detected side-by-side or spread out)
        val totalSum = items.sumOf { it.first }
        val notes = items.filter { !it.second }
        val coins = items.filter { it.second }

        val parts = mutableListOf<String>()
        if (notes.isNotEmpty()) {
            val noteCounts = notes.map { it.first }.groupingBy { it }.eachCount()
            val noteStr = noteCounts.entries.joinToString(" and ") { (d, c) -> if (c == 1) "one $d" else "$c $d" } + " rupee note" + (if (notes.size > 1) "s" else "")
            parts.add(noteStr)
        }
        if (coins.isNotEmpty()) {
            val coinCounts = coins.map { it.first }.groupingBy { it }.eachCount()
            val coinStr = coinCounts.entries.joinToString(" and ") { (d, c) -> if (c == 1) "one $d" else "$c $d" } + " rupee coin" + (if (coins.size > 1) "s" else "")
            parts.add(coinStr)
        }

        val summaryText = parts.joinToString(" and ")
        val alert = "Detected ${items.size} items: $summaryText. Total value is $totalSum rupees."

        return CurrencyDetectionResult(
            denomination = totalSum,
            isCoin = coins.isNotEmpty() && notes.isEmpty(),
            label = "Total: ₹$totalSum (${items.size} Items)",
            colorSignature = summaryText,
            confidence = confidence,
            spokenAlert = alert,
            detectionSource = source
        )
    }

    private fun getNoteColor(denom: Int): String {
        return when (denom) {
            10 -> "Chocolate Brown"
            20 -> "Greenish Yellow"
            50 -> "Fluorescent Blue"
            100 -> "Lavender"
            200 -> "Bright Yellow"
            500 -> "Stone Grey"
            else -> "Indian Banknote"
        }
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
