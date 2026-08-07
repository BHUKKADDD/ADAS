package com.example.adas.dms

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Driver Monitoring System (DMS) — geometric signal extraction.
 *
 * Regulatory context: MoRTH draft notification GSR 184(E) makes a Driver
 * Drowsiness and Attention Warning System (DDAWS, AIS-184) **mandatory** on M2/M3
 * and N2/N3 vehicles in India — new models from 2026-04-01, existing models from
 * 2026-10-01. This package is the on-device implementation of that function.
 *
 * MODEL-AGNOSTIC / LICENSE FIREWALL: nothing here touches [com.example.adas.InferenceEngine],
 * the v1 IDD detector, or the IDD dataset. It consumes face landmarks from the
 * *front* camera only. Like `obd/`, `geo/`, `upload/` and `privacy/`, it is
 * infrastructure that stays commercially shippable regardless of which detector
 * the rear camera runs.
 *
 * This file is deliberately free of Android and MediaPipe imports so the whole
 * signal chain is unit-testable on the JVM. The landmark *source* is abstracted
 * behind [FaceLandmarkSource]; only its real implementation touches MediaPipe.
 */

/** A single face landmark in normalized image coordinates, both axes in [0,1]. */
data class Landmark(val x: Float, val y: Float)

/**
 * One frame's worth of face landmarks.
 *
 * Index convention is MediaPipe Face Mesh (468 points, or 478 with irises). The
 * constants in [FaceMesh] name the handful of indices the signals need, so a
 * different landmark provider only has to remap those.
 */
data class FaceLandmarks(val points: List<Landmark>) {

    /** True when the landmark list is long enough for every index we read. */
    val isUsable: Boolean get() = points.size > FaceMesh.MAX_INDEX

    operator fun get(index: Int): Landmark = points[index]
}

/** MediaPipe Face Mesh indices for the features DDAWS reads. */
object FaceMesh {
    /**
     * Six-point eye contours in Eye Aspect Ratio order: [outer corner, upper lid,
     * upper lid, inner corner, lower lid, lower lid].
     */
    val RIGHT_EYE = intArrayOf(33, 160, 158, 133, 153, 144)
    val LEFT_EYE = intArrayOf(362, 385, 387, 263, 373, 380)

    /** Inner lip centre points, upper then lower — the vertical of the mouth. */
    const val LIP_UPPER = 13
    const val LIP_LOWER = 14

    /** Mouth corners — the horizontal of the mouth. */
    const val MOUTH_LEFT = 61
    const val MOUTH_RIGHT = 291

    /** Nose tip and the two cheek edges, used for the head-yaw proxy. */
    const val NOSE_TIP = 1
    const val CHEEK_LEFT = 234
    const val CHEEK_RIGHT = 454

    /** Highest index touched above — used by [FaceLandmarks.isUsable]. */
    const val MAX_INDEX = 454
}

/**
 * Eye Aspect Ratio: the ratio of eye height to eye width.
 *
 * EAR = (|p2-p6| + |p3-p5|) / (2 * |p1-p4|)
 *
 * Roughly 0.25–0.35 for an open eye and near 0 for a closed one, but the
 * absolute value varies a lot between people (and with glasses, camera angle and
 * eye shape) — which is why [DrowsinessAnalyzer] calibrates a personal baseline
 * instead of hard-coding a threshold.
 */
fun eyeAspectRatio(face: FaceLandmarks, eye: IntArray): Float {
    val p1 = face[eye[0]]
    val p2 = face[eye[1]]
    val p3 = face[eye[2]]
    val p4 = face[eye[3]]
    val p5 = face[eye[4]]
    val p6 = face[eye[5]]
    val width = dist(p1, p4)
    if (width <= 1e-6f) return 0f
    return (dist(p2, p6) + dist(p3, p5)) / (2f * width)
}

/** Mean EAR across both eyes — the value PERCLOS is computed from. */
fun averageEyeAspectRatio(face: FaceLandmarks): Float =
    (eyeAspectRatio(face, FaceMesh.LEFT_EYE) + eyeAspectRatio(face, FaceMesh.RIGHT_EYE)) / 2f

/**
 * Mouth Aspect Ratio — mouth height over mouth width. Yawning drives this well
 * above the closed-mouth baseline; speech moves it much less.
 */
fun mouthAspectRatio(face: FaceLandmarks): Float {
    val width = dist(face[FaceMesh.MOUTH_LEFT], face[FaceMesh.MOUTH_RIGHT])
    if (width <= 1e-6f) return 0f
    return dist(face[FaceMesh.LIP_UPPER], face[FaceMesh.LIP_LOWER]) / width
}

/**
 * Head-yaw proxy in roughly [-1, 1]: 0 looking straight ahead, negative turned
 * left, positive turned right.
 *
 * Compares the nose tip's horizontal distance to each cheek edge. This is a
 * cheap monocular stand-in for real head pose — enough to catch "eyes off the
 * road" for the attention half of DDAWS, not enough for gaze tracking. A real
 * PnP head-pose solve is the upgrade, behind this same function.
 */
fun headYawProxy(face: FaceLandmarks): Float {
    val nose = face[FaceMesh.NOSE_TIP]
    val left = face[FaceMesh.CHEEK_LEFT]
    val right = face[FaceMesh.CHEEK_RIGHT]
    val toLeft = abs(nose.x - left.x)
    val toRight = abs(right.x - nose.x)
    val total = toLeft + toRight
    if (total <= 1e-6f) return 0f
    // +1 when the nose sits on the left cheek edge (head turned right), -1 opposite.
    return (toLeft - toRight) / total
}

private fun dist(a: Landmark, b: Landmark): Float = hypot(a.x - b.x, a.y - b.y)
