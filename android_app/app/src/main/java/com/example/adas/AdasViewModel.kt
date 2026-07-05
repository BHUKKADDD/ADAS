package com.example.adas

import android.app.Application
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.adas.obd.ObdBleManager
import com.example.adas.obd.ObdConnectionState
import com.example.adas.obd.SimulatedTelemetrySource
import com.example.adas.obd.TelemetrySource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Central state holder for the entire ADAS app.
 * Holds live detection results, HUD metrics, alert state, and UI toggles.
 * All composables read from this ViewModel to avoid state duplication.
 */
class AdasViewModel(app: Application) : AndroidViewModel(app) {

    // ── Detection Results ─────────────────────────────────────────────────────

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections.asStateFlow()

    // Aspect ratio (width/height) of the upright camera frame; 0 = unknown.
    // Lets the overlay map boxes into the FIT_CENTER letterbox rect.
    private val _frameAspect = MutableStateFlow(0f)
    val frameAspect: StateFlow<Float> = _frameAspect.asStateFlow()

    fun updateFrameAspect(ratio: Float) {
        _frameAspect.value = ratio
    }

    fun updateDetections(results: List<Detection>) {
        viewModelScope.launch {
            _detections.value = results
            _objectCount.value = results.size
            updateAlertState(results)
        }
    }

    // ── HUD Metrics ───────────────────────────────────────────────────────────

    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _objectCount = MutableStateFlow(0)
    val objectCount: StateFlow<Int> = _objectCount.asStateFlow()

    // FPS tracking
    private var frameTimestamps = ArrayDeque<Long>()
    fun onFrameProcessed() {
        val now = System.currentTimeMillis()
        frameTimestamps.addLast(now)
        // Keep only frames from the last second
        while (frameTimestamps.isNotEmpty() && now - frameTimestamps.first() > 1000L) {
            frameTimestamps.removeFirst()
        }
        _fps.value = frameTimestamps.size
    }

    // ── Alert State ───────────────────────────────────────────────────────────

    enum class AlertLevel { NONE, CAUTION, DANGER }
    data class AlertState(
        val level: AlertLevel = AlertLevel.NONE,
        val message: String = ""
    )

    private val _alertState = MutableStateFlow(AlertState())
    val alertState: StateFlow<AlertState> = _alertState.asStateFlow()

    // IDD classes that warrant an immediate danger alert (vulnerable / unpredictable
    // road users — the India-specific edge cases COCO models miss)
    private val dangerLabels  = setOf("person", "rider", "bicycle", "animal")
    // IDD classes that warrant a caution alert (vehicles sharing the lane)
    private val cautionLabels = setOf("motorcycle", "car", "autorickshaw", "truck", "bus", "vehicle_fallback")

    private fun updateAlertState(detections: List<Detection>) {
        val danger  = detections.firstOrNull { it.label in dangerLabels  && isCenterZone(it.boundingBox) }
        val caution = detections.firstOrNull { it.label in cautionLabels && isCenterZone(it.boundingBox) }

        // Speed-aware: annotate alerts with the live OBD speed when available, and
        // suppress alerts entirely when the vehicle is confirmed stationary (real
        // OBD speed == 0) — parked/stopped means no phantom "BRAKE!" prompt.
        val speed = _speedKmh.value
        val speedSuffix = if (speed != null) "  ·  $speed km/h" else ""
        val stationary = speed == 0

        _alertState.value = when {
            danger  != null && !stationary -> AlertState(
                AlertLevel.DANGER,
                "⚠️  ${danger.label.uppercase()} DETECTED — BRAKE!$speedSuffix"
            )
            caution != null && !stationary -> AlertState(
                AlertLevel.CAUTION,
                "⚠️  ${caution.label.uppercase()} IN LANE — SLOW DOWN$speedSuffix"
            )
            else -> AlertState(AlertLevel.NONE, "")
        }
    }

    /** Returns true if the bounding box center falls in the middle 40% of the frame */
    private fun isCenterZone(box: RectF): Boolean {
        val cx = (box.left + box.right) / 2f
        val cy = (box.top + box.bottom) / 2f
        return cx in 0.30f..0.70f && cy in 0.25f..0.75f
    }

    // ── Recording State ───────────────────────────────────────────────────────

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    fun toggleRecording() {
        _isRecording.value = !_isRecording.value
    }

