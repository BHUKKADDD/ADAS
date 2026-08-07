package com.example.adas.dms

/**
 * DDAWS decision layer (AIS-184): turns a stream of face landmarks into a driver
 * drowsiness / attention alert.
 *
 * Two things make this more than a threshold on eye openness:
 *
 *  1. **Personal calibration.** Absolute EAR varies widely between drivers, so a
 *     fixed "eyes closed" threshold either misses sleepy drivers or screams at
 *     narrow-eyed ones. The analyzer spends [DmsConfig.calibrationMs] measuring a
 *     neutral baseline (eyes open, mouth closed) and derives the closure
 *     threshold from *that* driver's numbers.
 *  2. **PERCLOS, not blinks.** The regulated drowsiness measure is the proportion
 *     of time the eyes are closed over a rolling window — a normal blink is
 *     ~100-300 ms and must not alert, while the same eye state held over a minute
 *     must. Instantaneous closure is handled separately as microsleep.
 *
 * Pure Kotlin and time-injected: every method takes `nowMs`, so the whole state
 * machine is unit-testable without a device, a clock, or a camera.
 *
 * MODEL-AGNOSTIC: see the file header in [DrowsinessSignals].
 */

/** Where the analyzer is in its lifecycle. */
enum class DmsPhase {
    /** No face seen yet; nothing to report. */
    IDLE,
    /** Measuring this driver's neutral baseline. Restarts if the face is lost. */
    CALIBRATING,
    /** Baseline established; drowsiness and attention are being scored. */
    MONITORING,
    /** Face has been absent longer than [DmsConfig.faceLostMs]. */
    NO_FACE
}

/** Severity of the driver-state warning, mirroring the road-facing alert levels. */
enum class DriverAlertLevel { NONE, CAUTION, DANGER }

/**
 * Tunables. Defaults follow the common DMS literature; they are the numbers to
 * revisit after a real drive, not constants to trust blindly.
 */
data class DmsConfig(
    /** Neutral-baseline measurement window. */
    val calibrationMs: Long = 5_000,
    /** Reject a too-sparse calibration (e.g. a 2-frame stutter) even if time passed. */
    val minCalibrationSamples: Int = 20,
    /** Eyes count as closed below `baselineEar * this`. */
    val eyeClosedRatio: Float = 0.75f,
    /** Rolling window PERCLOS is measured over. */
    val perclosWindowMs: Long = 60_000,
    /**
     * PERCLOS is meaningless until the window holds enough history — without this
     * one closed frame just after calibration would read as 100 % closure.
     */
    val perclosMinSpanMs: Long = 10_000,
    val perclosCaution: Float = 0.08f,
    val perclosDanger: Float = 0.15f,
    /** A single unbroken closure this long is a microsleep — immediate DANGER. */
    val microsleepMs: Long = 1_500,
    /** Mouth counts as yawning above `baselineMar * this`. */
    val yawnMarRatio: Float = 1.8f,
    /** Mouth must stay open this long to be a yawn rather than speech. */
    val yawnMinMs: Long = 700,
    /** Window over which yawns accumulate. */
    val yawnWindowMs: Long = 300_000,
    /** Yawns within the window before it counts as fatigue. */
    val yawnCautionCount: Int = 3,
    /** |head-yaw proxy| above this counts as looking away from the road. */
    val yawThreshold: Float = 0.35f,
    /** Sustained look-away before the attention warning fires. */
    val distractionMs: Long = 2_000,
    /** Absence this long is reported as "driver not visible". */
    val faceLostMs: Long = 3_000
)

/** Everything the HUD (and the upload packet) needs about the driver's state. */
data class DmsState(
    val phase: DmsPhase = DmsPhase.IDLE,
    val alert: DriverAlertLevel = DriverAlertLevel.NONE,
    val message: String = "",
    /** Proportion of the rolling window with eyes closed, [0,1]. */
    val perclos: Float = 0f,
    /** Latest mean EAR, or null when no face is visible. */
    val ear: Float? = null,
    /** Duration of the current unbroken eye closure, 0 when eyes are open. */
    val eyesClosedMs: Long = 0,
    /** Yawns counted within [DmsConfig.yawnWindowMs]. */
    val yawns: Int = 0,
    /** Calibration completion in [0,1]; 1 once monitoring. */
    val calibrationProgress: Float = 0f,
    /** This driver's neutral EAR, once calibrated. */
    val baselineEar: Float? = null
)

class DrowsinessAnalyzer(private val config: DmsConfig = DmsConfig()) {

