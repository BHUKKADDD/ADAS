package com.example.adas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * On-device YOLOv8n inference using TensorFlow Lite.
 *
 * Setup requirements:
 *  1. Export model: `yolo export model=yolov8n.pt format=tflite imgsz=320`
 *  2. Place `yolov8n.tflite` in app/src/main/assets/
 *  3. Place `coco_labels.txt` in app/src/main/assets/
 *
 * YOLOv8n TFLite output layout: [1, 84, 8400]
 *   - Rows 0–3    : cx, cy, w, h  (normalized [0,1] relative to the INPUT_SIZE square)
 *   - Rows 4–83   : class scores  (already sigmoided by TFLite export)
 *   - Columns     : anchor predictions
 *
 * ── Aspect-ratio fix ────────────────────────────────────────────────────────
 * Instead of anisotropically stretching the frame to INPUT_SIZE×INPUT_SIZE
 * (which distorts objects and hurts detection), the frame is **letterboxed**:
 * scaled preserving aspect ratio and padded to a square with neutral gray.
 * The padding/scale is then undone on the output coords so every box is
 * returned in normalized [0,1] relative to the ORIGINAL frame.
 */
class InferenceEngine(private val context: Context) {

    companion object {
        private const val MODEL_FILENAME       = "yolov8n.tflite"
        private const val LABELS_FILENAME      = "coco_labels.txt"
        private const val INPUT_SIZE           = 320
        private const val CONFIDENCE_THRESHOLD = 0.40f
        private const val IOU_THRESHOLD        = 0.45f
        private const val NUM_CLASSES          = 80
        // Anchors for imgsz=320: feature maps 40×40 + 20×20 + 10×10 = 2100
        // (Would be 8400 for imgsz=640: 80×80 + 40×40 + 20×20)
        private const val NUM_ANCHORS          = 2100
        // YOLO letterbox pad color (neutral gray)
        private const val PAD_GRAY             = 114
    }

    /** Letterbox transform applied to a frame, used to undo padding on outputs. */
    private data class Letterbox(
        val padX: Float,
        val padY: Float,
        val contentW: Float,   // scaled frame width inside the square (px)
        val contentH: Float,   // scaled frame height inside the square (px)
        val size: Int          // square side (= INPUT_SIZE)
    )

    // Lazy-loaded — model file is memory-mapped from assets on first use
    private val interpreter: Interpreter by lazy {
        val model   = loadModelFile(context, MODEL_FILENAME)
        val options = Interpreter.Options().apply { numThreads = 4 }
        Interpreter(model, options)
    }

    /**
     * Loads a TFLite model from the app's assets folder as a [MappedByteBuffer].
     */
    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = java.io.FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    // 80 COCO class names loaded once from assets
    private val labels: List<String> by lazy {
        context.assets.open(LABELS_FILENAME)
            .let { BufferedReader(InputStreamReader(it)) }
            .readLines()
            .filter { it.isNotBlank() }
    }

    /**
     * Run YOLOv8n inference on [bitmap].
     * Executes on [Dispatchers.Default] (background thread).
     * Returns normalized [Detection] objects (coords in [0,1] of the original frame).
     */
    suspend fun detect(bitmap: Bitmap): List<Detection> = withContext(Dispatchers.Default) {
        val (inputBuffer, letterbox) = preprocessBitmap(bitmap)

        // Output buffer: [1, 84, NUM_ANCHORS]
        val outputArray = Array(1) { Array(84) { FloatArray(NUM_ANCHORS) } }

        interpreter.run(inputBuffer, outputArray)

        val rawDetections = parseOutput(outputArray[0], letterbox)
        applyNms(rawDetections)
    }

    // ── Pre-processing ────────────────────────────────────────────────────────

    /**
     * Letterbox [bitmap] into an INPUT_SIZE×INPUT_SIZE square preserving aspect
     * ratio (gray padding), and pack into a float32 ByteBuffer in RGB order,
     * pixels normalized to [0,1].
     *
     * Returns the buffer plus the [Letterbox] transform needed to undo padding.
     */
    private fun preprocessBitmap(bitmap: Bitmap): Pair<ByteBuffer, Letterbox> {
        val s = INPUT_SIZE
        val scale = minOf(s.toFloat() / bitmap.width, s.toFloat() / bitmap.height)
        val contentW = Math.round(bitmap.width * scale)
        val contentH = Math.round(bitmap.height * scale)
        val padX = (s - contentW) / 2f
        val padY = (s - contentH) / 2f

        val square = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)
        canvas.drawColor(Color.rgb(PAD_GRAY, PAD_GRAY, PAD_GRAY))
        val scaled = Bitmap.createScaledBitmap(bitmap, contentW, contentH, true)
        canvas.drawBitmap(scaled, padX, padY, null)

