package com.example.adas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
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
 * Model: YOLOv8n fine-tuned on the India Driving Dataset (IDD) — 12 classes
 * (see `idd_labels.txt`). Regenerate via `training/train.py` + `export_model.py`.
 *
 * IDD YOLOv8n TFLite output layout: [1, 16, 2100]  (imgsz=320)
 *   - Rows 0–3    : cx, cy, w, h  (already normalized to [0,1] by the export)
 *   - Rows 4–15   : class scores  (12 classes; already sigmoided by the export)
 *   - Columns     : 2100 anchor predictions
 */
class InferenceEngine(
    private val context: Context,
    // Minimum class score for a prediction to be kept. Sourced from the
    // ViewModel's fixed preset so the app has a single confidence source of truth.
    private val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD
) {

    companion object {
        private const val TAG                  = "InferenceEngine"
        private const val MODEL_FILENAME       = "yolov8n.tflite"
        private const val LABELS_FILENAME      = "idd_labels.txt"
        private const val INPUT_SIZE           = 320
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.40f
        private const val IOU_THRESHOLD        = 0.45f
        private const val NUM_CLASSES          = 12
        // Anchors for imgsz=320: feature maps 40×40 + 20×20 + 10×10 = 2100
        // (Would be 8400 for imgsz=640: 80×80 + 40×40 + 20×20)
        private const val NUM_ANCHORS          = 2100
    }

    // Lazy-loaded — model file is memory-mapped from assets on first use.
    // Held via an explicit Lazy so close() can release it only if it was ever created.
    private val interpreterLazy = lazy {
        val model   = loadModelFile(context, MODEL_FILENAME)
        val options = Interpreter.Options().apply { numThreads = 4 }
        Interpreter(model, options)
    }
    private val interpreter: Interpreter by interpreterLazy

    // Set once if the model/labels assets fail to load, so we don't spam logcat every frame.
    @Volatile
    private var loadErrorLogged = false

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

    // 12 IDD class names loaded once from assets (must match training/classes.txt order)
    private val labels: List<String> by lazy {
        context.assets.open(LABELS_FILENAME)
            .let { BufferedReader(InputStreamReader(it)) }
            .readLines()
            .filter { it.isNotBlank() }
    }

    /**
     * Run YOLOv8n inference on [bitmap].
     * Executes on [Dispatchers.Default] (background thread).
     * Returns normalized [Detection] objects (coords in [0,1]) for the overlay.
     */
    suspend fun detect(bitmap: Bitmap): List<Detection> = withContext(Dispatchers.Default) {
        try {
            val inputBuffer = preprocessBitmap(bitmap)

            // Output buffer: [1, 16, 2100]  (4 box coords + NUM_CLASSES scores)
            val outputArray = Array(1) { Array(4 + NUM_CLASSES) { FloatArray(NUM_ANCHORS) } }

            interpreter.run(inputBuffer, outputArray)

            val rawDetections = parseOutput(outputArray[0], bitmap.width, bitmap.height)
            applyNms(rawDetections)
        } catch (e: Exception) {
            // Missing/corrupt "$MODEL_FILENAME" or "$LABELS_FILENAME" asset, or an
            // interpreter failure. Degrade gracefully to "no detections" instead of
            // crashing the camera pipeline; log the cause once.
            if (!loadErrorLogged) {
                Log.e(TAG, "Inference unavailable — check that $MODEL_FILENAME and " +
                    "$LABELS_FILENAME exist in assets/ and are valid.", e)
                loadErrorLogged = true
            }
            emptyList()
        }
    }

    /**
     * Releases the native TFLite interpreter. Safe to call multiple times and
     * a no-op if the interpreter was never initialized. Must be called when the
     * owning screen is torn down to avoid a native memory leak.
     */
    fun close() {
        if (interpreterLazy.isInitialized()) {
            interpreter.close()
        }
    }

    // ── Pre-processing ────────────────────────────────────────────────────────

    /**
     * Resize bitmap to INPUT_SIZE × INPUT_SIZE and pack into a float32 ByteBuffer.
     * Pixel values normalized to [0, 1] in RGB channel order.
     */
    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        // Float32: 4 bytes × 3 channels × INPUT_SIZE²
        val buffer = ByteBuffer
            .allocateDirect(4 * 3 * INPUT_SIZE * INPUT_SIZE)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            buffer.putFloat(((pixel shr  8) and 0xFF) / 255.0f) // G
            buffer.putFloat(( pixel         and 0xFF) / 255.0f) // B
        }

        buffer.rewind()
        return buffer
    }

    // ── Output Parsing ────────────────────────────────────────────────────────

    /**
     * Converts raw [1, 16, 2100] model output into [Detection] objects.
     *
     * YOLOv8 TFLite output is transposed compared to ONNX:
     *   output[feature_index][anchor_index]
     *   - feature 0..3  → cx, cy, w, h (already normalized to [0,1])
     *   - feature 4..15 → class_0 .. class_11 confidence scores (12 IDD classes)
     *
     * Boxes are emitted as normalized [0,1] coords for the overlay.
     */
    private fun parseOutput(
        output: Array<FloatArray>,
        origW: Int,
        origH: Int
    ): List<Detection> {
        val results = mutableListOf<Detection>()

        for (i in 0 until NUM_ANCHORS) {
            // Center-format box, already normalized to [0,1] by the TFLite export
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
            if (maxScore < confidenceThreshold) continue

            // Convert center-format (cx, cy, w, h) to corner-format (left, top, right, bottom).
            // The YOLOv8 TFLite export already emits coordinates normalized to [0,1],
            // so they are used directly — dividing by INPUT_SIZE here would collapse
            // every box into the top-left corner.
            val left   = cx - bw / 2f
            val top    = cy - bh / 2f
            val right  = cx + bw / 2f
            val bottom = cy + bh / 2f

            val box = RectF(
                left.coerceIn(0f, 1f),
                top.coerceIn(0f, 1f),
                right.coerceIn(0f, 1f),
                bottom.coerceIn(0f, 1f)
            )

            // Skip degenerate boxes (boxes that are too small to be meaningful)
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
