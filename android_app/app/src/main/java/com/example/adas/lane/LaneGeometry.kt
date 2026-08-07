package com.example.adas.lane

import kotlin.math.abs

/**
 * Lane Departure Warning (AIS-188 / LDWS) — geometry and the departure decision.
 *
 * Classical computer vision, no model: lane markings are high-contrast near-linear
 * structures, which a gradient scan finds cheaply and which needs no training
 * data and carries no licence. That matters here — LDWS is the second India-
 * mandated function after DDAWS, and this implementation is firewall-clean by
 * construction.
 *
 * Pure Kotlin (no Android imports) so the line fitting and the departure state
 * machine are unit-testable. Pixel work lives in [LaneDetector].
 *
 * **Honest limitations**, all of which are properties of monocular LDW rather
 * than of this code: unmarked or faded lane lines — the common case on much of
 * the Indian road network — yield no lane at all, which is reported as
 * [LaneStatus.NO_LANE] rather than guessed at; and with no turn-indicator signal
 * available over OBD-II, a deliberate signalled lane change looks identical to a
 * drift. The speed gate below is the main defence against nagging.
 */

/** A straight lane boundary fitted in normalized image coordinates: x = f(y). */
data class LaneLine(val slope: Float, val intercept: Float, val support: Int) {
    /** Horizontal position of this boundary at image row [y]. */
    fun xAt(y: Float): Float = slope * y + intercept
}

/** What the lane tracker can currently see. */
enum class LaneStatus {
    /** No usable markings — unmarked road, glare, or heavy occlusion. */
    NO_LANE,
    /** One boundary only; position is estimated but not confirmed. */
    ONE_EDGE,
    /** Both boundaries fitted. */
    BOTH_EDGES
}

enum class DepartureSide { NONE, LEFT, RIGHT }

data class LaneState(
    val status: LaneStatus = LaneStatus.NO_LANE,
    /**
     * Lateral position within the lane: 0 = centred, -1 = on the left marking,
     * +1 = on the right marking.
     */
    val offset: Float = 0f,
    val departure: DepartureSide = DepartureSide.NONE,
    val message: String = "",
    val left: LaneLine? = null,
    val right: LaneLine? = null
)

data class LdwConfig(
    /**
     * LDWS is a highway function: below this speed, lane changes in traffic are
     * constant and warnings become noise. UNECE R130, which AIS-188 follows,
     * specifies activation from 60 km/h.
     */
    val minSpeedKmh: Int = 60,
    /** |offset| beyond which the vehicle counts as departing. */
    val departureThreshold: Float = 0.75f,
    /** Lower bar to *clear* a warning — hysteresis, so it doesn't flicker. */
    val clearThreshold: Float = 0.60f,
    /** Sustained drift required before warning, in consecutive frames. */
    val minDepartureFrames: Int = 3,
    /** Minimum edge points before a fitted line is trusted. */
    val minSupport: Int = 6
)

/**
 * Least-squares fit of x = slope·y + intercept over candidate edge points.
 *
 * Fitting x as a function of y (not the other way round) is deliberate: lane
 * markings run mostly vertically in the image, so x-of-y is well conditioned
 * where y-of-x would blow up on near-vertical lines.
 *
 * @return null if the points are too few or degenerate.
 */
fun fitLaneLine(points: List<Pair<Float, Float>>, minSupport: Int = 6): LaneLine? {
    if (points.size < minSupport) return null
    val n = points.size
    var sumY = 0f
    var sumX = 0f
    var sumYY = 0f
    var sumXY = 0f
    for ((x, y) in points) {
        sumX += x
        sumY += y
        sumYY += y * y
        sumXY += x * y
    }
    // Guard on the spread of y rather than on the raw determinant. Points confined
    // to one scanline carry no slope information, and in float arithmetic their
    // determinant lands near — but not at — zero, which an absolute epsilon lets
    // through and which then produces a wild slope.
    val meanY = sumY / n
    val varianceY = sumYY / n - meanY * meanY
    if (varianceY < MIN_Y_VARIANCE) return null

    val denom = n * sumYY - sumY * sumY
    if (abs(denom) < 1e-9f) return null
    val slope = (n * sumXY - sumY * sumX) / denom
    val intercept = (sumX - slope * sumY) / n
    return LaneLine(slope, intercept, n)
}

