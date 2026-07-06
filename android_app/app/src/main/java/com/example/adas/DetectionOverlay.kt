package com.example.adas

import android.graphics.RectF
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adas.theme.AdasColors
import com.example.adas.theme.AdasTheme

/**
 * Transparent Compose [Canvas] overlay drawn on top of the camera feed.
 * Fully supports dynamic Light/Dark theme configurations.
 */
@Composable
fun DetectionOverlay(
    detections: List<Detection>,
    viewModel: AdasViewModel,
    modifier: Modifier = Modifier
) {
    val colors = AdasTheme.colors
    val textMeasurer = rememberTextMeasurer()
    val showDistance by viewModel.showDistanceEstimates.collectAsState()
    val showScanLine by viewModel.showScanLine.collectAsState()
    val frameAspect by viewModel.frameAspect.collectAsState()
    val faceBoxes by viewModel.faceBoxes.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val scanlineY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanlineY"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        // ── Scanline sweep ─────────────────────────────────────────────────────
        if (showScanLine) {
            val sy = scanlineY * size.height
            drawLine(
                color = colors.cyan.copy(alpha = 0.08f),
                start = Offset(0f, sy),
                end   = Offset(size.width, sy),
                strokeWidth = 1.5.dp.toPx()
            )
            // Soft glow below line
            drawRect(
                color = colors.cyan.copy(alpha = 0.025f),
                topLeft = Offset(0f, sy),
                size = Size(size.width, 24.dp.toPx())
            )
        }

        // ── Compute the FIT_CENTER content rect for the camera frame ───────────
        // Falls back to the full view if the frame aspect isn't known yet.
        val content: Rect = if (frameAspect <= 0f) {
            Rect(0f, 0f, size.width, size.height)
        } else {
            val viewAspect = size.width / size.height
            if (frameAspect > viewAspect) {
                // Frame wider than view → letterbox top/bottom
                val ch = size.width / frameAspect
                val oy = (size.height - ch) / 2f
                Rect(0f, oy, size.width, oy + ch)
            } else {
                // Frame taller than view → pillarbox left/right
                val cw = size.height * frameAspect
                val ox = (size.width - cw) / 2f
                Rect(ox, 0f, ox + cw, size.height)
            }
        }

        // ── Detections ─────────────────────────────────────────────────────────
        detections.forEach { detection ->
            drawDetection(detection, content, textMeasurer, showDistance, colors)
        }

        // ── PII face redaction (on-device) ─────────────────────────────────────
        // Drawn last so the opaque cover sits on top of everything — a detected
        // face never reaches the screen in the clear.
        faceBoxes.forEach { box ->
            drawFaceRedaction(box, content, textMeasurer)
        }
    }
}

/** Cover a detected face with an opaque, pixelated redaction box + "PII" tag. */
private fun DrawScope.drawFaceRedaction(
    box: RectF,
    content: Rect,
    textMeasurer: TextMeasurer
) {
    val left   = content.left + box.left   * content.width
    val top    = content.top  + box.top    * content.height
    val right  = content.left + box.right  * content.width
    val bottom = content.top  + box.bottom * content.height
    val w = right - left
    val h = bottom - top
    if (w <= 0f || h <= 0f) return

    // Opaque base so nothing shows through.
    drawRect(color = Color.Black.copy(alpha = 0.92f), topLeft = Offset(left, top), size = Size(w, h))

    // Mosaic/pixelation hint.
    val cell = (minOf(w, h) / 6f).coerceAtLeast(6f)
    var y = top; var row = 0
    while (y < bottom) {
        var x = left; var col = 0
        while (x < right) {
            val a = if ((row + col) % 2 == 0) 0.10f else 0.22f
            drawRect(
                color = Color.White.copy(alpha = a),
                topLeft = Offset(x, y),
                size = Size(minOf(cell, right - x), minOf(cell, bottom - y))
            )
            x += cell; col++
        }
        y += cell; row++
    }

    // Border + tag.
    drawRect(
        color = Color.White.copy(alpha = 0.5f),
        topLeft = Offset(left, top),
        size = Size(w, h),
        style = Stroke(width = 1.5.dp.toPx())
    )
    val style = TextStyle(
        color = Color.White,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace
    )
    drawText(
        textMeasurer = textMeasurer,
        text = "PII",
        style = style,
        topLeft = Offset(left + 3.dp.toPx(), top + 3.dp.toPx())
    )
}

