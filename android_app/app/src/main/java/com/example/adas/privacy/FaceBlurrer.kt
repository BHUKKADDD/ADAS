package com.example.adas.privacy

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.media.FaceDetector
import kotlin.math.roundToInt

/**
 * On-device PII face detection for redaction. Returns normalized [0,1] face
 * rectangles that the overlay covers with opaque boxes, so faces never reach the
 * screen (or, later, recording/upload) in the clear — all on-device.
 *
 * Uses the dependency-free framework [android.media.FaceDetector] (no model asset,
 * fully offline) so the privacy pipeline builds and runs anywhere. It's a basic
 * frontal-face detector; MediaPipe Face Detection is the production upgrade for
 * angled faces and higher recall (drop-in behind this same class).
 */
class FaceBlurrer(
    private val maxFaces: Int = 4,
    private val targetWidth: Int = 320
) {
    // FaceDetector wants a fixed-size result array.
    private val faces = arrayOfNulls<FaceDetector.Face>(maxFaces)

    /** Detect faces in [source] (any config). Returns normalized [0,1] rects. */
    fun detect(source: Bitmap): List<RectF> {
        // FaceDetector requires an RGB_565 bitmap with an even width.
        val scale = targetWidth.toFloat() / source.width
        val w = targetWidth.let { if (it % 2 != 0) it - 1 else it }
        val h = (source.height * scale).roundToInt().let { if (it % 2 != 0) it - 1 else it }
        if (w < 2 || h < 2) return emptyList()

        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        val rgb565 =
            if (scaled.config == Bitmap.Config.RGB_565) scaled
            else scaled.copy(Bitmap.Config.RGB_565, false)

        val detector = FaceDetector(w, h, maxFaces)
        val n = detector.findFaces(rgb565, faces)

        val result = ArrayList<RectF>(n)
        val mid = PointF()
        for (i in 0 until n) {
            val f = faces[i] ?: continue
            f.getMidPoint(mid)
            val eye = f.eyesDistance()
            // Grow the eye-distance into a face-covering box (a bit taller than
            // wide, extended downward past the chin).
            val halfW = eye * 1.4f
            val left   = ((mid.x - halfW) / w).coerceIn(0f, 1f)
            val right  = ((mid.x + halfW) / w).coerceIn(0f, 1f)
            val top    = ((mid.y - eye * 1.5f) / h).coerceIn(0f, 1f)
            val bottom = ((mid.y + eye * 2.1f) / h).coerceIn(0f, 1f)
            result.add(RectF(left, top, right, bottom))
        }
        return result
    }
}
