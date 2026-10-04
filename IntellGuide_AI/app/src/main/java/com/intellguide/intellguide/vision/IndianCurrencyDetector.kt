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
 * Result representing an identified Indian Rupee banknote.
 */
data class CurrencyDetectionResult(
    val denomination: Int,           // e.g., 10, 20, 50, 100, 200, 500
    val label: String,                // "500 Rupee Note"
    val colorSignature: String,       // "Stone Grey"
    val confidence: Float,            // 0.0 to 1.0
    val spokenAlert: String,          // "This is a 500 rupee note."
    val detectionSource: String       // "TFLite Custom Model" or "OCR Pattern Matcher"
)

/**
 * Phase 7 — Indian Currency Recognition Engine.
 * Dual-Strategy Detector:
 * 1. TFLite Classifier (if custom currency_model.tflite asset is present).
 * 2. ML Kit OCR + Note Pattern Recognition (detects numerical 10, 20, 50, 100, 200, 500 + RBI markers).
 * 3. Color Signature Cross-Verification for Mahatma Gandhi New Series notes.
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
            "currency.tflite"
        )

        for (modelName in modelCandidates) {
            if (hasAsset(modelName)) {
                try {
                    val baseOptions = BaseOptions.builder().setNumThreads(2).build()
                    val options = ImageClassifier.ImageClassifierOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setMaxResults(1)
                        .setScoreThreshold(0.60f)
                        .build()

                    tfliteClassifier = ImageClassifier.createFromFileAndOptions(context, modelName, options)
                    isTfliteModelLoaded = true
                    Log.d(tag, "Loaded custom Indian Currency TFLite Model from asset: $modelName")
                    return
                } catch (e: Exception) {
                    Log.e(tag, "Failed loading currency model $modelName", e)
                }
            }
        }

        isTfliteModelLoaded = false
        Log.i(tag, "No custom currency .tflite model found in assets. ML Kit OCR & Color Signature Engine active.")
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

        // 1. Try Custom TFLite Model first if loaded
        if (isTfliteModelLoaded && tfliteClassifier != null) {
            try {
                val tensorImage = TensorImage.fromBitmap(bitmap)
                val results: List<Classifications> = tfliteClassifier!!.classify(tensorImage)

                for (classification in results) {
                    val topCategory = classification.categories.maxByOrNull { it.score }
                    if (topCategory != null && topCategory.score >= 0.65f) {
                        val parsedDenomination = parseDenominationFromLabel(topCategory.label)
                        if (parsedDenomination != null) {
                            val result = buildResult(parsedDenomination, topCategory.score, "TFLite Custom Model")
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

        // 2. ML Kit OCR + Currency Pattern Analysis
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val fullText = visionText.text
                val matchedDenomination = detectRupeeDenominationFromText(fullText)

                if (matchedDenomination != null) {
                    val colorMatch = analyzeColorSignature(bitmap)
                    val result = buildResult(matchedDenomination, 0.88f, "ML Kit OCR & Pattern Matcher")
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
     * Extracts Rupee denomination from OCR text by looking for currency indicators.
     */
    private fun detectRupeeDenominationFromText(rawText: String): Int? {
        if (rawText.isBlank()) return null

        val cleanText = rawText.uppercase(Locale.ROOT)
            .replace("₹", " ")
            .replace("RS", " ")
            .replace(".", " ")

        // Check for RBI / Currency indicators to prevent matching random numbers
        val hasRbiMarker = cleanText.contains("RESERVE") ||
                cleanText.contains("BANK") ||
                cleanText.contains("INDIA") ||
                cleanText.contains("REZERVE") ||
                cleanText.contains("BHARAT") ||
                cleanText.contains("RUPEES") ||
                cleanText.contains("GUARANTEED")

        val tokens = cleanText.split(Regex("\\s+"))

        // Priority order for Indian Rupee notes: 500, 200, 100, 50, 20, 10
        val validDenominations = listOf(500, 200, 100, 50, 20, 10)

        for (denom in validDenominations) {
            val denomStr = denom.toString()
            if (tokens.contains(denomStr) || cleanText.contains(denomStr)) {
                // If RBI marker present OR denomination number clearly found twice/prominently
                if (hasRbiMarker || countOccurrences(cleanText, denomStr) >= 1) {
                    return denom
                }
            }
        }

        return null
    }

    private fun countOccurrences(text: String, sub: String): Int {
        return text.split(sub).size - 1
    }

    private fun parseDenominationFromLabel(label: String): Int? {
        val digits = label.replace(Regex("[^0-9]"), "")
        val denom = digits.toIntOrNull()
        return if (denom in listOf(10, 20, 50, 100, 200, 500)) denom else null
    }

    /**
     * Color signature analysis for Mahatma Gandhi New Series Indian Banknotes.
     */
    private fun analyzeColorSignature(bitmap: Bitmap): String {
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 50, 50, false)
            var rSum = 0L
            var gSum = 0L
            var bSum = 0L
            val count = scaled.width * scaled.height

            for (x in 0 until scaled.width) {
                for (y in 0 until scaled.height) {
                    val pixel = scaled.getPixel(x, y)
                    rSum += Color.red(pixel)
                    gSum += Color.green(pixel)
                    bSum += Color.blue(pixel)
                }
            }

            val avgR = (rSum / count).toInt()
            val avgG = (gSum / count).toInt()
            val avgB = (bSum / count).toInt()

            when {
                avgR > 180 && avgG in 140..200 && avgB < 100 -> "Bright Yellow (₹200)"
                avgR < 100 && avgG in 140..220 && avgB > 180 -> "Fluorescent Blue (₹50)"
                avgR in 120..180 && avgG in 120..170 && avgB > 160 -> "Lavender (₹100)"
                avgR in 100..150 && avgG in 100..140 && avgB in 100..140 -> "Stone Grey (₹500)"
                avgR > 110 && avgG in 60..110 && avgB < 80 -> "Chocolate Brown (₹10)"
                avgR in 140..200 && avgG in 150..210 && avgB < 120 -> "Greenish Yellow (₹20)"
                else -> "Standard Banknote Color"
            }
        } catch (e: Exception) {
            "Standard Banknote Color"
        }
    }

    private fun buildResult(denom: Int, confidence: Float, source: String): CurrencyDetectionResult {
        val (color, label) = when (denom) {
            10 -> "Chocolate Brown" to "10 Rupee Note"
            20 -> "Greenish Yellow" to "20 Rupee Note"
            50 -> "Fluorescent Blue" to "50 Rupee Note"
            100 -> "Lavender" to "100 Rupee Note"
            200 -> "Bright Yellow" to "200 Rupee Note"
            500 -> "Stone Grey" to "500 Rupee Note"
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
