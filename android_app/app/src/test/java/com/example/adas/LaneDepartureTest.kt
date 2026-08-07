package com.example.adas

import com.example.adas.lane.DepartureSide
import com.example.adas.lane.LaneDepartureTracker
import com.example.adas.lane.LaneLine
import com.example.adas.lane.LaneStatus
import com.example.adas.lane.LdwConfig
import com.example.adas.lane.fitLaneLine
import com.example.adas.lane.lateralOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lane Departure Warning (AIS-188) tests: line fitting and the warning state machine. */
class LaneDepartureTest {

    private val cfg = LdwConfig()

    /** Boundaries for a vehicle offset by [offset] from lane centre. */
    private fun lane(offset: Float): Pair<LaneLine, LaneLine> {
        // Lane half-width 0.25 at the bottom; centre sits at 0.5 - offset*0.25.
        val center = 0.5f - offset * 0.25f
        val left = LaneLine(slope = 0.30f, intercept = center - 0.25f - 0.30f, support = 10)
        val right = LaneLine(slope = -0.30f, intercept = center + 0.25f + 0.30f, support = 10)
        return left to right
    }

    private fun drive(
        tracker: LaneDepartureTracker,
        offset: Float,
        frames: Int,
        speedKmh: Int? = 80
    ) = (1..frames).map {
        val (l, r) = lane(offset)
        tracker.update(l, r, speedKmh, evaluateAtY = 1.0f)
    }.last()

    // ── Line fitting ─────────────────────────────────────────────────────────

    @Test
    fun `fits a straight line through clean points`() {
        val pts = (0..9).map { i ->
            val y = 0.5f + i * 0.05f
            (0.2f * y + 0.1f) to y
        }
        val line = fitLaneLine(pts)
        assertNotNull(line)
        assertEquals(0.2f, line!!.slope, 1e-3f)
        assertEquals(0.1f, line.intercept, 1e-3f)
    }

    @Test
    fun `rejects a fit with too little support`() {
        val pts = listOf(0.3f to 0.8f, 0.31f to 0.85f)
        assertNull(fitLaneLine(pts, minSupport = 6))
    }

    @Test
    fun `rejects degenerate points that share one row`() {
        val pts = (0..9).map { i -> (0.2f + i * 0.01f) to 0.8f }
        assertNull(fitLaneLine(pts, minSupport = 6))
    }

    @Test
    fun `records the supporting point count`() {
        val pts = (0..7).map { i -> (0.3f) to (0.5f + i * 0.05f) }
        assertEquals(8, fitLaneLine(pts)!!.support)
    }

    // ── Lateral offset ───────────────────────────────────────────────────────

    @Test
    fun `offset is zero when centred`() {
        val (l, r) = lane(0f)
        assertEquals(0f, lateralOffset(l, r, 1.0f), 1e-3f)
    }

    @Test
    fun `offset is negative when drifted left`() {
        val (l, r) = lane(-0.8f)
        assertTrue(lateralOffset(l, r, 1.0f) < -0.5f)
    }

    @Test
    fun `offset is positive when drifted right`() {
        val (l, r) = lane(0.8f)
        assertTrue(lateralOffset(l, r, 1.0f) > 0.5f)
    }

    @Test
    fun `offset is clamped for degenerate boundaries`() {
        val same = LaneLine(0f, 0.5f, 10)
        assertEquals(0f, lateralOffset(same, same, 1.0f), 1e-6f)
    }

    // ── Departure state machine ──────────────────────────────────────────────

    @Test
    fun `centred driving raises no warning`() {
        assertEquals(DepartureSide.NONE, drive(LaneDepartureTracker(cfg), 0f, 10).departure)
    }

    @Test
    fun `sustained left drift warns`() {
        val state = drive(LaneDepartureTracker(cfg), -0.9f, 10)
        assertEquals(DepartureSide.LEFT, state.departure)
        assertTrue(state.message.contains("LEFT"))
    }

    @Test
    fun `sustained right drift warns`() {
        assertEquals(DepartureSide.RIGHT, drive(LaneDepartureTracker(cfg), 0.9f, 10).departure)
    }

    @Test
    fun `a brief wobble does not warn`() {
        val tracker = LaneDepartureTracker(cfg)
        val state = drive(tracker, -0.9f, cfg.minDepartureFrames - 1)
        assertEquals(DepartureSide.NONE, state.departure)
    }

    @Test
    fun `returning to centre clears the warning`() {
        val tracker = LaneDepartureTracker(cfg)
        assertEquals(DepartureSide.LEFT, drive(tracker, -0.9f, 10).departure)
        assertEquals(DepartureSide.NONE, drive(tracker, 0f, 5).departure)
    }

    @Test
    fun `hysteresis holds the warning through minor recovery`() {
        val tracker = LaneDepartureTracker(cfg)
        drive(tracker, -0.9f, 10)
        // Between the clear and departure thresholds: still warning.
        val state = drive(tracker, -0.68f, 5)
        assertEquals(DepartureSide.LEFT, state.departure)
    }

    @Test
    fun `no warning below the speed gate`() {
        val state = drive(LaneDepartureTracker(cfg), -0.9f, 10, speedKmh = 40)
        assertEquals(DepartureSide.NONE, state.departure)
        // Position is still measured and reported, just not warned on.
        assertEquals(LaneStatus.BOTH_EDGES, state.status)
        assertTrue(state.offset < -0.5f)
    }

    @Test
    fun `unknown speed does not warn`() {
        // Unlike FCW, a null speed suppresses LDW: without a speed reading this is
        // more likely city driving, where lane-change warnings are noise.
        assertEquals(
            DepartureSide.NONE,
            drive(LaneDepartureTracker(cfg), -0.9f, 10, speedKmh = null).departure
        )
    }

    @Test
    fun `speed exactly at the gate warns`() {
        val state = drive(LaneDepartureTracker(cfg), -0.9f, 10, speedKmh = cfg.minSpeedKmh)
        assertEquals(DepartureSide.LEFT, state.departure)
    }

    // ── Degraded input ───────────────────────────────────────────────────────

    @Test
    fun `unmarked road reports no lane rather than guessing`() {
        val state = LaneDepartureTracker(cfg).update(null, null, 80)
        assertEquals(LaneStatus.NO_LANE, state.status)
        assertEquals(DepartureSide.NONE, state.departure)
        assertEquals(0f, state.offset, 1e-6f)
    }

    @Test
    fun `a single visible boundary is reported but not warned on`() {
        val (l, _) = lane(0f)
        val state = LaneDepartureTracker(cfg).update(l, null, 80)
        assertEquals(LaneStatus.ONE_EDGE, state.status)
        assertEquals(DepartureSide.NONE, state.departure)
    }

    @Test
    fun `losing the markings clears an active warning`() {
        val tracker = LaneDepartureTracker(cfg)
        assertEquals(DepartureSide.LEFT, drive(tracker, -0.9f, 10).departure)
        assertEquals(DepartureSide.NONE, tracker.update(null, null, 80).departure)
    }

    @Test
    fun `reset clears the departure counters`() {
        val tracker = LaneDepartureTracker(cfg)
        drive(tracker, -0.9f, cfg.minDepartureFrames - 1)
        tracker.reset()
        val state = drive(tracker, -0.9f, 1)
        assertEquals(DepartureSide.NONE, state.departure)
    }
}
