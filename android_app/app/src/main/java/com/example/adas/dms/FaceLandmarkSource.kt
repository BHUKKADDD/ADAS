package com.example.adas.dms

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Where DDAWS gets its face landmarks from — the same real-vs-simulated split
 * that [com.example.adas.obd.TelemetrySource] uses for vehicle telemetry.
 *
 * Keeping the analyzer behind this interface means the drowsiness logic has no
 * dependency on MediaPipe, on the camera, or on Android at all, so it is fully
 * unit-tested on the JVM. Only [MediaPipeFaceLandmarkSource] (see the wiring note
 * at the bottom of this file) links the real detector.
 */

/** Lifecycle of a [FaceLandmarkSource]. */
enum class DmsSourceState {
    /** Not running. */
    STOPPED,
    /** Camera/detector starting up. */
    STARTING,
    /** Emitting frames (with or without a face in them). */
    RUNNING,
    /** Fatal error — see [FaceLandmarkSource.lastError]. */
    ERROR
}

/**
 * A live source of face-landmark frames from the driver-facing camera.
 *
 * [landmarks] holds the most recent frame, or null when the current frame had no
 * detectable face — null is a meaningful value here (it drives the "driver not
 * visible" attention warning), not just an absence of data.
 */
interface FaceLandmarkSource {
    val sourceState: StateFlow<DmsSourceState>
    val landmarks: StateFlow<FaceLandmarks?>
    val lastError: StateFlow<String?>

    /** Begin producing frames. Idempotent. */
    fun start()

    /** Stop and release the camera/detector. */
    fun stop()
}

/**
 * A [FaceLandmarkSource] that plays a scripted drowsy-driver performance — no
 * camera, no MediaPipe, no actual sleepy human required.
 *
 * This is how DDAWS gets exercised on the bench, exactly as
 * [com.example.adas.obd.SimulatedTelemetrySource] does for OBD. The script runs a
 * full arc so every branch of the analyzer's severity ladder can be seen on the
 * HUD in about a minute:
 *
 *   0–6 s    neutral, eyes open        → calibration completes
 *   6–20 s   normal driving + blinks   → NONE
 *   20–24 s  a yawn, then two more     → CAUTION (fatigue)
 *   24–34 s  frequent long closures    → CAUTION then DANGER (PERCLOS climbing)
 *   34–38 s  head turned away          → DANGER (eyes off road)
 *   38–42 s  a 2 s eye closure         → DANGER (microsleep)
 *   42–46 s  face absent               → CAUTION (driver not visible)
 *   then loops.
 */
class SimulatedFaceLandmarkSource(
    private val tickMs: Long = TICK_MS
) : FaceLandmarkSource {

    private val _sourceState = MutableStateFlow(DmsSourceState.STOPPED)
    override val sourceState: StateFlow<DmsSourceState> = _sourceState.asStateFlow()

    private val _landmarks = MutableStateFlow<FaceLandmarks?>(null)
    override val landmarks: StateFlow<FaceLandmarks?> = _landmarks.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var scope: CoroutineScope? = null

    override fun start() {
        if (scope != null) return
        _lastError.value = null
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch {
            _sourceState.value = DmsSourceState.STARTING
            delay(300)
            _sourceState.value = DmsSourceState.RUNNING
            var elapsed = 0L
            while (isActive) {
                _landmarks.value = frameAt(elapsed)
                delay(tickMs)
                elapsed = (elapsed + tickMs) % SCRIPT_MS
            }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        _landmarks.value = null
        _sourceState.value = DmsSourceState.STOPPED
    }

    /**
     * The script, as a pure function of elapsed time — deterministic and testable.
     * Returns null where the driver's face should be absent from frame.
     */
    fun frameAt(tMs: Long): FaceLandmarks? = when (tMs) {
        in 0 until 6_000 -> SyntheticFace.build(eyeOpenness = 1f)

        // Normal driving: a ~200 ms blink every 4 s.
        in 6_000 until 20_000 ->
            SyntheticFace.build(eyeOpenness = if (tMs % 4_000 < 200) 0.1f else 1f)

        // Three yawns, each ~1.2 s of wide-open mouth.
        in 20_000 until 24_000 ->
            SyntheticFace.build(
                eyeOpenness = 0.8f,
                mouthOpenness = if (tMs % 1_400 < 1_200) 1f else 0f
            )

        // Heavy lids: eyes closed roughly a third of the time -> PERCLOS climbs.
        in 24_000 until 34_000 ->
            SyntheticFace.build(eyeOpenness = if (tMs % 1_500 < 500) 0.2f else 0.9f)

        // Looking away from the road.
        in 34_000 until 38_000 -> SyntheticFace.build(eyeOpenness = 1f, yaw = 0.6f)

        // Microsleep: one long unbroken closure.
        in 38_000 until 42_000 -> SyntheticFace.build(eyeOpenness = 0.05f)

        // Driver out of frame.
        else -> null
    }

    private companion object {
        const val TICK_MS = 66L        // ≈15 fps, matching the detector's cadence
        const val SCRIPT_MS = 46_000L
    }
}

/*
 * Wiring the real source (next step, needs one dependency + one asset):
 *
 *   1. libs.versions.toml:  mediapipe-tasks-vision = "com.google.mediapipe:tasks-vision:0.10.+"
 *   2. app/build.gradle.kts: implementation(libs.mediapipe.tasks.vision)
 *      plus `androidResources { noCompress += "task" }` alongside the tflite entry.
 *   3. Download `face_landmarker.task` (~3 MB) into app/src/main/assets/.
 *   4. Implement MediaPipeFaceLandmarkSource : FaceLandmarkSource — bind a
 *      CameraX front-camera ImageAnalysis use case, run FaceLandmarker in
 *      LIVE_STREAM mode, and map result.faceLandmarks()[0] to List<Landmark>
 *      (MediaPipe already emits normalized x/y, so it is a direct copy).
 *
 * Nothing above this comment needs to change when that lands: the analyzer, the
 * signals, and the tests all sit behind this interface. Note the front camera is
 * a second concurrent CameraX use case — check `CameraInfo` for concurrent
 * front+back support on the target device before assuming both can stream.
 */
