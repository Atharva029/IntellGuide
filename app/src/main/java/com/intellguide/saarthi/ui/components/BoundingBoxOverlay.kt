package com.intellguide.saarthi.ui.components

import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.intellguide.saarthi.ui.theme.AccentCyan
import com.intellguide.saarthi.ui.theme.DarkBackground
import com.intellguide.saarthi.vision.DetectedObjectInfo
import com.intellguide.saarthi.vision.HazardCategory

@Composable
fun BoundingBoxOverlay(
    detectedObjects: List<DetectedObjectInfo>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        for (obj in detectedObjects) {
            val box = obj.boundingBox
            val left = box.left * canvasWidth
            val top = box.top * canvasHeight
            val right = box.right * canvasWidth
            val bottom = box.bottom * canvasHeight
            val width = right - left
            val height = bottom - top

            // Visual Hierarchy Colors
            val boxColor = when {
                obj.isImmediateCollisionRisk -> Color(0xFFEF4444) // Neon Red (Immediate Hazard)
                obj.hazardCategory == HazardCategory.CRITICAL_WALKING_HAZARD -> AccentCyan // Neon Cyan (Path Obstacle)
                else -> Color(0xFF64748B) // Subdued Slate (Secondary Tabletop Item)
            }

            // 1. Semi-transparent background fill
            drawRoundRect(
                color = boxColor.copy(alpha = if (obj.isImmediateCollisionRisk) 0.16f else 0.08f),
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = CornerRadius(14f, 14f)
            )

            // 2. Crisp bounding box stroke
            drawRoundRect(
                color = boxColor,
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = CornerRadius(14f, 14f),
                style = Stroke(width = if (obj.isImmediateCollisionRisk) 3.5.dp.toPx() else 2.dp.toPx())
            )

            // 3. Top Label Badge
            val labelText = if (obj.hazardCategory == HazardCategory.CRITICAL_WALKING_HAZARD) {
                "${obj.label} ${obj.confidencePercentage}% • ${obj.spatialDirection}"
            } else {
                "${obj.label} (Item)"
            }

            val textPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 32f
                isFakeBoldText = true
                isAntiAlias = true
            }

            val textBounds = Rect()
            textPaint.getTextBounds(labelText, 0, labelText.length, textBounds)
            val badgePaddingHorizontal = 14f
            val badgePaddingVertical = 10f
            val badgeWidth = textBounds.width() + (badgePaddingHorizontal * 2)
            val badgeHeight = textBounds.height() + (badgePaddingVertical * 2)

            val badgeTop = (top - badgeHeight).coerceAtLeast(8f)
            val badgeLeft = left.coerceAtLeast(8f)

            // Badge Background
            drawRoundRect(
                color = DarkBackground.copy(alpha = 0.92f),
                topLeft = Offset(badgeLeft, badgeTop),
                size = Size(badgeWidth, badgeHeight),
                cornerRadius = CornerRadius(6f, 6f)
            )

            // Badge Border
            drawRoundRect(
                color = boxColor,
                topLeft = Offset(badgeLeft, badgeTop),
                size = Size(badgeWidth, badgeHeight),
                cornerRadius = CornerRadius(6f, 6f),
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Badge Text
            drawContext.canvas.nativeCanvas.drawText(
                labelText,
                badgeLeft + badgePaddingHorizontal,
                badgeTop + badgeHeight - badgePaddingVertical + 3f,
                textPaint
            )
        }
    }
}