/** Points must span at least ~1 % of frame height (sd 0.01) to define a slope. */
private const val MIN_Y_VARIANCE = 1e-4f

/**
 * Lateral position of the ego vehicle between two boundaries at row [y].
 *
 * The camera looks straight ahead, so image centre x = 0.5 is the vehicle's own
 * track. Returns 0 when centred, ±1 at the markings.
 */
fun lateralOffset(left: LaneLine, right: LaneLine, y: Float): Float {
    val lx = left.xAt(y)
    val rx = right.xAt(y)
    val halfWidth = (rx - lx) / 2f
    if (halfWidth <= 1e-4f) return 0f
    val center = (lx + rx) / 2f
    return ((0.5f - center) / halfWidth).coerceIn(-2f, 2f)
}

/**
 * Departure state machine. Stateful across frames — one instance per session.
 */
class LaneDepartureTracker(private val config: LdwConfig = LdwConfig()) {

    private var consecutiveLeft = 0
    private var consecutiveRight = 0
    private var active: DepartureSide = DepartureSide.NONE

    fun reset() {
        consecutiveLeft = 0
        consecutiveRight = 0
        active = DepartureSide.NONE
    }

    /**
     * @param left fitted left boundary, or null.
     * @param right fitted right boundary, or null.
     * @param speedKmh ego speed; null (no OBD) is treated as *not* meeting the
     *   speed gate, because LDWS below 60 km/h is a nuisance and an unknown speed
     *   is more often city driving than highway.
     * @param evaluateAtY image row to measure at — near the bottom, where the
     *   markings are closest to the vehicle and best resolved.
     */
    fun update(
        left: LaneLine?,
        right: LaneLine?,
        speedKmh: Int?,
        evaluateAtY: Float = 0.92f
    ): LaneState {
        val status = when {
            left != null && right != null -> LaneStatus.BOTH_EDGES
            left != null || right != null -> LaneStatus.ONE_EDGE
            else -> LaneStatus.NO_LANE
        }

        if (status != LaneStatus.BOTH_EDGES) {
            reset()
            return LaneState(status = status, left = left, right = right)
        }

        val offset = lateralOffset(left!!, right!!, evaluateAtY)

        // Speed gate: measure and display, but never warn below the threshold.
        if (speedKmh == null || speedKmh < config.minSpeedKmh) {
            reset()
            return LaneState(status, offset, DepartureSide.NONE, "", left, right)
        }

        // Negative offset = drifted left of centre.
        val threshold =
            if (active == DepartureSide.NONE) config.departureThreshold else config.clearThreshold
        when {
            offset <= -threshold -> {
                consecutiveLeft++
                consecutiveRight = 0
            }
            offset >= threshold -> {
                consecutiveRight++
                consecutiveLeft = 0
            }
            else -> {
                consecutiveLeft = 0
                consecutiveRight = 0
                active = DepartureSide.NONE
            }
        }

        val side = when {
            consecutiveLeft >= config.minDepartureFrames -> DepartureSide.LEFT
            consecutiveRight >= config.minDepartureFrames -> DepartureSide.RIGHT
            else -> DepartureSide.NONE
        }
        active = side

        val message = when (side) {
            DepartureSide.LEFT -> "⚠️  LANE DEPARTURE — DRIFTING LEFT"
            DepartureSide.RIGHT -> "⚠️  LANE DEPARTURE — DRIFTING RIGHT"
            DepartureSide.NONE -> ""
        }
        return LaneState(status, offset, side, message, left, right)
    }
}