        // Float32: 4 bytes × 3 channels × s²
        val buffer = ByteBuffer
            .allocateDirect(4 * 3 * s * s)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(s * s)
        square.getPixels(pixels, 0, s, 0, 0, s, s)

        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            buffer.putFloat(((pixel shr  8) and 0xFF) / 255.0f) // G
            buffer.putFloat(( pixel         and 0xFF) / 255.0f) // B
        }

        buffer.rewind()
        return buffer to Letterbox(padX, padY, contentW.toFloat(), contentH.toFloat(), s)
    }

    // ── Output Parsing ────────────────────────────────────────────────────────

    /**
     * Converts raw [1, 84, NUM_ANCHORS] model output into [Detection] objects.
     *
     *   output[feature_index][anchor_index]
     *   - feature 0..3  → cx, cy, w, h (normalized [0,1] of the INPUT_SIZE square)
     *   - feature 4..83 → class_0 .. class_79 confidence scores
     *
     * Coordinates are un-letterboxed back to normalized [0,1] of the ORIGINAL frame.
     */
    private fun parseOutput(
        output: Array<FloatArray>,
        lb: Letterbox
    ): List<Detection> {
        val results = mutableListOf<Detection>()

        // Undo letterbox: square-normalized coord → original-frame-normalized coord
        fun unpadX(nx: Float) = ((nx * lb.size) - lb.padX) / lb.contentW
        fun unpadY(ny: Float) = ((ny * lb.size) - lb.padY) / lb.contentH

        for (i in 0 until NUM_ANCHORS) {
            // Center-format box, normalized to [0,1] of the INPUT_SIZE square
            val cx = output[0][i]
            val cy = output[1][i]
            val bw = output[2][i]
            val bh = output[3][i]

            // Find the highest-scoring class
            var maxScore = 0f
            var bestClass = 0
            for (c in 0 until NUM_CLASSES) {
                val score = output[4 + c][i]
                if (score > maxScore) {
                    maxScore = score
                    bestClass = c
                }
            }

            // Skip low-confidence predictions early
            if (maxScore < CONFIDENCE_THRESHOLD) continue

            // Corner-format in square-normalized space, then un-letterbox to
            // original-frame-normalized space.
            val left   = unpadX(cx - bw / 2f)
            val top    = unpadY(cy - bh / 2f)
            val right  = unpadX(cx + bw / 2f)
            val bottom = unpadY(cy + bh / 2f)

            val box = RectF(
                left.coerceIn(0f, 1f),
                top.coerceIn(0f, 1f),
                right.coerceIn(0f, 1f),
                bottom.coerceIn(0f, 1f)
            )

            // Skip degenerate boxes (too small to be meaningful)
            if (box.width() < 0.01f || box.height() < 0.01f) continue

            val label = if (bestClass < labels.size) labels[bestClass] else "object"
            results.add(Detection(label, maxScore, box))
        }

        return results
    }

    // ── Non-Maximum Suppression ───────────────────────────────────────────────

    /**
     * Greedy NMS: keep the highest-confidence box, remove overlapping boxes
     * that share class and have IoU > [IOU_THRESHOLD].
     */
    private fun applyNms(detections: List<Detection>): List<Detection> {
        // Group by class label to run NMS per-class
        val byClass = detections.groupBy { it.label }
        val kept    = mutableListOf<Detection>()

        for ((_, group) in byClass) {
            val sorted = group.sortedByDescending { it.confidence }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                kept.add(best)
                sorted.removeAll { iou(best.boundingBox, it.boundingBox) > IOU_THRESHOLD }
            }
        }

        return kept
    }

    /** Computes Intersection-over-Union for two normalized bounding boxes. */
    private fun iou(a: RectF, b: RectF): Float {
        val interLeft   = maxOf(a.left,   b.left)
        val interTop    = maxOf(a.top,    b.top)
        val interRight  = minOf(a.right,  b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        val interW = (interRight  - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop ).coerceAtLeast(0f)
        val interArea = interW * interH

        val aArea = (a.right - a.left) * (a.bottom - a.top)
        val bArea = (b.right - b.left) * (b.bottom - b.top)
        val union = aArea + bArea - interArea

        return if (union <= 0f) 0f else interArea / union
    }
}
