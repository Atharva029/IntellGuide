package com.intellguide.intellguide.ui.components

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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.intellguide.intellguide.ui.theme.AccentCyan
import com.intellguide.intellguide.ui.theme.DarkBackground
import com.intellguide.intellguide.ui.theme.PrimaryBlue
import com.intellguide.intellguide.ui.theme.SuccessGreen
import com.intellguide.intellguide.vision.DetectedObjectInfo

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

            val boxColor = if (obj.isObstacleInPath) AccentCyan else SuccessGreen

            // 1. Draw glowing semi-transparent background fill
            drawRoundRect(
                color = boxColor.copy(alpha = 0.12f),
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = CornerRadius(16f, 16f)
            )

            // 2. Draw crisp bounding box stroke
            drawRoundRect(
                color = boxColor,
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = CornerRadius(16f, 16f),
                style = Stroke(width = 3.dp.toPx())
            )

            // 3. Draw top label badge
            val labelText = "${obj.label} ${obj.confidencePercentage}% • ${obj.spatialDirection}"
            val textPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 34f
                isFakeBoldText = true
                isAntiAlias = true
            }

            val textBounds = Rect()
            textPaint.getTextBounds(labelText, 0, labelText.length, textBounds)
            val badgePaddingHorizontal = 16f
            val badgePaddingVertical = 12f
            val badgeWidth = textBounds.width() + (badgePaddingHorizontal * 2)
            val badgeHeight = textBounds.height() + (badgePaddingVertical * 2)

            val badgeTop = (top - badgeHeight).coerceAtLeast(10f)
            val badgeLeft = left.coerceAtLeast(10f)

            // Badge Background
            drawRoundRect(
                color = DarkBackground.copy(alpha = 0.90f),
                topLeft = Offset(badgeLeft, badgeTop),
                size = Size(badgeWidth, badgeHeight),
                cornerRadius = CornerRadius(8f, 8f)
            )

            // Badge Border
            drawRoundRect(
                color = boxColor,
                topLeft = Offset(badgeLeft, badgeTop),
                size = Size(badgeWidth, badgeHeight),
                cornerRadius = CornerRadius(8f, 8f),
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Badge Text
            drawContext.canvas.nativeCanvas.drawText(
                labelText,
                badgeLeft + badgePaddingHorizontal,
                badgeTop + badgeHeight - badgePaddingVertical + 4f,
                textPaint
            )
        }
    }
}
