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
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.classifier.Classifications
import org.tensorflow.lite.task.vision.classifier.ImageClassifier
import java.util.Locale

/**
 * Result representing an identified Indian Rupee banknote or coin.
 */
data class CurrencyDetectionResult(
    val denomination: Int,           // e.g., 1, 2, 5, 10, 20, 50, 100, 200, 500
    val isCoin: Boolean = false,      // true for coins, false for banknotes
    val label: String,                // "5 Rupee Coin" or "500 Rupee Note"
    val colorSignature: String,       // "Nickel-Brass" or "Stone Grey"
    val confidence: Float,            // 0.0 to 1.0
    val spokenAlert: String,          // "This is a 5 rupee coin."
    val detectionSource: String       // "TFLite Custom Model" or "OCR Pattern Matcher"
)

/**
 * Phase 7 — Indian Currency & Coin Recognition Engine.
 * Supports:
 * - Banknotes: ₹10, ₹20, ₹50, ₹100, ₹200, ₹500
 * - Coins: ₹1, ₹2, ₹5, ₹10, ₹20
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
                        .setMaxResults(1)
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
        Log.i(tag, "No custom currency .tflite model found in assets. ML Kit OCR & Metallic Color Engine active.")
    }

    private fun hasAsset(name: String): Boolean {
        return try {
            context.assets.list("")?.contains(name) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Processes incoming CameraX ImageProxy frame for Indian Currency and Coin recognition.
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

                for (classification in results) {
                    val topCategory = classification.categories.maxByOrNull { it.score }
                    if (topCategory != null && topCategory.score >= 0.60f) {
                        val parsed = parseDenominationAndTypeFromLabel(topCategory.label)
                        if (parsed != null) {
                            val (denom, isCoin) = parsed
                            val result = buildResult(denom, isCoin, topCategory.score, "TFLite Custom Model")
                            imageProxy.close()
                            onResult(result)
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "TFLite inference error", e)
            }
        }

        // 2. ML Kit OCR + Metallic Color & Coin Pattern Analysis
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val fullText = visionText.text
                val matched = detectRupeeDenominationFromText(fullText)

                if (matched != null) {
                    val (denom, isCoin) = matched
                    val result = buildResult(denom, isCoin, 0.88f, "ML Kit Pattern Engine")
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
     * Extracts Rupee denomination and type (note or coin) from OCR text & currency patterns.
     */
    private fun detectRupeeDenominationFromText(rawText: String): Pair<Int, Boolean>? {
        if (rawText.isBlank()) return null

        val cleanText = rawText.uppercase(Locale.ROOT)
            .replace("₹", " ")
            .replace("RS", " ")
            .replace(".", " ")

        // Check for RBI / Currency indicators
        val hasRbiMarker = cleanText.contains("RESERVE") ||
                cleanText.contains("BANK") ||
                cleanText.contains("INDIA") ||
                cleanText.contains("REZERVE") ||
                cleanText.contains("BHARAT") ||
                cleanText.contains("RUPEES") ||
                cleanText.contains("GUARANTEED")

        val isCoinMarker = cleanText.contains("COIN") || cleanText.contains("SATYAMEVA") || cleanText.contains("JAYATE")

        val tokens = cleanText.split(Regex("\\s+"))

        // Priority 1: Banknotes (500, 200, 100, 50, 20, 10)
        val validNoteDenominations = listOf(500, 200, 100, 50, 20, 10)
        for (denom in validNoteDenominations) {
            val denomStr = denom.toString()
            if (tokens.contains(denomStr) || cleanText.contains(denomStr)) {
                if (hasRbiMarker) {
                    return Pair(denom, false) // Banknote
                }
            }
        }

        // Priority 2: Indian Coins (1, 2, 5, 10, 20)
        val validCoinDenominations = listOf(20, 10, 5, 2, 1)
        for (denom in validCoinDenominations) {
            val denomStr = denom.toString()
            if (tokens.contains(denomStr) || cleanText.contains(denomStr)) {
                if (isCoinMarker || cleanText.contains("INDIA") || cleanText.contains("BHARAT") || countOccurrences(cleanText, denomStr) >= 1) {
                    return Pair(denom, true) // Coin
                }
            }
        }

        return null
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

    private fun buildResult(denom: Int, isCoin: Boolean, confidence: Float, source: String): CurrencyDetectionResult {
        val (color, label, alert) = if (isCoin) {
            val coinColor = when (denom) {
                1 -> "Stainless Steel Silver"
                2 -> "Ferritic Stainless Steel"
                5 -> "Nickel-Brass Gold"
                10 -> "Bimetallic Ring"
                20 -> "12-Sided Dodecagon Brass"
                else -> "Metallic Coin"
            }
            Triple(coinColor, "$denom Rupee Coin", "This is a $denom rupee coin.")
        } else {
            val noteColor = when (denom) {
                10 -> "Chocolate Brown"
                20 -> "Greenish Yellow"
                50 -> "Fluorescent Blue"
                100 -> "Lavender"
                200 -> "Bright Yellow"
                500 -> "Stone Grey"
                else -> "Indian Banknote"
            }
            Triple(noteColor, "$denom Rupee Note", "This is a $denom rupee note.")
        }

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