    // ── OBD Telemetry (vehicle speed via ELM327) ──────────────────────────────
    // Model-agnostic infrastructure: none of this touches the detector or the IDD
    // model. A TelemetrySource (real BLE or simulated) feeds live vehicle data to
    // the HUD. The ViewModel owns stable flows and mirrors whichever source is
    // active, so the UI is unaffected when the source is swapped.

    private val _obdState = MutableStateFlow(ObdConnectionState.DISCONNECTED)
    val obdConnectionState: StateFlow<ObdConnectionState> = _obdState.asStateFlow()

    private val _speedKmh = MutableStateFlow<Int?>(null)
    val speedKmh: StateFlow<Int?> = _speedKmh.asStateFlow()

    private val _obdError = MutableStateFlow<String?>(null)
    val obdError: StateFlow<String?> = _obdError.asStateFlow()

    private val _isObdSimulated = MutableStateFlow(false)
    val isObdSimulated: StateFlow<Boolean> = _isObdSimulated.asStateFlow()

    private var telemetrySource: TelemetrySource? = null
    private var telemetryJob: Job? = null

    /** Connect to a real ELM327 BLE adapter. Caller must already hold BT permissions. */
    fun connectObd() {
        _isObdSimulated.value = false
        activateSource(ObdBleManager(getApplication()))
    }

    /** Toggle the bench simulator (synthetic speed curve, no hardware). */
    fun setObdSimulated(enabled: Boolean) {
        if (enabled) {
            _isObdSimulated.value = true
            activateSource(SimulatedTelemetrySource())
        } else {
            disconnectObd()
        }
    }

    /** Stop any active source and reset telemetry to disconnected. */
    fun disconnectObd() {
        telemetryJob?.cancel()
        telemetryJob = null
        telemetrySource?.stop()
        telemetrySource = null
        _isObdSimulated.value = false
        _obdState.value = ObdConnectionState.DISCONNECTED
        _speedKmh.value = null
    }

    private fun activateSource(source: TelemetrySource) {
        telemetryJob?.cancel()
        telemetrySource?.stop()
        telemetrySource = source
        telemetryJob = viewModelScope.launch {
            launch { source.connectionState.collect { _obdState.value = it } }
            launch { source.telemetry.collect { _speedKmh.value = it.speedKmh } }
            launch { source.lastError.collect { _obdError.value = it } }
        }
        source.start()
    }

    override fun onCleared() {
        super.onCleared()
        telemetrySource?.stop()
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    // Dynamic Theme Setting (The only adjustable user preference)
    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    fun toggleTheme() {
        _isDarkTheme.value = !_isDarkTheme.value
    }

    // Professional Presets (Fixed for driver safety, cannot be adjusted)
    // confidenceThreshold is the single source of truth for detection filtering:
    // its value is passed into InferenceEngine when the camera screen is created.
    // With correct NCHW input, true detections score ~0.9, so 0.40 keeps them while
    // cutting weak background clutter. Lower to ~0.30 for max sensitivity.
    val confidenceThreshold: StateFlow<Float> = MutableStateFlow(0.40f).asStateFlow()
    val showDistanceEstimates: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    val showScanLine: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    val hudOpacity: StateFlow<Float> = MutableStateFlow(0.85f).asStateFlow()

    // ── Upload State ──────────────────────────────────────────────────────────

    enum class UploadStatus { IDLE, UPLOADING, SUCCESS, FAILED }

    private val _uploadStatus = MutableStateFlow(UploadStatus.IDLE)
    val uploadStatus: StateFlow<UploadStatus> = _uploadStatus.asStateFlow()

    fun triggerUpload() {
        viewModelScope.launch {
            _uploadStatus.value = UploadStatus.UPLOADING
            try {
                // TODO Phase D: serialize latest clip frames to .npz and POST to
                // cloud_scorer endpoint via OkHttp or Ktor:
                //
                //   val client = OkHttpClient()
                //   val body = RequestBody.create(MediaType.parse("application/octet-stream"), clipFile)
                //   val request = Request.Builder()
                //       .url("https://your-cloud-server/score")
                //       .post(body).build()
                //   val response = client.newCall(request).execute()
                //   if (!response.isSuccessful) throw IOException("Upload failed: ${response.code}")
                //
                kotlinx.coroutines.delay(1500) // Remove once real upload is implemented
                _uploadStatus.value = UploadStatus.SUCCESS
            } catch (e: Exception) {
                _uploadStatus.value = UploadStatus.FAILED
            } finally {
                kotlinx.coroutines.delay(3000)
                _uploadStatus.value = UploadStatus.IDLE
            }
        }
    }
}
