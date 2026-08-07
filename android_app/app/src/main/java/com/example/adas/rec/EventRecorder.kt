package com.example.adas.rec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * Persists event clips from the analysis stream.
 *
 * **Why analysis frames rather than a CameraX `VideoCapture`.** Three reasons,
 * in order of weight:
 *
 *  1. *Privacy.* Frames are redacted **before** they enter the buffer, so PII
 *     never reaches storage in the clear. A parallel `VideoCapture` pipeline
 *     writes the raw sensor stream straight to disk, which is exactly what the
 *     privacy roadmap said must not happen.
 *  2. *No native ring buffer.* CameraX's `Recorder` has no pre-roll concept, so a
 *     video path needs segment-and-stitch plumbing anyway.
 *  3. *Use-case contention.* A second video use case competes with the front
 *     camera the DMS wants; analysis frames are already flowing.
 *
 * The trade is fidelity: clips are ~15 FPS JPEG sequences at analysis resolution,
 * not H.264. That is the right currency for what consumes them (upload, scene
 * graphs, VLM training), and muxing the sequence to MP4 with `MediaCodec` +
 * `MediaMuxer` is a self-contained upgrade behind [writeClip].
 *
 * Layout on disk, under `filesDir/events/<triggeredAtMs>/`:
 *   - `frame_0000.jpg` … one per buffered frame, already redacted
 *   - `event.json` … reason, timestamps, frame index
 */
class EventRecorder(
    private val context: Context,
    private val buffer: EventBuffer<ByteArray> = EventBuffer(),
    private val jpegQuality: Int = 70,
    private val maxEdgePx: Int = 640
) {

    /** What the HUD needs to show about recording. */
    data class RecorderState(
        val isRecording: Boolean = false,
        val bufferedFrames: Int = 0,
        val clipsWritten: Int = 0,
        val lastClipPath: String? = null,
        val lastError: String? = null
    )

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val redactionPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val eventsDir: File by lazy {
        File(context.filesDir, "events").apply { mkdirs() }
    }

    /**
     * Offer one analysis frame. Call from the analyzer's background thread — this
     * redacts, scales and JPEG-compresses, none of which belongs on the main
     * thread.
     *
     * @param faceBoxes normalized [0,1] face rects to black out before storage.
     */
    fun offerFrame(bitmap: Bitmap, faceBoxes: List<RectF>, tMs: Long) {
        val jpeg = try {
            encode(bitmap, faceBoxes)
        } catch (e: Exception) {
            Log.w(TAG, "frame encode failed", e)
            _state.value = _state.value.copy(lastError = "encode: ${e.message}")
            return
        }

        val clip = buffer.add(jpeg, tMs)
        _state.value = _state.value.copy(
            isRecording = buffer.isRecording,
            bufferedFrames = buffer.bufferedFrames
        )
        if (clip != null) persist(clip)
    }

    /** Request a clip. Safe to call every frame; the buffer handles cooldown. */
    fun trigger(reason: String, tMs: Long): Boolean {
        val started = buffer.trigger(reason, tMs)
        if (started) {
            _state.value = _state.value.copy(isRecording = buffer.isRecording)
        }
        return started
    }

    /** Close any in-progress clip (camera teardown / app backgrounded). */
    fun flush(tMs: Long) {
        buffer.flush(tMs)?.let { persist(it) }
        _state.value = _state.value.copy(isRecording = false)
    }

    fun reset() {
        buffer.reset()
        _state.value = RecorderState()
    }

    /** Redact, downscale and compress in one pass. */
    private fun encode(source: Bitmap, faceBoxes: List<RectF>): ByteArray {
        val scale = maxEdgePx.toFloat() / maxOf(source.width, source.height)
        val working = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        }

        // A scaled copy is already mutable; a same-size copy was made mutable above.
        if (faceBoxes.isNotEmpty()) {
            val canvas = Canvas(working)
            val w = working.width.toFloat()
            val h = working.height.toFloat()
            for (box in faceBoxes) {
                canvas.drawRect(
                    box.left * w, box.top * h, box.right * w, box.bottom * h,
                    redactionPaint
                )
            }
        }

        return java.io.ByteArrayOutputStream().use { out ->
            working.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            out.toByteArray()
        }
    }

    private fun persist(clip: Clip<ByteArray>) {
        try {
            val dir = File(eventsDir, clip.triggeredAtMs.toString()).apply { mkdirs() }
            val index = JSONArray()
            clip.frames.forEachIndexed { i, frame ->
                val name = "frame_%04d.jpg".format(i)
                File(dir, name).writeBytes(frame.payload)
                index.put(JSONObject().put("file", name).put("tMs", frame.tMs))
            }
            val manifest = JSONObject()
                .put("reason", clip.reason)
                .put("triggeredAtMs", clip.triggeredAtMs)
                .put("frameCount", clip.frameCount)
                .put("durationMs", clip.durationMs)
                .put("redacted", true)
                .put("format", "jpeg-sequence")
                .put("frames", index)
            File(dir, "event.json").writeText(manifest.toString(2))

            _state.value = _state.value.copy(
                isRecording = false,
                bufferedFrames = buffer.bufferedFrames,
                clipsWritten = _state.value.clipsWritten + 1,
                lastClipPath = dir.absolutePath,
                lastError = null
            )
            Log.i(TAG, "clip written: ${dir.name} (${clip.frameCount} frames, ${clip.durationMs}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "clip write failed", e)
            _state.value = _state.value.copy(isRecording = false, lastError = "write: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "EventRecorder"
    }
}
