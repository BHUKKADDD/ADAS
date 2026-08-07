package com.example.adas.fcw

/**
 * Forward Collision Warning — geometry and filtering primitives.
 *
 * The alert this feeds replaces a pure presence test ("is there a box near the
 * middle of the frame?") with an actual collision estimate. Presence says nothing
 * about risk: a parked car 50 m ahead and a pedestrian stepping out at 3 m produce
 * identical centre-frame boxes. What separates them is how fast the box is
 * *growing*.
 *
 * **Why the filter tracks inverse width.** Apparent width is inversely
 * proportional to distance, `w = K/d`, so for an approach at constant closing
 * speed `w` grows *super-linearly* — it accelerates. Running a constant-velocity
 * filter directly on `w` therefore lags exactly when it matters, understating the
 * growth rate and overstating time-to-collision as the object gets close. That is
 * the dangerous direction to be wrong in: it warns late.
 *
 * Tracking `u = 1/w ∝ d` instead makes the dynamics *linear* — `u` falls at a
 * constant rate for a constant-speed approach — so a constant-velocity filter is
 * exact rather than merely adequate, and
 *
 *     TTC = -u / u̇
 *
 * still needs no camera calibration, no depth sensor and no known object size.
 * Filtering is not optional either way: a couple of pixels of box jitter, if
 * differenced frame-to-frame, reads as a large velocity change.
 *
 * Deliberately free of Android imports (hence [Box] rather than `RectF`) so the
 * whole estimator is unit-testable on the JVM.
 *
 * MODEL-AGNOSTIC: this consumes bounding boxes, not model internals. Any detector
 * that emits boxes drives it unchanged, so it sits outside the licence firewall
 * exactly like `obd/`, `geo/` and `dms/`.
 */

/** An axis-aligned box in normalized frame coordinates, all edges in [0,1]. */
data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = (width.coerceAtLeast(0f)) * (height.coerceAtLeast(0f))
}

/** Intersection-over-union, used to associate detections with existing tracks. */
fun iou(a: Box, b: Box): Float {
    val left = maxOf(a.left, b.left)
    val top = maxOf(a.top, b.top)
    val right = minOf(a.right, b.right)
    val bottom = minOf(a.bottom, b.bottom)
    if (right <= left || bottom <= top) return 0f
    val inter = (right - left) * (bottom - top)
    val union = a.area + b.area - inter
    return if (union <= 0f) 0f else inter / union
}

/** Frame row above which nothing can be an on-road obstacle. */
const val HORIZON_Y = 0.45f

/**
 * Corridor half-width at the horizon. A 3.5 m lane at 40 m subtends roughly 7 %
 * of frame width on a typical phone FOV, so the far corridor is genuinely tight —
 * a generous value here pulls the next lane into the ego path.
 */
private const val CORRIDOR_HALF_AT_HORIZON = 0.035f
private const val CORRIDOR_HALF_AT_BUMPER = 0.38f

/**
 * Fraction of the narrower of (box, corridor) that must overlap before the object
 * counts as being in the ego path. Requiring *meaningful* overlap rather than any
 * contact stops a box that merely clips the corridor edge — routine for a vehicle
 * one lane over at distance — from raising an alert.
 */
private const val MIN_PATH_OVERLAP_FRACTION = 0.25f

/**
 * The ego vehicle's collision corridor at a given image row.
 *
 * A fixed centre band (what the old alert used) is wrong under perspective: the
 * lane occupies a few percent of frame width at the horizon and most of it at the
 * bumper. The corridor therefore widens linearly toward the bottom of the frame.
 *
 * @param y image row in [0,1], 0 = top.
 * @return half-width of the corridor at that row, in normalized units.
 */
fun corridorHalfWidth(y: Float): Float {
    val t = ((y - HORIZON_Y) / (1f - HORIZON_Y)).coerceIn(0f, 1f)
    return CORRIDOR_HALF_AT_HORIZON +
        t * (CORRIDOR_HALF_AT_BUMPER - CORRIDOR_HALF_AT_HORIZON)
}

/**
 * True when [box] sits in the ego vehicle's path.
 *
 * Judged at the box's bottom edge — the object's ground contact point — because
 * that is what determines which lane it is standing in. Boxes entirely above the
 * horizon are overhead signs, gantries and traffic lights, never obstacles.
 */
fun isInEgoPath(box: Box): Boolean {
    if (box.bottom <= HORIZON_Y) return false
    val half = corridorHalfWidth(box.bottom)
    val corridorLeft = 0.5f - half
    val corridorRight = 0.5f + half

    val overlap = minOf(box.right, corridorRight) - maxOf(box.left, corridorLeft)
    if (overlap <= 0f) return false

    val reference = minOf(box.width, corridorRight - corridorLeft)
    if (reference <= 0f) return false
    return overlap >= reference * MIN_PATH_OVERLAP_FRACTION
}

/**
 * A 1-D constant-velocity Kalman filter over **inverse** apparent width.
 *
 * State is `[u, u̇]` where `u = 1/w ∝ distance`. See the file header for why the
 * inverse is the right domain: it linearises a constant-speed approach, so the
 * filter neither lags nor overstates TTC at close range.
 *
 * @param processNoise acceleration spectral density in `u` units; higher tracks
 *   genuine acceleration (braking, a car pulling out) faster at the cost of
 *   admitting more jitter.
 * @param relativeMeasurementNoise expected detector error in box width as a
 *   fraction of the width itself. Applied relatively because a 2-pixel error on a
 *   small distant box is a far bigger error in `u` than on a large near one.
 */
