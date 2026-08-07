package com.example.adas

import android.app.Application
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.adas.dms.DmsSourceState
import com.example.adas.dms.DmsState
import com.example.adas.dms.DrowsinessAnalyzer
import com.example.adas.dms.FaceLandmarkSource
import com.example.adas.dms.SimulatedFaceLandmarkSource
import com.example.adas.fcw.Box
import com.example.adas.fcw.CollisionAssessment
import com.example.adas.fcw.CollisionEstimator
import com.example.adas.fcw.ThreatLevel
import com.example.adas.geo.GeoLocation
import com.example.adas.geo.GnssLocationProvider
import com.example.adas.lane.LaneDepartureTracker
import com.example.adas.lane.LaneLine
import com.example.adas.lane.LaneState
import com.example.adas.rec.EventRecorder
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

    /**
     * Forward collision warning.
     *
     * This used to be a presence test: "is a box of an interesting class sitting
     * in the middle 40 % of the frame?" That fires identically for a parked car
     * 50 m ahead and a pedestrian stepping out at 3 m — it measures where things
     * are, not whether we are going to hit them. It is now a real estimate:
     * objects are tracked across frames and alerts are driven by time-to-collision
     * from bounding-box scale expansion. See `fcw/CollisionSignals.kt`.
     *
     * The vulnerable-road-user taxonomy that used to live here as `dangerLabels`
     * now lives in [com.example.adas.fcw.VULNERABLE_LABELS], where it earns those
     * classes a longer warning threshold rather than an unconditional alert.
     */
    private val collisionEstimator = CollisionEstimator()

    private val _collisionAssessment = MutableStateFlow(CollisionAssessment())
    val collisionAssessment: StateFlow<CollisionAssessment> = _collisionAssessment.asStateFlow()

    private fun updateAlertState(detections: List<Detection>) {
        val boxes = detections.map { d ->
            d.label to Box(
                d.boundingBox.left,
                d.boundingBox.top,
                d.boundingBox.right,
                d.boundingBox.bottom
            )
        }

        // elapsedRealtime, not currentTimeMillis: TTC is a derivative, so a wall
        // clock correction mid-drive would fabricate a collision.
        // A confirmed OBD speed of 0 suppresses alerts inside the estimator;
        // a null speed (no adapter) deliberately does not.
        val assessment = collisionEstimator.update(
            detections = boxes,
            nowMs = SystemClock.elapsedRealtime(),
            egoSpeedKmh = _speedKmh.value
        )
        _collisionAssessment.value = assessment

        // A DANGER assessment is the primary recording trigger. The buffer owns
        // cooldown and clip-extension policy, so calling this every frame during a
        // sustained hazard yields one clip, not a burst.
        if (assessment.level == ThreatLevel.DANGER) {
            eventRecorder.trigger(assessment.message, SystemClock.elapsedRealtime())
        }

        val speed = _speedKmh.value
        val speedSuffix = if (speed != null) "  ·  $speed km/h" else ""
        _alertState.value = when (assessment.level) {
            ThreatLevel.DANGER -> AlertState(AlertLevel.DANGER, assessment.message + speedSuffix)
            ThreatLevel.CAUTION -> AlertState(AlertLevel.CAUTION, assessment.message + speedSuffix)
            ThreatLevel.NONE -> AlertState(AlertLevel.NONE, "")
        }
    }

    // ── Lane departure warning (AIS-188) ──────────────────────────────────────
    // Classical CV, no model — firewall-clean like dms/. The speed gate means this
    // stays quiet in city traffic and only speaks up at highway speeds, per
    // UNECE R130, which AIS-188 follows.

    private val laneTracker = LaneDepartureTracker()

    private val _laneState = MutableStateFlow(LaneState())
    val laneState: StateFlow<LaneState> = _laneState.asStateFlow()

    /** Fitted lane boundaries from the analyzer; either may be null. */
    fun updateLane(left: LaneLine?, right: LaneLine?) {
        _laneState.value = laneTracker.update(left, right, _speedKmh.value)
    }

    // ── Event recording (REC) ─────────────────────────────────────────────────
    // Was a bare boolean toggle; now a real pre-roll/post-roll event recorder.
    // Frames arrive from the analysis thread already redacted, so PII never
    // reaches storage in the clear. See rec/EventRecorder.kt.

    private val eventRecorder by lazy { EventRecorder(getApplication()) }

    val recorderState: StateFlow<EventRecorder.RecorderState>
        get() = eventRecorder.state

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /**
     * Feed one analysis frame to the recorder. Called on the analysis thread —
     * redaction and JPEG compression happen there, not on the main thread.
     */
    fun onAnalyzerFrame(bitmap: android.graphics.Bitmap, faceBoxes: List<RectF>) {
        eventRecorder.offerFrame(bitmap, faceBoxes, SystemClock.elapsedRealtime())
        _isRecording.value = eventRecorder.state.value.isRecording
    }

    /** Manual REC button: capture the current moment with its pre-roll history. */
    fun toggleRecording() {
        eventRecorder.trigger("MANUAL", SystemClock.elapsedRealtime())
        _isRecording.value = eventRecorder.state.value.isRecording
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

    // ── DDAWS: driver drowsiness & attention (AIS-184) ────────────────────────
    // Model-agnostic infra, same shape as the OBD wiring above: a FaceLandmarkSource
    // (real MediaPipe or the bench simulator) feeds DrowsinessAnalyzer, and the HUD
    // renders whatever comes out. Nothing here touches InferenceEngine, so it stays
    // outside the IDD licence firewall — see dms/DrowsinessSignals.kt.

    private val drowsinessAnalyzer = DrowsinessAnalyzer()

    private val _dmsState = MutableStateFlow(DmsState())
    val dmsState: StateFlow<DmsState> = _dmsState.asStateFlow()

    private val _dmsSourceState = MutableStateFlow(DmsSourceState.STOPPED)
    val dmsSourceState: StateFlow<DmsSourceState> = _dmsSourceState.asStateFlow()

    private val _isDmsSimulated = MutableStateFlow(false)
    val isDmsSimulated: StateFlow<Boolean> = _isDmsSimulated.asStateFlow()

    private var dmsSource: FaceLandmarkSource? = null
    private var dmsJob: Job? = null

    /** Toggle the bench simulator (scripted drowsy driver, no camera). */
    fun setDmsSimulated(enabled: Boolean) {
        if (enabled) {
            _isDmsSimulated.value = true
            activateDmsSource(SimulatedFaceLandmarkSource())
        } else {
            stopDms()
        }
    }

    /** Stop the active source and reset the driver-state readout. */
    fun stopDms() {
        dmsJob?.cancel()
        dmsJob = null
        dmsSource?.stop()
        dmsSource = null
        drowsinessAnalyzer.reset()
        _isDmsSimulated.value = false
        _dmsSourceState.value = DmsSourceState.STOPPED
        _dmsState.value = DmsState()
    }

    private fun activateDmsSource(source: FaceLandmarkSource) {
        dmsJob?.cancel()
        dmsSource?.stop()
        dmsSource = source
        dmsJob = viewModelScope.launch {
            launch { source.sourceState.collect { _dmsSourceState.value = it } }
            launch {
                source.landmarks.collect { face ->
                    // elapsedRealtime, not currentTimeMillis: PERCLOS and the
                    // microsleep timer must not jump if the wall clock is corrected.
                    _dmsState.value =
                        drowsinessAnalyzer.update(face, SystemClock.elapsedRealtime())
                }
            }
        }
        source.start()
    }

    override fun onCleared() {
        super.onCleared()
        telemetrySource?.stop()
        dmsSource?.stop()
        // Don't lose a clip that was mid-post-roll when the screen went away.
        eventRecorder.flush(SystemClock.elapsedRealtime())
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
