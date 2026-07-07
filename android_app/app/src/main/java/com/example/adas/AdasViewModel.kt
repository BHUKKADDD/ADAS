package com.example.adas

import android.app.Application
import android.graphics.RectF
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.adas.geo.GeoLocation
import com.example.adas.geo.GnssLocationProvider
import com.example.adas.obd.ObdBleManager
import com.example.adas.obd.ObdConnectionState
import com.example.adas.obd.SimulatedTelemetrySource
import com.example.adas.obd.TelemetrySource
import com.example.adas.upload.AnomalyPacket
import com.example.adas.upload.DetectionSummary
import com.example.adas.upload.UploadClient
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

    private val _rpm = MutableStateFlow<Int?>(null)
    val rpm: StateFlow<Int?> = _rpm.asStateFlow()

    private val _coolantC = MutableStateFlow<Int?>(null)
    val coolantC: StateFlow<Int?> = _coolantC.asStateFlow()

    private val _throttlePct = MutableStateFlow<Int?>(null)
    val throttlePct: StateFlow<Int?> = _throttlePct.asStateFlow()

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
        _rpm.value = null
        _coolantC.value = null
        _throttlePct.value = null
    }

    private fun activateSource(source: TelemetrySource) {
        telemetryJob?.cancel()
        telemetrySource?.stop()
        telemetrySource = source
        telemetryJob = viewModelScope.launch {
            launch { source.connectionState.collect { _obdState.value = it } }
            launch {
                source.telemetry.collect {
                    _speedKmh.value = it.speedKmh
                    _rpm.value = it.rpm
                    _coolantC.value = it.coolantC
                    _throttlePct.value = it.throttlePct
                }
            }
            launch { source.lastError.collect { _obdError.value = it } }
        }
        source.start()
    }

    // ── GNSS geolocation (position + speed fallback) ──────────────────────────
    // Model-agnostic infra. Provides a live location fix for anomaly tagging, and
    // — since a GPS fix carries speed-over-ground — feeds the HUD speed when no OBD
    // adapter is connected (real speed with zero extra hardware).

    private val gnss by lazy { GnssLocationProvider(getApplication()) }
    private var gnssJob: Job? = null

    private val _location = MutableStateFlow<GeoLocation?>(null)
    val location: StateFlow<GeoLocation?> = _location.asStateFlow()

    /** Start GNSS updates. Caller must already hold a location permission. */
    fun startGnss() {
        if (gnssJob != null) return
        gnss.start()
        gnssJob = viewModelScope.launch {
            gnss.location.collect { fix ->
                _location.value = fix
                // GPS speed drives the HUD only when no OBD/sim source is active.
                if (telemetrySource == null) _speedKmh.value = fix?.speedKmh
            }
        }
    }

    fun stopGnss() {
        gnssJob?.cancel()
        gnssJob = null
        gnss.stop()
        if (telemetrySource == null) _speedKmh.value = null
    }

    // ── PII redaction (on-device face blur) ───────────────────────────────────
    // Normalized face rects the overlay covers so faces never render in the clear.

    private val _faceBoxes = MutableStateFlow<List<RectF>>(emptyList())
    val faceBoxes: StateFlow<List<RectF>> = _faceBoxes.asStateFlow()

    fun updateFaceBoxes(boxes: List<RectF>) {
        _faceBoxes.value = boxes
    }

    override fun onCleared() {
        super.onCleared()
        telemetrySource?.stop()
        stopGnss()
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

    private val uploadClient by lazy { UploadClient(getApplication()) }

    // Ingestion endpoint. For on-device testing this points at a local mock server
    // bridged to the phone via `adb reverse tcp:8000 tcp:8000`. Swap for the real
    // cloud URL later (that side is Phase 4 / commercial).
    val uploadEndpoint: String = "http://localhost:8000/ingest"

    /**
     * Build an anomaly packet from the latest detections + telemetry and POST it,
     * subject to the selective-upload policy (Wi-Fi only). Metadata only for now.
     */
    fun triggerUpload() {
        viewModelScope.launch {
            _uploadStatus.value = UploadStatus.UPLOADING
            try {
                if (!uploadClient.canUpload()) {
                    _uploadStatus.value = UploadStatus.FAILED   // no Wi-Fi → held back
                } else {
                    val fix = _location.value
                    val packet = AnomalyPacket(
                        timestampMs = System.currentTimeMillis(),
                        deviceModel = Build.MODEL,
                        speedKmh    = _speedKmh.value,
                        rpm         = _rpm.value,
                        coolantC    = _coolantC.value,
                        throttlePct = _throttlePct.value,
                        latitude    = fix?.latitude,
                        longitude   = fix?.longitude,
                        accuracyM   = fix?.accuracyM,
                        detections  = _detections.value.map {
                            DetectionSummary(it.label, it.confidence)
                        }
                    )
                    val ok = uploadClient.upload(packet, uploadEndpoint)
                    _uploadStatus.value = if (ok) UploadStatus.SUCCESS else UploadStatus.FAILED
                }
            } catch (e: Exception) {
                _uploadStatus.value = UploadStatus.FAILED
            } finally {
                kotlinx.coroutines.delay(3000)
                _uploadStatus.value = UploadStatus.IDLE
            }
        }
    }
}
