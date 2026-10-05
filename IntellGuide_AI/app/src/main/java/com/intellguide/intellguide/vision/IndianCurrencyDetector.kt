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
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.classifier.Classifications
import org.tensorflow.lite.task.vision.classifier.ImageClassifier
import java.util.Locale

/**
 * Result representing an identified single or multi-item Indian Rupee banknote or coin set.
 */
data class CurrencyDetectionResult(
    val denomination: Int,           // e.g., 100, 300 (total sum if multi-item)
    val isCoin: Boolean = false,      // true if coin
    val label: String,                // "500 Rupee Note" or "Total: ₹300 (2 Items)"
    val colorSignature: String,       // "Stone Grey" or summary list
    val confidence: Float,            // 0.0 to 1.0
    val spokenAlert: String,          // "Detected 2 notes: One 100 and one 200 rupee note. Total value is 300 rupees."
    val detectionSource: String       // "TFLite Custom Model" or "Multi-Item Pattern Engine"
)

/**
 * Phase 7 — Indian Currency & Coin Multi-Item Recognition Engine.
 * Supports side-by-side detection of multiple banknotes and coins (e.g. ₹100 note beside ₹200 note or ₹5 coin).
 */
class IndianCurrencyDetector(private val context: Context) {

    private val tag = "CurrencyDetector"
    private var tfliteClassifier: ImageClassifier? = null
    var isTfliteModelLoaded: Boolean = false
        private set

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    init {
        initializeTfliteModel()
    }

    private fun initializeTfliteModel() {
        val modelCandidates = listOf(
            "currency_model.tflite",
            "rupee_classifier.tflite",
            "indian_currency.tflite",
            "coin_currency.tflite",
            "currency.tflite"
        )

        for (modelName in modelCandidates) {
            if (hasAsset(modelName)) {
                try {
                    val baseOptions = BaseOptions.builder().setNumThreads(2).build()
                    val options = ImageClassifier.ImageClassifierOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setMaxResults(3)
                        .setScoreThreshold(0.55f)
                        .build()

                    tfliteClassifier = ImageClassifier.createFromFileAndOptions(context, modelName, options)
                    isTfliteModelLoaded = true
                    Log.d(tag, "Loaded custom Indian Currency & Coin TFLite Model from asset: $modelName")
                    return
                } catch (e: Exception) {
                    Log.e(tag, "Failed loading currency model $modelName", e)
                }
            }
        }

        isTfliteModelLoaded = false
        Log.i(tag, "No custom currency .tflite model found in assets. Multi-Item Pattern Engine active.")
    }

    private fun hasAsset(name: String): Boolean {
        return try {
            context.assets.list("")?.contains(name) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Processes incoming CameraX ImageProxy frame for single or side-by-side Indian Currency recognition.
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

        // 1. Try Custom TFLite Model first if loaded
        if (isTfliteModelLoaded && tfliteClassifier != null) {
            try {
                val tensorImage = TensorImage.fromBitmap(bitmap)
                val results: List<Classifications> = tfliteClassifier!!.classify(tensorImage)

                val detectedItems = mutableListOf<Pair<Int, Boolean>>()
                for (classification in results) {
                    val topCategory = classification.categories.maxByOrNull { it.score }
                    if (topCategory != null && topCategory.score >= 0.55f) {
                        val parsed = parseDenominationAndTypeFromLabel(topCategory.label)
                        if (parsed != null) {
                            detectedItems.add(parsed)
                        }
                    }
                }

                if (detectedItems.isNotEmpty()) {
                    val result = buildMultiItemResult(detectedItems, 0.90f, "TFLite Custom Model")
                    imageProxy.close()
                    onResult(result)
                    return
                }
            } catch (e: Exception) {
                Log.e(tag, "TFLite inference error", e)
            }
        }

        // 2. ML Kit Multi-Item Pattern Engine (Detects multiple side-by-side notes and coins in frame)
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val detectedList = detectMultiRupeeDenominationsFromText(visionText.text)

                if (detectedList.isNotEmpty()) {
                    val result = buildMultiItemResult(detectedList, 0.88f, "Multi-Item Pattern Engine")
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
     * Extracts ALL distinct Rupee banknote and coin denominations present side-by-side in the frame.
     */
    private fun detectMultiRupeeDenominationsFromText(rawText: String): List<Pair<Int, Boolean>> {
        if (rawText.isBlank()) return emptyList()

        val cleanText = rawText.uppercase(Locale.ROOT)
            .replace("₹", " ")
            .replace("RS", " ")
            .replace(".", " ")

        val hasRbiMarker = cleanText.contains("RESERVE") ||
                cleanText.contains("BANK") ||
                cleanText.contains("INDIA") ||
                cleanText.contains("REZERVE") ||
                cleanText.contains("BHARAT") ||
                cleanText.contains("RUPEES") ||
                cleanText.contains("GUARANTEED")

        val isCoinMarker = cleanText.contains("COIN") || cleanText.contains("SATYAMEVA") || cleanText.contains("JAYATE")

        val tokens = cleanText.split(Regex("\\s+"))
        val detected = mutableListOf<Pair<Int, Boolean>>()

        // Scan for Banknote Denominations (500, 200, 100, 50, 20, 10)
        val validNoteDenoms = listOf(500, 200, 100, 50, 20, 10)
        for (denom in validNoteDenoms) {
            val denomStr = denom.toString()
            if (tokens.contains(denomStr) || cleanText.contains(denomStr)) {
                if (hasRbiMarker || countOccurrences(cleanText, denomStr) >= 1) {
                    detected.add(Pair(denom, false)) // Banknote
                }
            }
        }

        // Scan for Coin Denominations (20, 10, 5, 2, 1)
        val validCoinDenoms = listOf(20, 10, 5, 2, 1)
        for (denom in validCoinDenoms) {
            val denomStr = denom.toString()
            if (tokens.contains(denomStr) || cleanText.contains(denomStr)) {
                // Add coin if coin marker or distinct coin digit pattern found
                if (isCoinMarker || cleanText.contains("INDIA") || cleanText.contains("BHARAT")) {
                    val alreadyAddedAsNote = detected.any { it.first == denom && !it.second }
                    if (!alreadyAddedAsNote) {
                        detected.add(Pair(denom, true)) // Coin
                    }
                }
            }
        }

        return detected.distinct()
    }

    private fun countOccurrences(text: String, sub: String): Int {
        return text.split(sub).size - 1
    }

    private fun parseDenominationAndTypeFromLabel(label: String): Pair<Int, Boolean>? {
        val lower = label.lowercase(Locale.ROOT)
        val isCoin = lower.contains("coin")
        val digits = label.replace(Regex("[^0-9]"), "")
        val denom = digits.toIntOrNull() ?: return null

        val validDenoms = listOf(1, 2, 5, 10, 20, 50, 100, 200, 500)
        return if (denom in validDenoms) Pair(denom, isCoin) else null
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

        // Multiple items side-by-side
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
            tfliteClassifier?.close()
            tfliteClassifier = null
            textRecognizer.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing currency detector", e)
        }
    }
}
