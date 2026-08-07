package com.example.adas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
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
    private val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    /**
     * Compute path. Defaults to [Accelerator.CPU] — the XNNPACK path that
     * measured 12–15 FPS on the A55. GPU is implemented and falls back safely,
     * but for this INT8 model it is an unmeasured change, so it is opt-in rather
     * than the default: switching the safety path on an unverified assumption is
     * the wrong trade.
     */
    private val accelerator: Accelerator = Accelerator.CPU
) {

    companion object {
        private const val TAG                  = "InferenceEngine"
        private const val MODEL_FILENAME       = "yolov8n.tflite"
        private const val LABELS_FILENAME      = "idd_labels.txt"
        private const val INPUT_SIZE           = 320
        // Tuned for the INT8-quantized model: quantization compresses class
        // scores (float32's ~0.9 detections score ~0.5 here), so the cutoff
        // sits lower than the 0.40 used with the float32 export. Validated on
        // an IDD val subset: 0.30 matches the float32@0.40 recall.
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.30f
        private const val IOU_THRESHOLD        = 0.45f
        private const val NUM_CLASSES          = 12
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

    /**
     * Which compute path inference runs on.
     *
     * **NNAPI is deliberately absent**: it was deprecated in Android 15, and the
     * migration guidance is the GPU delegate or XNNPACK. The LiteRT QNN
     * accelerator would be the NPU option, but it is Qualcomm-only and so does
     * nothing on the Exynos-based Galaxy A55 this app is tuned against.
     */
    enum class Accelerator {
        /** XNNPACK-accelerated CPU, 4 threads. The measured 12–15 FPS baseline. */
        CPU,
        /** GPU delegate, falling back to [CPU] if unavailable at runtime. */
        GPU
    }

    /** Which path actually initialised — GPU can fall back, so this may differ. */
    @Volatile
    var activeAccelerator: Accelerator = accelerator
        private set

    private var gpuDelegate: GpuDelegate? = null

    // Lazy-loaded — model file is memory-mapped from assets on first use.
    // Held via an explicit Lazy so close() can release it only if it was ever created.
    private val interpreterLazy = lazy {
        val model = loadModelFile(context, MODEL_FILENAME)
        buildInterpreter(model)
    }
    private val interpreter: Interpreter by interpreterLazy

    /**
     * Build the interpreter on the requested accelerator, degrading to CPU rather
     * than failing: a device that cannot start the GPU delegate must still run
     * the detector, since this is the safety-critical path.
     */
    private fun buildInterpreter(model: MappedByteBuffer): Interpreter {
        if (accelerator == Accelerator.GPU) {
            try {
                val compat = CompatibilityList()
                if (compat.isDelegateSupportedOnThisDevice) {
                    val delegate = GpuDelegate(
                        compat.bestOptionsForThisDevice.apply {
                            // The shipped model is INT8. The GPU delegate only
                            // accepts quantized graphs with this flag, and even
                            // then INT8-on-GPU is not automatically faster than
                            // INT8-on-XNNPACK — it must be measured per device.
                            setQuantizedModelsAllowed(true)
                        }
                    )
                    gpuDelegate = delegate
                    val options = Interpreter.Options().apply { addDelegate(delegate) }
                    val interp = Interpreter(model, options)
                    activeAccelerator = Accelerator.GPU
                    Log.i(TAG, "inference on GPU delegate")
                    return interp
                }
                Log.w(TAG, "GPU delegate unsupported on this device; using CPU")
            } catch (e: Throwable) {
                // Delegate creation can fail with a LinkageError on some OEM
                // builds, which a catch on Exception would let through.
                Log.w(TAG, "GPU delegate init failed; falling back to CPU", e)
                gpuDelegate?.close()
                gpuDelegate = null
            }
        }
        val options = Interpreter.Options().apply {
            numThreads = 4
            setUseXNNPACK(true)
        }
        activeAccelerator = Accelerator.CPU
        Log.i(TAG, "inference on CPU (XNNPACK, 4 threads)")
        return Interpreter(model, options)
    }

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
            val (inputBuffer, letterbox) = preprocessBitmap(bitmap)

            // Output buffer: [1, 16, 2100]  (4 box coords + NUM_CLASSES scores)
            val outputArray = Array(1) { Array(4 + NUM_CLASSES) { FloatArray(NUM_ANCHORS) } }

            interpreter.run(inputBuffer, outputArray)

            val rawDetections = parseOutput(outputArray[0], letterbox)
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
        // The delegate outlives the interpreter and leaks GPU memory if not
        // released; order matters, interpreter first.
        gpuDelegate?.close()
        gpuDelegate = null
    }

    // ── Pre-processing ────────────────────────────────────────────────────────

    /**
     * Letterbox [bitmap] into an INPUT_SIZE×INPUT_SIZE square preserving aspect
     * ratio (neutral-gray padding), matching how YOLO was trained — a naive
     * anisotropic stretch distorts objects and tanks detection. Packs the result
     * into a float32 ByteBuffer in RGB order, pixels normalized to [0,1].
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

        // Model input is NCHW [1,3,320,320] → channel-PLANAR order: all R values,
        // then all G, then all B. Writing interleaved R,G,B,R,G,B… (NHWC) scrambles
        // the channels and the model outputs near-zero scores for everything.
        for (pixel in pixels) buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R plane
        for (pixel in pixels) buffer.putFloat(((pixel shr  8) and 0xFF) / 255.0f) // G plane
        for (pixel in pixels) buffer.putFloat(( pixel         and 0xFF) / 255.0f) // B plane

        buffer.rewind()
        return buffer to Letterbox(padX, padY, contentW.toFloat(), contentH.toFloat(), s)
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
        lb: Letterbox
    ): List<Detection> {
        val results = mutableListOf<Detection>()

        // Undo letterbox: square-normalized coord → original-frame-normalized coord
        fun unpadX(nx: Float) = ((nx * lb.size) - lb.padX) / lb.contentW
        fun unpadY(ny: Float) = ((ny * lb.size) - lb.padY) / lb.contentH

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

            // Center→corner in square-normalized space, then un-letterbox back to
            // original-frame-normalized space. (Never divide by INPUT_SIZE — the
            // export already emits [0,1] coords of the padded square.)
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
