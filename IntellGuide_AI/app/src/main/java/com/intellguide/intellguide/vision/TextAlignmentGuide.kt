package com.intellguide.intellguide.vision

import com.google.mlkit.vision.text.Text
import kotlin.math.abs

/**
 * Analyzes lightweight ML Kit text-detection results from the live preview
 * to produce audio guidance cues ("move closer", "hold steady", "text centered")
 * and decides when text is well-aligned enough to trigger an auto-capture.
 *
 * This class does NOT run OCR itself — it only interprets the bounding-box
 * geometry that ML Kit's cheap text detector already provides.
 */
class TextAlignmentGuide(
    /** Frame width in pixels (from ImageProxy / ImageAnalysis). */
    private val frameWidth: Int,
    /** Frame height in pixels. */
    private val frameHeight: Int
) {

    // ── Guidance result ──────────────────────────────────────────────────────
    enum class Guidance {
        NO_TEXT,            // Nothing detected
        MOVE_CLOSER,        // Text too small / far away
        MOVE_LEFT,          // Bulk of text is to the right
        MOVE_RIGHT,         // Bulk of text is to the left
        HOLD_STEADY,        // Almost there — small jitter
        TEXT_CENTERED       // Good alignment — safe to capture
    }

    data class AlignmentResult(
        val guidance: Guidance,
        val message: String,
        val readyToCapture: Boolean
    )

    // ── Thresholds ───────────────────────────────────────────────────────────

    /** Minimum fraction of frame height the text bounding box should span. */
    private val minHeightFraction = 0.08f

    /** The centre region of the frame (±tolerance from midpoint as a fraction). */
    private val centerTolerance = 0.18f

    /** Minimum number of text lines detected to even consider capturing. */
    private val minLinesForCapture = 2

    /** How many consecutive "centered" frames we need before auto-capture. */
    private val steadyFramesRequired = 3

    private var consecutiveSteadyFrames = 0

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Call once per analyzed frame with the raw [Text] result from ML Kit's
     * cheap text recognizer.  Returns an [AlignmentResult] that tells the UI
     * what to speak and whether auto-capture should fire.
     */
    fun evaluate(visionText: Text): AlignmentResult {
        val blocks = visionText.textBlocks
        if (blocks.isEmpty()) {
            consecutiveSteadyFrames = 0
            return AlignmentResult(Guidance.NO_TEXT, "No text detected. Point camera at text.", false)
        }

        // ── Aggregate bounding box of ALL detected text ──────────────────
        var minLeft = Int.MAX_VALUE
        var minTop = Int.MAX_VALUE
        var maxRight = Int.MIN_VALUE
        var maxBottom = Int.MIN_VALUE
        var totalLines = 0

        for (block in blocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (box.left < minLeft) minLeft = box.left
                if (box.top < minTop) minTop = box.top
                if (box.right > maxRight) maxRight = box.right
                if (box.bottom > maxBottom) maxBottom = box.bottom
                totalLines++
            }
        }

        if (totalLines == 0) {
            consecutiveSteadyFrames = 0
            return AlignmentResult(Guidance.NO_TEXT, "No text detected. Point camera at text.", false)
        }

        val textWidth = maxRight - minLeft
        val textHeight = maxBottom - minTop

        // ── 1. Size check — is the user too far away? ────────────────────
        val heightFraction = textHeight.toFloat() / frameHeight
        if (heightFraction < minHeightFraction) {
            consecutiveSteadyFrames = 0
            return AlignmentResult(Guidance.MOVE_CLOSER, "Move closer to the text.", false)
        }

        // ── 2. Horizontal centering check ────────────────────────────────
        val textCenterX = (minLeft + maxRight) / 2f
        val frameCenterX = frameWidth / 2f
        val offsetFraction = (textCenterX - frameCenterX) / frameWidth

        if (offsetFraction > centerTolerance) {
            consecutiveSteadyFrames = 0
            return AlignmentResult(Guidance.MOVE_LEFT, "Move phone slightly left.", false)
        }
        if (offsetFraction < -centerTolerance) {
            consecutiveSteadyFrames = 0
            return AlignmentResult(Guidance.MOVE_RIGHT, "Move phone slightly right.", false)
        }

        // ── 3. Enough lines for a useful capture? ────────────────────────
        if (totalLines < minLinesForCapture) {
            consecutiveSteadyFrames = 0
            return AlignmentResult(Guidance.MOVE_CLOSER, "Move closer. Only partial text visible.", false)
        }

        // ── 4. Stability — require consecutive aligned frames ────────────
        consecutiveSteadyFrames++
        if (consecutiveSteadyFrames < steadyFramesRequired) {
            return AlignmentResult(Guidance.HOLD_STEADY, "Hold steady.", false)
        }

        // ── 5. Ready! ────────────────────────────────────────────────────
        return AlignmentResult(Guidance.TEXT_CENTERED, "Text centered. Capturing.", true)
    }

    /** Reset after a successful capture so we don't re-trigger immediately. */
    fun resetStability() {
        consecutiveSteadyFrames = 0
    }
}