    private data class Sample(val tMs: Long, val closed: Boolean)

    private var phase = DmsPhase.IDLE
    private var baselineEar: Float? = null
    private var baselineMar: Float? = null

    private var calibrationStartMs = 0L
    private val calEar = ArrayList<Float>()
    private val calMar = ArrayList<Float>()

    private val samples = ArrayDeque<Sample>()
    private var closedSinceMs: Long? = null
    private var mouthOpenSinceMs: Long? = null
    private var yawnCountedForEpisode = false
    private val yawnTimes = ArrayDeque<Long>()
    private var lookingAwaySinceMs: Long? = null
    private var noFaceSinceMs: Long? = null

    /** Drop all state, including the calibrated baseline (e.g. on driver change). */
    fun reset() {
        phase = DmsPhase.IDLE
        baselineEar = null
        baselineMar = null
        calibrationStartMs = 0L
        calEar.clear()
        calMar.clear()
        samples.clear()
        closedSinceMs = null
        mouthOpenSinceMs = null
        yawnCountedForEpisode = false
        yawnTimes.clear()
        lookingAwaySinceMs = null
        noFaceSinceMs = null
    }

    /**
     * Feed one frame. Pass `face = null` when no face was detected.
     *
     * @param nowMs monotonic-ish timestamp for this frame, e.g. `SystemClock.elapsedRealtime()`.
     */
    fun update(face: FaceLandmarks?, nowMs: Long): DmsState {
        if (face == null || !face.isUsable) return onFaceMissing(nowMs)
        noFaceSinceMs = null

        val ear = averageEyeAspectRatio(face)
        val mar = mouthAspectRatio(face)
        val yaw = headYawProxy(face)

        return when (phase) {
            DmsPhase.IDLE, DmsPhase.NO_FACE -> {
                startCalibration(nowMs, ear, mar)
                calibratingState(nowMs, ear)
            }

            DmsPhase.CALIBRATING -> {
                calEar.add(ear)
                calMar.add(mar)
                if (nowMs - calibrationStartMs >= config.calibrationMs &&
                    calEar.size >= config.minCalibrationSamples
                ) {
                    baselineEar = calEar.average().toFloat()
                    baselineMar = calMar.average().toFloat()
                    phase = DmsPhase.MONITORING
                    monitor(nowMs, ear, mar, yaw)
                } else {
                    calibratingState(nowMs, ear)
                }
            }

            DmsPhase.MONITORING -> monitor(nowMs, ear, mar, yaw)
        }
    }

    // ── Calibration ──────────────────────────────────────────────────────────

    private fun startCalibration(nowMs: Long, ear: Float, mar: Float) {
        phase = DmsPhase.CALIBRATING
        calibrationStartMs = nowMs
        calEar.clear()
        calMar.clear()
        calEar.add(ear)
        calMar.add(mar)
    }

    private fun calibratingState(nowMs: Long, ear: Float): DmsState {
        val progress =
            ((nowMs - calibrationStartMs).toFloat() / config.calibrationMs).coerceIn(0f, 1f)
        return DmsState(
            phase = DmsPhase.CALIBRATING,
            alert = DriverAlertLevel.NONE,
            message = "CALIBRATING DRIVER BASELINE — LOOK AHEAD",
            ear = ear,
            calibrationProgress = progress
        )
    }

    // ── Missing face ─────────────────────────────────────────────────────────

    private fun onFaceMissing(nowMs: Long): DmsState {
        val since = noFaceSinceMs ?: nowMs.also { noFaceSinceMs = it }

        // A baseline is only valid if it was measured from one continuous look at
        // the driver, so an interrupted calibration has to start over.
        if (phase == DmsPhase.CALIBRATING) {
            phase = DmsPhase.IDLE
            calEar.clear()
            calMar.clear()
        }

        // Eyes can't be scored without a face; end any open closure/yawn episode
        // rather than letting it accumulate through the gap.
        closedSinceMs = null
        mouthOpenSinceMs = null
        yawnCountedForEpisode = false

        if (nowMs - since >= config.faceLostMs) {
            phase = DmsPhase.NO_FACE
            return DmsState(
                phase = DmsPhase.NO_FACE,
                alert = DriverAlertLevel.CAUTION,
                message = "⚠️  DRIVER NOT VISIBLE — CHECK CAMERA",
                perclos = perclos(nowMs),
                yawns = yawnTimes.size,
                baselineEar = baselineEar
            )
        }
        return DmsState(
            phase = phase,
            alert = DriverAlertLevel.NONE,
            message = "",
            perclos = perclos(nowMs),
            yawns = yawnTimes.size,
            baselineEar = baselineEar
        )
    }

