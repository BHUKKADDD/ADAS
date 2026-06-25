package com.example.adas

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
 * ── Aspect-ratio fix ────────────────────────────────────────────────────────
 * The raw analysis buffer is in sensor orientation. We apply
 * [ImageProxy.getImageInfo].rotationDegrees so inference runs on an upright
 * frame that matches what the preview displays, then report the upright frame's
 * aspect ratio via [onFrameAspect] so the overlay can map boxes into the
 * letterboxed (FIT_CENTER) preview rect.
 */
class FrameAnalyzer(
    private val engine: InferenceEngine,
    private val onResults: (List<Detection>) -> Unit,
    private val onFrameAspect: (Float) -> Unit = {}
) : ImageAnalysis.Analyzer, Closeable {

    // A supervised scope so one failed frame doesn't cancel the entire pipeline
    private val analyzerJob: Job = SupervisorJob()
    private val analyzerScope = CoroutineScope(analyzerJob + Dispatchers.Default)
    private var pendingJob: Job? = null

    // Report aspect ratio only when it changes (avoids spamming state updates)
    private var lastReportedAspect: Float = 0f

    override fun analyze(image: ImageProxy) {
        if (pendingJob?.isActive == true) {
            image.close()
            return
        }

        val rotation = image.imageInfo.rotationDegrees

        // Use CameraX's built-in toBitmap() extension — returns the raw (sensor-
        // oriented) buffer. Rotate it to display orientation for inference.
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
            pendingJob = analyzerScope.launch {
                val results = engine.detect(bitmap)
                withContext(Dispatchers.Main) {
                    onResults(results)
                }
            }
        }
    }

    override fun close() {
        analyzerScope.cancel()
    }
}
