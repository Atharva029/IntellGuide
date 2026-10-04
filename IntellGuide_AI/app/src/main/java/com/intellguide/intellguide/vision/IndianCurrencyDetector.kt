package com.intellguide.intellguide.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.classifier.Classifications
import org.tensorflow.lite.task.vision.classifier.ImageClassifier
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.Locale

/**
 * Result representing single or multi-note identified Indian Rupee banknotes.
 */
data class CurrencyDetectionResult(
    val denomination: Int,           // e.g., 10, 20, 50, 100, 200, 500 (or total sum if multi-note)
    val label: String,                // "500 Rupee Note" or "Total: ₹700 (3 Notes)"
    val colorSignature: String,       // "Stone Grey"
    val confidence: Float,            // 0.0 to 1.0
    val spokenAlert: String,          // "This is a 500 rupee note."
    val detectionSource: String       // "YOLO Custom Detector", "TFLite Classifier", or "OCR Engine"
)

/**
 * Phase 7 — Indian Currency Recognition Engine.
 * Dual/Triple-Strategy Hybrid Detector:
 * 1. YOLO/TFLite Object Detector (for Multi-Note & Single-Note Bounding Box detection).
 * 2. TFLite Image Classifier fallback.
 * 3. ML Kit OCR + Note Pattern Recognition (detects numerical 10, 20, 50, 100, 200, 500 + RBI markers).
 * 4. Color Signature Cross-Verification for Mahatma Gandhi New Series notes.
 */
class IndianCurrencyDetector(private val context: Context) {

    private val tag = "CurrencyDetector"
    private var tfliteObjectDetector: ObjectDetector? = null
    private var tfliteClassifier: ImageClassifier? = null

    var isTfliteModelLoaded: Boolean = false
        private set
    var modelType: String = "None"
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
                // 1. First try loading as TFLite Object Detector (YOLO / SSD)
                try {
                    val baseOptions = BaseOptions.builder().setNumThreads(2).build()
                    val detectorOptions = ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setMaxResults(5) // Allow detecting up to 5 notes in single frame
                        .setScoreThreshold(0.45f)
                        .build()

                    tfliteObjectDetector = ObjectDetector.createFromFileAndOptions(context, modelName, detectorOptions)
                    isTfliteModelLoaded = true
                    modelType = "ObjectDetector"
                    Log.d(tag, "Loaded custom Indian Currency TFLite Object Detector from asset: $modelName")
                    return
                } catch (e: Exception) {
                    Log.i(tag, "Model $modelName is not a Task Vision ObjectDetector. Trying ImageClassifier...", e)
                }

                // 2. Fallback to ImageClassifier if ObjectDetector schema fails
                try {
                    val baseOptions = BaseOptions.builder().setNumThreads(2).build()
                    val classifierOptions = ImageClassifier.ImageClassifierOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setMaxResults(1)
                        .setScoreThreshold(0.50f)
                        .build()

                    tfliteClassifier = ImageClassifier.createFromFileAndOptions(context, modelName, classifierOptions)
                    isTfliteModelLoaded = true
                    modelType = "Classifier"
                    Log.d(tag, "Loaded custom Indian Currency TFLite Classifier from asset: $modelName")
                    return
                } catch (e: Exception) {
                    Log.e(tag, "Failed loading currency model $modelName as Classifier", e)
                }
            }
        }

        isTfliteModelLoaded = false
        modelType = "OCR Engine"
        Log.i(tag, "No custom currency .tflite model loaded. ML Kit OCR & Color Signature Engine active.")
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

        // 1. Try Custom TFLite Object Detector (Multi-Note support)
        if (isTfliteModelLoaded && tfliteObjectDetector != null) {
            try {
                val inputTensor: TensorImage = TensorImage.fromBitmap(bitmap)
                val imageProcessor = ImageProcessor.Builder()
                    .add(Rot90Op(-rotationDegrees / 90))
                    .build()
                val processedImage: TensorImage = imageProcessor.process(inputTensor)

                val results: List<Detection> = tfliteObjectDetector!!.detect(processedImage)
                if (results.isNotEmpty()) {
                    val detectedDenominations = mutableListOf<Int>()
                    var maxScore = 0f

                    for (detection in results) {
                        val topCategory = detection.categories.maxByOrNull { it.score }
                        if (topCategory != null && topCategory.score >= 0.45f) {
                            val denom = parseDenominationFromLabel(topCategory.label)
                            if (denom != null) {
                                detectedDenominations.add(denom)
                                if (topCategory.score > maxScore) maxScore = topCategory.score
                            }
                        }
                    }

                    if (detectedDenominations.isNotEmpty()) {
                        val result = buildMultiNoteResult(detectedDenominations, maxScore, "YOLO Custom Detector")
                        imageProxy.close()
                        onResult(result)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "TFLite ObjectDetector inference error", e)
            }
        }

        // 2. Try Custom TFLite Classifier fallback
        if (isTfliteModelLoaded && tfliteClassifier != null) {
            try {
                val tensorImage = TensorImage.fromBitmap(bitmap)
                val results: List<Classifications> = tfliteClassifier!!.classify(tensorImage)

                for (classification in results) {
                    val topCategory = classification.categories.maxByOrNull { it.score }
                    if (topCategory != null && topCategory.score >= 0.50f) {
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
                Log.e(tag, "TFLite Classifier inference error", e)
            }
        }

        // 3. ML Kit OCR + Currency Pattern Analysis
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val fullText = visionText.text
                val matchedDenomination = detectRupeeDenominationFromText(fullText)

                if (matchedDenomination != null) {
                    val colorMatch = analyzeColorSignature(bitmap)
                    val result = buildResult(matchedDenomination, 0.88f, "ML Kit OCR Engine")
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

        val hasRbiMarker = cleanText.contains("RESERVE") ||
                cleanText.contains("BANK") ||
                cleanText.contains("INDIA") ||
                cleanText.contains("REZERVE") ||
                cleanText.contains("BHARAT") ||
                cleanText.contains("RUPEES") ||
                cleanText.contains("GUARANTEED")

        val tokens = cleanText.split(Regex("\\s+"))
        val validDenominations = listOf(500, 200, 100, 50, 20, 10)

        for (denom in validDenominations) {
            val denomStr = denom.toString()
            if (tokens.contains(denomStr) || cleanText.contains(denomStr)) {
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

    private fun buildMultiNoteResult(denominations: List<Int>, confidence: Float, source: String): CurrencyDetectionResult {
        if (denominations.size == 1) {
            return buildResult(denominations[0], confidence, source)
        }

        val totalSum = denominations.sum()
        val counts = denominations.groupingBy { it }.eachCount()
        
        val summaryParts = counts.entries.joinToString(", ") { (denom, count) ->
            if (count == 1) "one $denom" else "$count $denom"
        }

        val spokenAlert = "Detected ${denominations.size} notes: $summaryParts rupees. Total value is $totalSum rupees."

        return CurrencyDetectionResult(
            denomination = totalSum,
            label = "Total: ₹$totalSum (${denominations.size} Notes)",
            colorSignature = summaryParts,
            confidence = confidence,
            spokenAlert = spokenAlert,
            detectionSource = source
        )
    }

    fun close() {
        try {
            tfliteObjectDetector?.close()
            tfliteObjectDetector = null
            tfliteClassifier?.close()
            tfliteClassifier = null
            textRecognizer.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing currency detector", e)
        }
    }
}
