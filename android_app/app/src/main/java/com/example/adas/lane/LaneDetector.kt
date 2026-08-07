package com.example.adas.lane

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Extracts lane-boundary candidates from a camera frame.
 *
 * Deliberately simple and allocation-light, because this runs alongside TFLite
 * inference, face detection and JPEG encoding on the same analysis thread:
 *
 *  1. Sample a handful of scanlines across the lower half of the frame (above the
 *     bonnet, below the horizon).
 *  2. On each scanline, walk outward from the image centre looking for the
 *     bright-dark luminance step that a painted marking makes against tarmac.
 *  3. Least-squares fit the accepted points into a left and a right boundary.
 *
 * No Hough transform: a full accumulator is far more expensive than this, and
 * with the search anchored at the vehicle's own position the extra generality
 * buys nothing. The fitting and the decision live in [LaneGeometry] where they
 * can be tested.
 */
class LaneDetector(
    private val scanlines: Int = 12,
    private val roiTop: Float = 0.55f,
    private val roiBottom: Float = 0.97f,
    /** Luminance step over [gradientRun] px that counts as a marking edge. */
    private val gradientThreshold: Int = 34,
    private val gradientRun: Int = 3,
    private val sampleWidth: Int = 240,
    private val minSupport: Int = 6
) {
    private var rowBuffer = IntArray(0)

    /** Fitted (left, right) boundaries; either may be null when not found. */
    fun detect(frame: Bitmap): Pair<LaneLine?, LaneLine?> {
        val w = sampleWidth.coerceAtMost(frame.width)
        if (w < 16 || frame.height < 16) return null to null
        val scale = w.toFloat() / frame.width
        val h = (frame.height * scale).toInt().coerceAtLeast(16)

        val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
        if (rowBuffer.size < w) rowBuffer = IntArray(w)

        val leftPoints = ArrayList<Pair<Float, Float>>(scanlines)
        val rightPoints = ArrayList<Pair<Float, Float>>(scanlines)

        val yStart = (h * roiTop).toInt()
        val yEnd = (h * roiBottom).toInt().coerceAtMost(h - 1)
        val step = ((yEnd - yStart) / scanlines).coerceAtLeast(1)

        var y = yStart
        while (y <= yEnd) {
            scaled.getPixels(rowBuffer, 0, w, 0, y, w, 1)
            val yNorm = y.toFloat() / h
            val center = w / 2

            findEdge(w, center, -1)?.let { x ->
                leftPoints.add(x.toFloat() / w to yNorm)
            }
            findEdge(w, center, +1)?.let { x ->
                rightPoints.add(x.toFloat() / w to yNorm)
            }
            y += step
        }

        return fitLaneLine(leftPoints, minSupport) to fitLaneLine(rightPoints, minSupport)
    }

    /**
     * Walk outward from [from] in [direction] looking for the first strong
     * bright-ward luminance step — the inner edge of a painted marking.
     */
    private fun findEdge(width: Int, from: Int, direction: Int): Int? {
        var x = from
        val limit = if (direction < 0) gradientRun else width - gradientRun - 1
        while (if (direction < 0) x > limit else x < limit) {
            val here = luma(rowBuffer[x])
            val ahead = luma(rowBuffer[x + direction * gradientRun])
            if (ahead - here >= gradientThreshold) return x + direction * gradientRun
            x += direction
        }
        return null
    }

    /** Integer luma approximation; avoids float work in the inner loop. */
    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 77 + g * 151 + b * 28) shr 8
    }

    /** True when the two boundaries are plausibly a lane rather than noise. */
    fun isPlausibleLane(left: LaneLine?, right: LaneLine?): Boolean {
        if (left == null || right == null) return false
        val widthAtBottom = right.xAt(0.95f) - left.xAt(0.95f)
        // A lane should occupy a large share of the frame near the bumper, and the
        // boundaries must converge with distance, not diverge.
        val widthAtTop = right.xAt(0.6f) - left.xAt(0.6f)
        return widthAtBottom in 0.25f..1.4f && widthAtTop < widthAtBottom &&
            abs(widthAtTop) > 0.02f
    }
}
