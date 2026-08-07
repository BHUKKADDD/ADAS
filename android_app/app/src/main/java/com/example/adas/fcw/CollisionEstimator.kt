package com.example.adas.fcw

/**
 * Forward Collision Warning — tracking and the alert decision.
 *
 * TTC needs a *track*, not a detection: apparent width only has a rate of change
 * if the same object is followed across frames. This file does the association
 * (greedy IoU, label-aware), maintains one [ScaleKalman] per track, and turns the
 * resulting TTCs into an alert.
 *
 * Two guards keep false alarms down, both of which the monocular-FCW literature
 * warns about:
 *
 *  - **Track maturity.** A TTC is ignored until the track has enough observations
 *    over enough elapsed time for its width rate to mean anything.
 *  - **Ego motion gate.** With a real OBD speed of 0 the vehicle is stopped, so
 *    nothing ahead is a collision risk — the same suppression the old alert used,
 *    kept deliberately.
 *
 * Vulnerable road users get longer warning thresholds than vehicles. On Indian
 * roads a pedestrian, rider, cyclist or animal entering the corridor is both more
 * likely and more consequential than a car doing the same, and it is the case the
 * whole IDD retrain exists to catch.
 */

/** Severity of the forward-collision assessment. */
enum class ThreatLevel { NONE, CAUTION, DANGER }

/** One tracked object's current collision estimate. */
data class Threat(
    val trackId: Int,
    val label: String,
    val box: Box,
    /** Seconds until impact on current course, or null when not closing. */
    val ttcSeconds: Float?,
    val inEgoPath: Boolean,
    /** Approximate headway; display only — see [approximateDistanceMeters]. */
    val distanceMeters: Float?
)

/** The estimator's output for one frame. */
data class CollisionAssessment(
    val level: ThreatLevel = ThreatLevel.NONE,
    val message: String = "",
    /** The threat that drove [level], if any. */
    val primary: Threat? = null,
    /** Every live track this frame, for overlay/debug. */
    val threats: List<Threat> = emptyList()
)

/** Thresholds, in seconds of time-to-collision. */
data class FcwConfig(
    val vehicleDangerTtc: Float = 1.5f,
    val vehicleCautionTtc: Float = 3.0f,
    /** Vulnerable road users get earlier warnings — less mass, less predictability. */
    val vulnerableDangerTtc: Float = 2.0f,
    val vulnerableCautionTtc: Float = 4.0f,
    /** Minimum observations before a track's TTC is trusted. */
    val minHits: Int = 4,
    /** Minimum track lifetime before its TTC is trusted. */
    val minTrackAgeMs: Long = 250,
    /** IoU above which a detection is considered the same object as a track. */
    val iouMatchThreshold: Float = 0.25f,
    /** Frames a track survives without a matching detection. */
    val maxMissedFrames: Int = 5
)

/** Classes treated as vulnerable road users. Mirrors the app's danger taxonomy. */
val VULNERABLE_LABELS = setOf("person", "rider", "bicycle", "animal")

/** One tracked object. Internal to [CollisionEstimator]. */
private class Track(
    val id: Int,
    var label: String,
    var box: Box,
    firstSeenMs: Long,
    lastSeenMs: Long
) {
    val kalman = ScaleKalman(initialWidth = box.width)
    val firstSeenMs: Long = firstSeenMs
    var lastSeenMs: Long = lastSeenMs
    var hits: Int = 1
    var missed: Int = 0

    fun ageMs(nowMs: Long): Long = nowMs - firstSeenMs
}

/**
 * Stateful across frames — one instance per camera session. Not thread-safe;
 * call [update] from the analysis thread only.
 */
class CollisionEstimator(private val config: FcwConfig = FcwConfig()) {

    private val tracks = mutableListOf<Track>()
    private var nextId = 1
    private var lastFrameMs: Long? = null

    /** Drop all tracking state (camera restart, driver change, teardown). */
    fun reset() {
        tracks.clear()
        lastFrameMs = null
        nextId = 1
    }