    // ── Monitoring ───────────────────────────────────────────────────────────

    private fun monitor(nowMs: Long, ear: Float, mar: Float, yaw: Float): DmsState {
        val baseEar = baselineEar ?: return calibratingState(nowMs, ear)
        val closed = ear < baseEar * config.eyeClosedRatio

        // PERCLOS window
        samples.addLast(Sample(nowMs, closed))
        while (samples.size > 1 && nowMs - samples.first().tMs > config.perclosWindowMs) {
            samples.removeFirst()
        }

        // Unbroken closure -> microsleep
        if (closed) {
            if (closedSinceMs == null) closedSinceMs = nowMs
        } else {
            closedSinceMs = null
        }
        val eyesClosedMs = closedSinceMs?.let { nowMs - it } ?: 0L

        trackYawn(nowMs, mar)
        trackAttention(nowMs, yaw)

        val perclos = perclos(nowMs)
        val spanMs = if (samples.size > 1) samples.last().tMs - samples.first().tMs else 0L
        val perclosReady = spanMs >= config.perclosMinSpanMs
        val awayMs = lookingAwaySinceMs?.let { nowMs - it } ?: 0L

        // Severity ladder — most acute first.
        val (alert, message) = when {
            eyesClosedMs >= config.microsleepMs ->
                DriverAlertLevel.DANGER to "⚠️  MICROSLEEP — EYES CLOSED ${eyesClosedMs / 1000}s"

            awayMs >= config.distractionMs ->
                DriverAlertLevel.DANGER to "⚠️  EYES OFF ROAD — LOOK AHEAD"

            perclosReady && perclos >= config.perclosDanger ->
                DriverAlertLevel.DANGER to
                    "⚠️  DRIVER DROWSY — STOP AND REST  ·  PERCLOS ${pct(perclos)}"

            perclosReady && perclos >= config.perclosCaution ->
                DriverAlertLevel.CAUTION to
                    "⚠️  FATIGUE DETECTED — TAKE A BREAK  ·  PERCLOS ${pct(perclos)}"

            yawnTimes.size >= config.yawnCautionCount ->
                DriverAlertLevel.CAUTION to
                    "⚠️  ${yawnTimes.size} YAWNS — FATIGUE LIKELY"

            else -> DriverAlertLevel.NONE to ""
        }

        return DmsState(
            phase = DmsPhase.MONITORING,
            alert = alert,
            message = message,
            perclos = perclos,
            ear = ear,
            eyesClosedMs = eyesClosedMs,
            yawns = yawnTimes.size,
            calibrationProgress = 1f,
            baselineEar = baseEar
        )
    }

    private fun trackYawn(nowMs: Long, mar: Float) {
        val baseMar = baselineMar ?: return
        val open = mar > baseMar * config.yawnMarRatio
        if (open) {
            val since = mouthOpenSinceMs ?: nowMs.also { mouthOpenSinceMs = it }
            if (!yawnCountedForEpisode && nowMs - since >= config.yawnMinMs) {
                yawnTimes.addLast(nowMs)
                yawnCountedForEpisode = true
            }
        } else {
            mouthOpenSinceMs = null
            yawnCountedForEpisode = false
        }
        while (yawnTimes.isNotEmpty() && nowMs - yawnTimes.first() > config.yawnWindowMs) {
            yawnTimes.removeFirst()
        }
    }

    private fun trackAttention(nowMs: Long, yaw: Float) {
        if (kotlin.math.abs(yaw) > config.yawThreshold) {
            if (lookingAwaySinceMs == null) lookingAwaySinceMs = nowMs
        } else {
            lookingAwaySinceMs = null
        }
    }

    /**
     * Time-weighted proportion of the window spent with eyes closed. Weighting by
     * duration rather than frame count keeps the measure honest when the frame
     * rate wobbles, which it does under thermal load.
     */
    private fun perclos(nowMs: Long): Float {
        if (samples.size < 2) return 0f
        var closedMs = 0L
        var totalMs = 0L
        val list = samples.toList()
        for (i in 0 until list.size - 1) {
            val dt = list[i + 1].tMs - list[i].tMs
            if (dt <= 0) continue
            totalMs += dt
            if (list[i].closed) closedMs += dt
        }
        return if (totalMs <= 0) 0f else closedMs.toFloat() / totalMs
    }

    private fun pct(v: Float): String = "${(v * 100).toInt()}%"
}
