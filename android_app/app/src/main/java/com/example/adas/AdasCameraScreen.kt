package com.example.adas

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.adas.geo.GeoLocation
import com.example.adas.theme.AdasTheme
import com.example.adas.theme.HudFontFamily
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
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
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AdasCameraScreen(
    viewModel: AdasViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // GNSS: request location once, then stream fixes while the camera is on screen.
    val locationPermissions = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )
    LaunchedEffect(Unit) {
        if (!locationPermissions.allPermissionsGranted) {
            locationPermissions.launchMultiplePermissionRequest()
        }
    }
    LaunchedEffect(locationPermissions.allPermissionsGranted) {
        if (locationPermissions.allPermissionsGranted) viewModel.startGnss()
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopGnss() }
    }

    val inferenceEngine = remember {
        InferenceEngine(context, viewModel.confidenceThreshold.value)
    }
    val detections by viewModel.detections.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showEventLog by remember { mutableStateOf(false) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember(inferenceEngine) {
        FrameAnalyzer(
            engine = inferenceEngine,
            onResults = { results ->
                viewModel.updateDetections(results)
                viewModel.onFrameProcessed()
            },
            onFrameAspect = { aspect -> viewModel.updateFrameAspect(aspect) },
            onFaces = { faces -> viewModel.updateFaceBoxes(faces) },
            // Analysis thread: feeds the event recorder's pre-roll ring buffer.
            onFrameForRecording = { bitmap, faces -> viewModel.onAnalyzerFrame(bitmap, faces) },
            onLane = { left, right -> viewModel.updateLane(left, right) }
        )
    }

    val previewView = remember {
        PreviewView(context).apply {
            // FIT_CENTER: show the whole frame (letterboxed) so the overlay's
            // coordinate space matches what's on screen and boxes line up.
            scaleType = PreviewView.ScaleType.FIT_CENTER
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

        // ── Layer 6: GPS readout (below the top HUD bar) ───────────────────────
        val location by viewModel.location.collectAsState()
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 16.dp, top = 60.dp)
        ) {
            GpsReadout(location)
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

/** Compact GPS fix readout (coords + accuracy) shown on the camera HUD. */
@Composable
private fun GpsReadout(location: GeoLocation?) {
    val colors = AdasTheme.colors
    val text = location?.let {
        "📍 %.5f, %.5f  ·  ±%dm".format(it.latitude, it.longitude, it.accuracyM.toInt())
    } ?: "📍 GPS — acquiring…"
    Text(
        text = text,
        fontFamily = HudFontFamily,
        fontSize = 9.sp,
        color = if (location != null) colors.green else colors.hudDim,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.glass)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