    /**
     * Feed one frame of detections.
     *
     * @param detections normalized boxes with labels, this frame.
     * @param nowMs monotonic timestamp, e.g. `SystemClock.elapsedRealtime()`.
     * @param egoSpeedKmh real vehicle speed if known; null when unavailable.
     *   A confirmed 0 suppresses alerts, null does not.
     */
    fun update(
        detections: List<Pair<String, Box>>,
        nowMs: Long,
        egoSpeedKmh: Int? = null
    ): CollisionAssessment {
        val dtSeconds = lastFrameMs?.let { (nowMs - it) / 1000f } ?: 0f
        lastFrameMs = nowMs

        associate(detections, nowMs, dtSeconds)

        val threats = tracks.map { t ->
            val mature = t.hits >= config.minHits && t.ageMs(nowMs) >= config.minTrackAgeMs
            val ttc = if (mature) t.kalman.ttcSeconds else null
            Threat(
                trackId = t.id,
                label = t.label,
                box = t.box,
                ttcSeconds = ttc,
                inEgoPath = isInEgoPath(t.box),
                distanceMeters = approximateDistanceMeters(t.box, t.label)
            )
        }

        // A confirmed standstill means nothing ahead can be struck.
        if (egoSpeedKmh == 0) {
            return CollisionAssessment(ThreatLevel.NONE, "", null, threats)
        }

        // Most urgent in-path threat wins: lowest TTC.
        val candidate = threats
            .filter { it.inEgoPath && it.ttcSeconds != null }
            .minByOrNull { it.ttcSeconds!! }
            ?: return CollisionAssessment(ThreatLevel.NONE, "", null, threats)

        val ttc = candidate.ttcSeconds!!
        val vulnerable = candidate.label in VULNERABLE_LABELS
        val dangerAt = if (vulnerable) config.vulnerableDangerTtc else config.vehicleDangerTtc
        val cautionAt = if (vulnerable) config.vulnerableCautionTtc else config.vehicleCautionTtc

        val level = when {
            ttc <= dangerAt -> ThreatLevel.DANGER
            ttc <= cautionAt -> ThreatLevel.CAUTION
            else -> ThreatLevel.NONE
        }
        if (level == ThreatLevel.NONE) {
            return CollisionAssessment(ThreatLevel.NONE, "", null, threats)
        }

        val name = candidate.label.uppercase()
        val ttcText = "TTC ${"%.1f".format(ttc)}s"
        val message = when (level) {
            ThreatLevel.DANGER -> "⚠️  $name AHEAD — BRAKE!  ·  $ttcText"
            ThreatLevel.CAUTION -> "⚠️  $name CLOSING — SLOW DOWN  ·  $ttcText"
            ThreatLevel.NONE -> ""
        }
        return CollisionAssessment(level, message, candidate, threats)
    }

    // ── Association ──────────────────────────────────────────────────────────

    private fun associate(
        detections: List<Pair<String, Box>>,
        nowMs: Long,
        dtSeconds: Float
    ) {
        val unmatchedTracks = tracks.toMutableList()
        val unmatchedDetections = detections.toMutableList()

        // Greedy best-IoU matching. Same-label pairs are preferred so a pedestrian
        // stepping in front of a car does not inherit the car's growth history.
        while (unmatchedDetections.isNotEmpty() && unmatchedTracks.isNotEmpty()) {
            var bestIou = 0f
            var bestTrack: Track? = null
            var bestDetection: Pair<String, Box>? = null

            for (track in unmatchedTracks) {
                for (det in unmatchedDetections) {
                    var score = iou(track.box, det.second)
                    if (score < config.iouMatchThreshold) continue
                    if (det.first != track.label) score *= LABEL_MISMATCH_PENALTY
                    if (score > bestIou) {
                        bestIou = score
                        bestTrack = track
                        bestDetection = det
                    }
                }
            }
            val track = bestTrack ?: break
            val det = bestDetection ?: break

            track.label = det.first
            track.box = det.second
            track.kalman.update(det.second.width, if (dtSeconds > 0f) dtSeconds else DEFAULT_DT)
            track.lastSeenMs = nowMs
            track.hits++
            track.missed = 0

            unmatchedTracks.remove(track)
            unmatchedDetections.remove(det)
        }

        // Tracks with no detection this frame age out.
        for (track in unmatchedTracks) {
            track.missed++
        }
        tracks.removeAll { it.missed > config.maxMissedFrames }

        // Leftover detections become new tracks.
        for ((label, box) in unmatchedDetections) {
            tracks.add(Track(nextId++, label, box, nowMs, nowMs))
        }
    }

    private companion object {
        /** Down-weights, but does not forbid, matching across a label change. */
        const val LABEL_MISMATCH_PENALTY = 0.5f
        /** Assumed frame interval for the first update of a session (~15 FPS). */
        const val DEFAULT_DT = 0.066f
    }
}
