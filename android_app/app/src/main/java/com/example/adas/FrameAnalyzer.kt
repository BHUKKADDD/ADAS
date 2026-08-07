package com.example.adas

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.adas.lane.LaneDetector
import com.example.adas.lane.LaneLine
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
    private val enableFaceBlur: Boolean = true,
    /**
     * Upright frame plus its best-known face boxes, delivered on the **analysis
     * thread** so the event recorder can redact and compress off the main thread.
     */
    private val onFrameForRecording: (Bitmap, List<RectF>) -> Unit = { _, _ -> },
    /** Fitted lane boundaries (left, right); either may be null. Main thread. */
    private val onLane: (LaneLine?, LaneLine?) -> Unit = { _, _ -> },
    private val enableLaneDetection: Boolean = true
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

    // Most recent face detection, reused on frames where face detection was skipped.
    @Volatile
    private var lastFaces: List<RectF> = emptyList()

    // Lane departure warning (AIS-188): classical CV, no model, no licence.
    private val laneDetector = LaneDetector()

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
            val doFaces = enableFaceBlur && (frameCount % 2 == 0L)
            val doLanes = enableLaneDetection && (frameCount % 3 == 0L)
            frameCount++
            pendingJob = analyzerScope.launch {
                val results = engine.detect(bitmap)
                val faces = if (doFaces) faceBlurrer.detect(bitmap) else null
                if (faces != null) lastFaces = faces

                // Recording happens here, on the analysis thread, and uses the most
                // recent face boxes even on frames where detection was skipped —
                // a slightly stale redaction box is far better than none.
                onFrameForRecording(bitmap, lastFaces)

                // Lane detection every third frame: it is pure CPU pixel work
                // sharing a thread with inference, and lane geometry changes far
                // more slowly than the objects in front of the vehicle.
                val lane = if (doLanes) {
                    val (l, r) = laneDetector.detect(bitmap)
                    if (laneDetector.isPlausibleLane(l, r)) l to r else l to null
                } else null

                withContext(Dispatchers.Main) {
                    onResults(results)
                    if (faces != null) onFaces(faces)
                    lane?.let { (l, r) -> onLane(l, r) }
                }
            }
        }
    }

    override fun close() {
        analyzerScope.cancel()
    }
}