private fun DrawScope.drawDetection(
    detection: Detection,
    content: Rect,
    textMeasurer: TextMeasurer,
    showDistance: Boolean,
    colors: AdasColors
) {
    val box   = detection.boundingBox
    val color = when (detection.label) {
        // Vulnerable / unpredictable road users — highest priority (pink)
        "person", "rider", "bicycle", "animal" -> colors.pink
        // Smaller vehicles (amber)
        "motorcycle", "car", "autorickshaw"    -> colors.amber
        // Large vehicles (cyan)
        "truck", "bus", "vehicle_fallback"     -> colors.cyan
        // Informational (green)
        "traffic_sign", "traffic_light"        -> colors.green
        else                                   -> colors.textPrimary
    }

    // Map normalized [0,1] coords into the letterboxed content rect
    val left   = content.left + box.left   * content.width
    val top    = content.top  + box.top    * content.height
    val right  = content.left + box.right  * content.width
    val bottom = content.top  + box.bottom * content.height
    val w = right - left
    val h = bottom - top

    // ── Box fill (very subtle, adds depth) ────────────────────────────────────
    drawRect(
        color = color.copy(alpha = 0.06f),
        topLeft = Offset(left, top),
        size = Size(w, h)
    )

    // ── Bounding box stroke ────────────────────────────────────────────────────
    drawRect(
        color = color.copy(alpha = 0.85f),
        topLeft = Offset(left, top),
        size = Size(w, h),
        style = Stroke(width = 2.dp.toPx())
    )

    // ── Corner accent marks (L-shaped brackets) ───────────────────────────────
    val cornerLen = minOf(w, h) * 0.20f
    val cornerStroke = 3.dp.toPx()

    // top-left
    drawLine(color, Offset(left, top + cornerLen), Offset(left, top), cornerStroke)
    drawLine(color, Offset(left, top), Offset(left + cornerLen, top), cornerStroke)
    // top-right
    drawLine(color, Offset(right - cornerLen, top), Offset(right, top), cornerStroke)
    drawLine(color, Offset(right, top), Offset(right, top + cornerLen), cornerStroke)
    // bottom-left
    drawLine(color, Offset(left, bottom - cornerLen), Offset(left, bottom), cornerStroke)
    drawLine(color, Offset(left, bottom), Offset(left + cornerLen, bottom), cornerStroke)
    // bottom-right
    drawLine(color, Offset(right - cornerLen, bottom), Offset(right, bottom), cornerStroke)
    drawLine(color, Offset(right, bottom), Offset(right, bottom - cornerLen), cornerStroke)

    // ── Distance estimate (mock: inversely proportional to box height) ────────
    val distanceText = if (showDistance) {
        val boxFraction = (bottom - top) / content.height
        val estimatedMeters = ((1f / boxFraction) * 5f).coerceIn(2f, 60f)
        "~${estimatedMeters.toInt()}m"
    } else null

    // ── Label badge ────────────────────────────────────────────────────────────
    val confText  = "%.0f%%".format(detection.confidence * 100f)
    val labelLine = "${detection.label.uppercase()}  $confText"
    val distLine  = distanceText ?: ""

    val labelStyle = TextStyle(
        color      = colors.background,
        fontSize   = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace
    )
    val distStyle = TextStyle(
        color      = colors.background.copy(alpha = 0.8f),
        fontSize   = 9.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.Monospace
    )

    val measuredLabel = textMeasurer.measure(labelLine, labelStyle)
    val measuredDist  = if (distLine.isNotEmpty()) textMeasurer.measure(distLine, distStyle) else null

    val pad    = 4.dp.toPx()
    val badgeW = maxOf(measuredLabel.size.width.toFloat(), measuredDist?.size?.width?.toFloat() ?: 0f) + pad * 2
    val badgeH = measuredLabel.size.height.toFloat() +
            (measuredDist?.size?.height?.toFloat() ?: 0f) +
            pad * (if (measuredDist != null) 3f else 2f)
    val badgeTop = (top - badgeH).coerceAtLeast(0f)

    // Badge background
    drawRect(
        color   = color,
        topLeft = Offset(left, badgeTop),
        size    = Size(badgeW, badgeH)
    )

    // Class + confidence text
    drawText(
        textMeasurer = textMeasurer,
        text  = labelLine,
        style = labelStyle,
        topLeft = Offset(left + pad, badgeTop + pad)
    )

    // Distance text (below class label)
    if (measuredDist != null) {
        drawText(
            textMeasurer = textMeasurer,
            text  = distLine,
            style = distStyle,
            topLeft = Offset(left + pad, badgeTop + pad + measuredLabel.size.height + 2.dp.toPx())
        )
    }
}
