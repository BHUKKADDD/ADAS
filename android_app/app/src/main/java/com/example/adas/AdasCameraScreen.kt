package com.example.adas

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/**
 * Full-screen camera preview with ADAS HUD layers stacked on top.
 *
 * Layer order (bottom → top):
 *  1. [PreviewView]       — full-screen live camera feed
 *  2. [DetectionOverlay]  — bounding boxes + scanline
 *  3. [AlertBanner]       — danger/caution notification strip (slides in)
 *  4. [HudTopBar]         — FPS, object count, status
 *  5. [HudBottomBar]      — REC, UPLOAD, LOG, SETTINGS
 *  6. [SettingsSheet]     — modal bottom sheet
 */
@Composable
fun AdasCameraScreen(
    viewModel: AdasViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val inferenceEngine = remember {
        InferenceEngine(context, viewModel.confidenceThreshold.value)
    }
    val detections by viewModel.detections.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showEventLog by remember { mutableStateOf(false) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember(inferenceEngine) {
        FrameAnalyzer(inferenceEngine) { results ->
            viewModel.updateDetections(results)
            viewModel.onFrameProcessed()
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(cameraProviderFuture, analyzer, analysisExecutor) {
        onDispose {
            analyzer.close()
            analysisExecutor.shutdown()
            if (cameraProviderFuture.isDone) {
                cameraProviderFuture.get().unbindAll()
            }
            // Release the native TFLite interpreter (created here via remember).
            inferenceEngine.close()
        }
    }

    // Bind CameraX use-cases once
    LaunchedEffect(cameraProviderFuture, lifecycleOwner, previewView, analyzer, analysisExecutor) {
        val cameraProvider = cameraProviderFuture.get()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            android.util.Size(640, 480),
                            androidx.camera.core.resolutionselector.ResolutionStrategy
                                .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis
        )
    }

    Box(modifier = modifier.fillMaxSize()) {

        // ── Layer 1: Camera feed ───────────────────────────────────────────────
        AndroidView(
            factory  = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // ── Layer 2: Detection overlay + scanline ──────────────────────────────
        DetectionOverlay(
            detections = detections,
            viewModel  = viewModel,
            modifier   = Modifier.fillMaxSize()
        )

        // ── Layer 3: Alert banner (slides from top) ────────────────────────────
        Box(modifier = Modifier.align(Alignment.TopCenter)) {
            AlertBanner(viewModel = viewModel)
        }

        // ── Layer 4: HUD Top Bar ───────────────────────────────────────────────
        Box(modifier = Modifier.align(Alignment.TopCenter)) {
            HudTopBar(
                viewModel = viewModel,
                onBack = onBack
            )
        }

        // ── Layer 5: HUD Bottom Bar ────────────────────────────────────────────
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            HudBottomBar(
                viewModel       = viewModel,
                onSettingsClick = { showSettings = true },
                onLogClick      = { showEventLog = true }
            )
        }
    }

    // ── Modals ─────────────────────────────────────────────────────────────────
    if (showSettings) {
        SettingsSheet(
            viewModel    = viewModel,
            onDismiss    = { showSettings = false }
        )
    }

    if (showEventLog) {
        EventLogScreen(
            onBack = { showEventLog = false }
        )
    }
}