class ScaleKalman(
    initialWidth: Float,
    private val processNoise: Float = 1.0f,
    private val relativeMeasurementNoise: Float = 0.04f
) {
    private var u: Float = inverseOf(initialWidth)
    private var uRate: Float = 0f

    // Covariance P, symmetric 2x2. Start wide on the rate: it is unknown.
    private var p00 = (0.1f * u) * (0.1f * u)
    private var p01 = 0f
    private var p10 = 0f
    private var p11 = (u * u).coerceAtLeast(1f)

    /** Filtered apparent width. */
    val width: Float get() = if (u > 1e-6f) 1f / u else 0f

    /** Filtered inverse width — proportional to distance. */
    val inverseWidth: Float get() = u

    /** Rate of change of inverse width; negative means closing. */
    val inverseWidthRate: Float get() = uRate

    /** Time to collision in seconds, or null when the object is not closing. */
    val ttcSeconds: Float? get() = timeToCollision(u, uRate)

    /**
     * Advance the state by [dtSeconds] and fold in an observed [measuredWidth].
     *
     * A degenerate (zero or negative) width carries no information and would
     * invert to an enormous outlier that wrecks the filter, so such frames are
     * skipped outright rather than clamped.
     */
    fun update(measuredWidth: Float, dtSeconds: Float) {
        if (measuredWidth <= MIN_WIDTH) return
        val dt = dtSeconds.coerceIn(MIN_DT, MAX_DT)
        val z = inverseOf(measuredWidth)

        // ── Predict: u += u̇·dt, u̇ unchanged ─────────────────────────────────
        u += uRate * dt
        val np00 = p00 + dt * (p10 + p01) + dt * dt * p11
        val np01 = p01 + dt * p11
        val np10 = p10 + dt * p11
        val np11 = p11

        val q = processNoise
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        p00 = np00 + q * dt3 / 3f
        p01 = np01 + q * dt2 / 2f
        p10 = np10 + q * dt2 / 2f
        p11 = np11 + q * dt

        // ── Update against the measurement (H = [1, 0]) ──────────────────────
        val sigma = relativeMeasurementNoise * z
        val r = (sigma * sigma).coerceAtLeast(1e-9f)
        val innovation = z - u
        val s = p00 + r
        if (s <= 0f) return
        val k0 = p00 / s
        val k1 = p10 / s

        u += k0 * innovation
        uRate += k1 * innovation

        // P = (I - K·H)·P, with H = [1, 0]. The second row consumes the *predicted*
        // p00/p01, so snapshot them before the first row overwrites them.
        val predP00 = p00
        val predP01 = p01
        val u00 = 1f - k0
        p00 = u00 * predP00
        p01 = u00 * predP01
        p10 -= k1 * predP00
        p11 -= k1 * predP01
    }

    private fun inverseOf(w: Float): Float =
        if (w > MIN_WIDTH) 1f / w else 1f / MIN_WIDTH

    private companion object {
        const val MIN_DT = 1e-3f
        const val MAX_DT = 1f
        /** Guards against a degenerate zero-width box inverting to infinity. */
        const val MIN_WIDTH = 1e-4f
    }
}

/**
 * Time to collision in seconds from inverse width and its rate, or null when
 * there is no closing motion.
 *
 * `TTC = -u/u̇`, where `u = 1/w ∝ distance`. Null means "not approaching" — a
 * receding, parallel or stationary object. That is a distinct outcome from
 * "approaching slowly", and callers must not collapse the two into a large number.
 */
fun timeToCollision(inverseWidth: Float, inverseWidthRate: Float): Float? {
    if (inverseWidth <= 0f || inverseWidthRate >= -MIN_CLOSING_RATE) return null
    return -inverseWidth / inverseWidthRate
}

/**
 * Closing rate must clear this before an object counts as approaching — below it
 * the signal is indistinguishable from detector jitter.
 */
private const val MIN_CLOSING_RATE = 1e-3f

/**
 * Rough headway in metres — **display only, never used for alerting.**
 *
 * Uses the pinhole relation `d ≈ (W_real · f_px) / w_px` with a class-typical real
 * width and a focal length inferred from an assumed horizontal FOV. Both
 * assumptions are wrong by tens of percent on any given phone and vehicle, which
 * is precisely why the alert decision uses calibration-free TTC instead. Shown to
 * the driver as context, not as a measurement.
 */
fun approximateDistanceMeters(
    box: Box,
    label: String,
    horizontalFovDegrees: Float = 65f
): Float? {
    val realWidth = TYPICAL_WIDTHS_M[label] ?: return null
    if (box.width <= 1e-4f) return null
    val fovRadians = Math.toRadians(horizontalFovDegrees.toDouble() / 2.0)
    val focalNormalized = (0.5 / Math.tan(fovRadians)).toFloat()
    return realWidth * focalNormalized / box.width
}

/** Class-typical real-world widths in metres, Indian-road weighted. */
private val TYPICAL_WIDTHS_M = mapOf(
    "person" to 0.5f,
    "rider" to 0.6f,
    "bicycle" to 0.6f,
    "motorcycle" to 0.7f,
    "autorickshaw" to 1.4f,
    "car" to 1.7f,
    "truck" to 2.4f,
    "bus" to 2.5f,
    "animal" to 0.8f,
    "vehicle_fallback" to 1.7f
)
