package com.example.adas

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.adas.privacy.FaceBlurrer
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CameraX [ImageAnalysis.Analyzer] that extracts frames from the camera pipeline
 * and passes them to the [InferenceEngine] on a background coroutine.
 *
 * Results are delivered via the [onResults] callback on the **main** thread,
 * ready to drive a recomposition of the overlay.
 *
 * ── Orientation fix ─────────────────────────────────────────────────────────
 * The raw analysis buffer is in sensor orientation. We apply
 * [ImageProxy.getImageInfo].rotationDegrees so inference runs on an upright
 * frame that matches what the preview displays (a model trained on upright road
 * images cannot recognize a sideways autorickshaw). The upright frame's aspect
 * ratio is reported via [onFrameAspect] so the overlay can map boxes into the
 * letterboxed (FIT_CENTER) preview rect.
 */
class FrameAnalyzer(
    private val engine: InferenceEngine,
    private val onResults: (List<Detection>) -> Unit,
    private val onFrameAspect: (Float) -> Unit = {},
    private val onFaces: (List<RectF>) -> Unit = {},
    private val enableFaceBlur: Boolean = true
) : ImageAnalysis.Analyzer, Closeable {

    // A supervised scope so one failed frame doesn't cancel the entire pipeline
    private val analyzerJob: Job = SupervisorJob()
    private val analyzerScope = CoroutineScope(analyzerJob + Dispatchers.Default)
    private var pendingJob: Job? = null

    // Report aspect ratio only when it changes (avoids spamming state updates)
    private var lastReportedAspect: Float = 0f

    // On-device PII face redaction (runs on the same upright frame as inference,
    // every other frame to spare the CPU budget).
    private val faceBlurrer = FaceBlurrer()
    private var frameCount = 0L

    override fun analyze(image: ImageProxy) {
        if (pendingJob?.isActive == true) {
            image.close()
            return
        }

        val rotation = image.imageInfo.rotationDegrees

        // toBitmap() returns the raw (sensor-oriented) buffer. Rotate it to the
        // display orientation so inference sees an upright frame.
        val bitmap: Bitmap? = try {
            val raw = image.toBitmap()
            if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            } else {
                raw
            }
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }

        if (bitmap != null) {
            val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
            if (aspect != lastReportedAspect) {
                lastReportedAspect = aspect
                onFrameAspect(aspect)
            }
            val doFaces = enableFaceBlur && (frameCount++ % 2 == 0L)
            pendingJob = analyzerScope.launch {
                val results = engine.detect(bitmap)
                val faces = if (doFaces) faceBlurrer.detect(bitmap) else null
                withContext(Dispatchers.Main) {
                    onResults(results)
                    if (faces != null) onFaces(faces)
                }
            }
        }
    }

    override fun close() {
        analyzerScope.cancel()
    }
}
