package com.intellguide.intellguide.vision

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max

class OCRManager {
    // Pure English (Latin) Text Recognizer Engine
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class TextLineItem(
        val text: String,
        val top: Int,
        val left: Int,
        val height: Int
    )

    @OptIn(ExperimentalGetImage::class)
    @SuppressLint("UnsafeOptInUsageError")
    fun processImageProxy(
        imageProxy: ImageProxy,
        onTextRecognized: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val result = formatIntoReadingOrder(visionText, filterBackground = true)
                    if (result.isNotBlank() && result.length > 3) {
                        onTextRecognized(result)
                    }
                    imageProxy.close()
                }
                .addOnFailureListener { e ->
                    onError(e)
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    /**
     * Lightweight text-presence detection for live preview frames.
     * Returns the raw ML Kit [Text] object so that [TextAlignmentGuide] can
     * evaluate bounding-box geometry for guidance cues.
     * This does NOT run full reading-order formatting — it's the cheap check.
     */
    @OptIn(ExperimentalGetImage::class)
    @SuppressLint("UnsafeOptInUsageError")
    fun processImageProxyForDetection(
        imageProxy: ImageProxy,
        onTextDetected: (Text) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    onTextDetected(visionText)
                    imageProxy.close()
                }
                .addOnFailureListener { e ->
                    onError(e)
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    /**
     * High-speed, high-accuracy processing for static scanned document Bitmaps.
     * Downscales oversized 12-Megapixel images to 1600px and filters out stray noise.
     */
    fun processBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        onTextRecognized: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val optimizedBitmap = getOptimizedBitmap(bitmap, maxDimension = 1600)
        val image = InputImage.fromBitmap(optimizedBitmap, rotationDegrees)

        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val result = formatIntoReadingOrder(visionText, filterBackground = true)
                if (result.isNotBlank()) {
                    onTextRecognized(result)
                } else {
                    onTextRecognized("No text found in scanned document.")
                }
            }
            .addOnFailureListener { e ->
                onError(e)
            }
    }

    private fun getOptimizedBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxDim = max(width, height)
        if (maxDim <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / maxDim
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Formats raw OCR text blocks into exact reading sequence (Top-to-Bottom, Left-to-Right)
     * and filters out small distant background text to focus purely on the foreground document/sign.
     */
    private fun formatIntoReadingOrder(visionText: Text, filterBackground: Boolean = true): String {
        val lineItems = mutableListOf<TextLineItem>()
        val lineHeights = mutableListOf<Int>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val cleanedLine = cleanLine(line.text)
                val box = line.boundingBox
                if (cleanedLine.isNotBlank() && box != null) {
                    val h = box.height()
                    lineHeights.add(h)
                    lineItems.add(TextLineItem(cleanedLine, box.top, box.left, h))
                }
            }
        }

        if (lineItems.isEmpty()) return ""

        // Calculate median line height to filter out tiny background text
        val sortedHeights = lineHeights.sorted()
        val medianHeight = if (sortedHeights.isNotEmpty()) sortedHeights[sortedHeights.size / 2] else 0

        // Filter out text lines that are significantly smaller than the foreground text (background noise)
        val itemsToUse = if (filterBackground && medianHeight > 8) {
            val filtered = lineItems.filter { it.height >= (medianHeight * 0.45f) }
            if (filtered.isNotEmpty()) filtered else lineItems
        } else {
            lineItems
        }

        // Group lines into rows using a vertical threshold (25 pixels)
        // and sort top-to-bottom, then left-to-right within each row.
        val rowThreshold = 25
        val sortedLines = itemsToUse.sortedWith(Comparator { a, b ->
            val topDiff = a.top - b.top
            if (abs(topDiff) <= rowThreshold) {
                a.left - b.left // Same visual line/row, sort left to right
            } else {
                topDiff // Different rows, sort top to bottom
            }
        })

        // Remove duplicate lines while preserving sequence
        val uniqueLines = mutableListOf<String>()
        for (item in sortedLines) {
            if (!uniqueLines.contains(item.text)) {
                uniqueLines.add(item.text)
            }
        }

        // Join lines into structured sentences
        val joined = uniqueLines.joinToString(". ") { it.removeSuffix(".") }
        return if (joined.endsWith(".")) joined else "$joined."
    }

    /**
     * Strictly cleans and sanitizes text to ensure 100% English accuracy:
     * - Retains strictly English letters (A-Z, a-z), numbers (0-9), and basic punctuation (. , ! - ?)
     * - Strips isolated single stray character noise (except 'a', 'A', 'I' and numbers)
     */
    private fun cleanLine(raw: String): String {
        // Strip out non-English symbols and non-ASCII noise
        val sanitized = raw.replace(Regex("[^a-zA-Z0-9\\s.,!?-]"), " ")
        
        // Remove isolated stray single-letter noise artifacts caused by camera grain/speckles
        val words = sanitized.split(Regex("\\s+")).filter { word ->
            word.length > 1 || word.equals("a", ignoreCase = true) || word.equals("I", ignoreCase = true) || word.all { it.isDigit() }
        }

        return words.joinToString(" ").trim()
    }

    fun close() {
        // ML Kit clients close gracefully
    }
}
