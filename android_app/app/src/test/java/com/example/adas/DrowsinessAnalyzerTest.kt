package com.example.adas

import com.example.adas.dms.DmsConfig
import com.example.adas.dms.DmsPhase
import com.example.adas.dms.DriverAlertLevel
import com.example.adas.dms.DrowsinessAnalyzer
import com.example.adas.dms.FaceLandmarks
import com.example.adas.dms.FaceMesh
import com.example.adas.dms.Landmark
import com.example.adas.dms.SimulatedFaceLandmarkSource
import com.example.adas.dms.SyntheticFace
import com.example.adas.dms.averageEyeAspectRatio
import com.example.adas.dms.eyeAspectRatio
import com.example.adas.dms.headYawProxy
import com.example.adas.dms.mouthAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DDAWS (AIS-184) unit tests — signal geometry plus the full alert state machine.
 *
 * The analyzer takes an explicit `nowMs` on every call, so these drive simulated
 * time directly: no clock, no camera, no device.
 */
class DrowsinessAnalyzerTest {

    private val cfg = DmsConfig()

    /** Feed frames at [stepMs] for [durationMs], returning the final state. */
    private fun DrowsinessAnalyzer.run(
        startMs: Long,
        durationMs: Long,
        stepMs: Long = 66,
        face: (Long) -> FaceLandmarks?
    ): Pair<Long, com.example.adas.dms.DmsState> {
        var t = startMs
        var state = update(face(t), t)
        val end = startMs + durationMs
        while (t < end) {
            t += stepMs
            state = update(face(t), t)
        }
        return t to state
    }

    /** Drives calibration to completion with a neutral face. Returns the end time. */
    private fun DrowsinessAnalyzer.calibrate(startMs: Long = 0L): Long {
        val (t, state) = run(startMs, cfg.calibrationMs + 200) { SyntheticFace.build() }
        assertEquals(DmsPhase.MONITORING, state.phase)
        return t
    }

    // ── Signal geometry ──────────────────────────────────────────────────────

    @Test
    fun `open eye has a realistic EAR`() {
        val ear = averageEyeAspectRatio(SyntheticFace.build(eyeOpenness = 1f))
        assertTrue("expected ~0.30, got $ear", ear in 0.25f..0.35f)
    }

    @Test
    fun `closed eye EAR collapses toward zero`() {
        assertTrue(averageEyeAspectRatio(SyntheticFace.build(eyeOpenness = 0f)) < 0.02f)
    }

    @Test
    fun `EAR scales with openness`() {
        val half = averageEyeAspectRatio(SyntheticFace.build(eyeOpenness = 0.5f))
        val full = averageEyeAspectRatio(SyntheticFace.build(eyeOpenness = 1f))
        assertEquals(full / 2f, half, 1e-3f)
    }

    @Test
    fun `both eyes report the same EAR on a symmetric face`() {
        val face = SyntheticFace.build(eyeOpenness = 0.7f)
        assertEquals(
            eyeAspectRatio(face, FaceMesh.LEFT_EYE),
            eyeAspectRatio(face, FaceMesh.RIGHT_EYE),
            1e-4f
        )
    }

    @Test
    fun `closed mouth MAR is small and a yawn is large`() {
        assertTrue(mouthAspectRatio(SyntheticFace.build(mouthOpenness = 0f)) < 0.1f)
        assertTrue(mouthAspectRatio(SyntheticFace.build(mouthOpenness = 1f)) > 0.5f)
    }

    @Test
    fun `head yaw proxy is zero facing forward and signed when turned`() {
        assertEquals(0f, headYawProxy(SyntheticFace.build(yaw = 0f)), 1e-4f)
        assertEquals(0.6f, headYawProxy(SyntheticFace.build(yaw = 0.6f)), 1e-3f)
        assertEquals(-0.6f, headYawProxy(SyntheticFace.build(yaw = -0.6f)), 1e-3f)
    }

    @Test
    fun `degenerate landmarks do not divide by zero`() {
        val flat = FaceLandmarks(List(FaceMesh.MAX_INDEX + 1) { Landmark(0.5f, 0.5f) })
        assertEquals(0f, averageEyeAspectRatio(flat), 1e-6f)
        assertEquals(0f, mouthAspectRatio(flat), 1e-6f)
        assertEquals(0f, headYawProxy(flat), 1e-6f)
    }

    @Test
    fun `a short landmark list is rejected rather than crashing`() {
        val short = FaceLandmarks(List(10) { Landmark(0.5f, 0.5f) })
        assertTrue(!short.isUsable)
        val state = DrowsinessAnalyzer(cfg).update(short, 0L)
        assertEquals(DriverAlertLevel.NONE, state.alert)
    }

    // ── Calibration ──────────────────────────────────────────────────────────

    @Test
    fun `analyzer starts idle and enters calibration on first face`() {
        val a = DrowsinessAnalyzer(cfg)
        val state = a.update(SyntheticFace.build(), 0L)
        assertEquals(DmsPhase.CALIBRATING, state.phase)
        assertEquals(DriverAlertLevel.NONE, state.alert)
    }

    @Test
    fun `calibration reports progress and completes on schedule`() {
        val a = DrowsinessAnalyzer(cfg)
        val (_, mid) = a.run(0L, cfg.calibrationMs / 2) { SyntheticFace.build() }
        assertEquals(DmsPhase.CALIBRATING, mid.phase)
        assertTrue(mid.calibrationProgress in 0.4f..0.6f)

        val (_, done) = a.run(cfg.calibrationMs / 2 + 66, cfg.calibrationMs) { SyntheticFace.build() }
        assertEquals(DmsPhase.MONITORING, done.phase)
        assertEquals(1f, done.calibrationProgress, 1e-6f)
    }

    @Test
    fun `baseline is personal to the driver`() {
        val narrow = DrowsinessAnalyzer(cfg)
        narrow.run(0L, cfg.calibrationMs + 200) { SyntheticFace.build(eyeOpenness = 0.5f) }
        val wide = DrowsinessAnalyzer(cfg)
        wide.run(0L, cfg.calibrationMs + 200) { SyntheticFace.build(eyeOpenness = 1f) }

        val narrowBase = narrow.update(SyntheticFace.build(eyeOpenness = 0.5f), 10_000L).baselineEar!!
        val wideBase = wide.update(SyntheticFace.build(eyeOpenness = 1f), 10_000L).baselineEar!!
        assertTrue("narrow-eyed baseline must be lower", narrowBase < wideBase)
    }

    @Test
    fun `a narrow-eyed driver at their own baseline is not flagged drowsy`() {
        // The whole point of calibration: 0.5 openness is this driver's normal.
        val a = DrowsinessAnalyzer(cfg)
        val (t, _) = a.run(0L, cfg.calibrationMs + 200) { SyntheticFace.build(eyeOpenness = 0.5f) }
        val (_, state) = a.run(t + 66, 30_000) { SyntheticFace.build(eyeOpenness = 0.5f) }
        assertEquals(DriverAlertLevel.NONE, state.alert)
        assertTrue(state.perclos < 0.01f)
    }

    @Test
    fun `losing the face mid-calibration restarts it`() {
        val a = DrowsinessAnalyzer(cfg)
        a.run(0L, cfg.calibrationMs / 2) { SyntheticFace.build() }
        a.update(null, cfg.calibrationMs / 2 + 66)

        // Immediately after the gap we are back at the beginning, not near done.
        val resumed = a.update(SyntheticFace.build(), cfg.calibrationMs / 2 + 132)
        assertEquals(DmsPhase.CALIBRATING, resumed.phase)
        assertTrue("progress should have reset", resumed.calibrationProgress < 0.1f)
    }

    // ── PERCLOS / drowsiness ─────────────────────────────────────────────────

    @Test
    fun `alert driver with normal blinks raises no alert`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        // 200 ms blink every 4 s ≈ 5 % closure, below the caution threshold.
        val (_, state) = a.run(t + 66, 40_000) { ms ->
            SyntheticFace.build(eyeOpenness = if (ms % 4_000 < 200) 0.1f else 1f)
        }
        assertEquals(DriverAlertLevel.NONE, state.alert)
        assertTrue("PERCLOS ${state.perclos} should stay low", state.perclos < cfg.perclosCaution)
    }

    @Test
    fun `sustained partial closure drives PERCLOS into danger`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        // Eyes closed 500 ms of every 1500 ms ≈ 33 % closure.
        val (_, state) = a.run(t + 66, 40_000) { ms ->
            SyntheticFace.build(eyeOpenness = if (ms % 1_500 < 500) 0.2f else 0.9f)
        }
        assertEquals(DriverAlertLevel.DANGER, state.alert)
        assertTrue(state.perclos > cfg.perclosDanger)
        assertTrue(state.message.contains("DROWSY"))
    }

    @Test
    fun `PERCLOS does not fire before the window has enough history`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        // Fully closed, but only for 3 s — shorter than perclosMinSpanMs.
        val (_, state) = a.run(t + 66, 3_000) { SyntheticFace.build(eyeOpenness = 0f) }
        assertTrue(
            "should not be a PERCLOS alert yet",
            !state.message.contains("DROWSY") && !state.message.contains("FATIGUE DETECTED")
        )
    }

    @Test
    fun `PERCLOS window forgets old closures`() {
        val short = DmsConfig(perclosWindowMs = 5_000, perclosMinSpanMs = 2_000)
        val a = DrowsinessAnalyzer(short)
        var t = 0L
        val (t1, _) = a.run(t, short.calibrationMs + 200) { SyntheticFace.build() }
        t = t1
        // Drowsy stretch...
        val (t2, drowsy) = a.run(t + 66, 8_000) { SyntheticFace.build(eyeOpenness = 0.1f) }
        assertTrue(drowsy.perclos > 0.5f)
        // ...then fully alert for longer than the window.
        val (_, recovered) = a.run(t2 + 66, 8_000) { SyntheticFace.build(eyeOpenness = 1f) }
        assertTrue("stale closures must age out, got ${recovered.perclos}", recovered.perclos < 0.1f)
    }

    // ── Microsleep ───────────────────────────────────────────────────────────

    @Test
    fun `unbroken closure past the threshold is a microsleep`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        val (_, state) = a.run(t + 66, cfg.microsleepMs + 500) { SyntheticFace.build(eyeOpenness = 0f) }
        assertEquals(DriverAlertLevel.DANGER, state.alert)
        assertTrue(state.message.contains("MICROSLEEP"))
        assertTrue(state.eyesClosedMs >= cfg.microsleepMs)
    }

    @Test
    fun `a blink shorter than the threshold is not a microsleep`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        val (_, state) = a.run(t + 66, 300) { SyntheticFace.build(eyeOpenness = 0f) }
        assertTrue(!state.message.contains("MICROSLEEP"))
    }

    @Test
    fun `opening the eyes clears the closure timer`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        val (t2, _) = a.run(t + 66, 1_000) { SyntheticFace.build(eyeOpenness = 0f) }
        val state = a.update(SyntheticFace.build(eyeOpenness = 1f), t2 + 66)
        assertEquals(0L, state.eyesClosedMs)
    }

    @Test
    fun `microsleep outranks a drowsy PERCLOS reading`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        val (t2, drowsy) = a.run(t + 66, 30_000) { ms ->
            SyntheticFace.build(eyeOpenness = if (ms % 1_500 < 500) 0.2f else 0.9f)
        }
        assertTrue(drowsy.perclos > cfg.perclosDanger)
        val (_, state) = a.run(t2 + 66, cfg.microsleepMs + 300) { SyntheticFace.build(eyeOpenness = 0f) }
        assertTrue("microsleep must win the ladder", state.message.contains("MICROSLEEP"))
    }

    // ── Yawning ──────────────────────────────────────────────────────────────

    @Test
    fun `sustained wide mouth counts as a yawn`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        val (_, state) = a.run(t + 66, cfg.yawnMinMs + 400) { SyntheticFace.build(mouthOpenness = 1f) }
        assertEquals(1, state.yawns)
    }

    @Test
    fun `a brief mouth opening is not a yawn`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        val (_, state) = a.run(t + 66, 300) { SyntheticFace.build(mouthOpenness = 1f) }
        assertEquals(0, state.yawns)
    }

    @Test
    fun `one long yawn is counted once, not once per frame`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        val (_, state) = a.run(t + 66, 5_000) { SyntheticFace.build(mouthOpenness = 1f) }
        assertEquals(1, state.yawns)
    }

    @Test
    fun `repeated yawns raise a fatigue caution`() {
        val a = DrowsinessAnalyzer(cfg)
        var t = a.calibrate()
        repeat(cfg.yawnCautionCount) {
            val (open, _) = a.run(t + 66, cfg.yawnMinMs + 200) { SyntheticFace.build(mouthOpenness = 1f) }
            val (closed, _) = a.run(open + 66, 500) { SyntheticFace.build(mouthOpenness = 0f) }
            t = closed
        }
        val state = a.update(SyntheticFace.build(), t + 66)
        assertEquals(cfg.yawnCautionCount, state.yawns)
        assertEquals(DriverAlertLevel.CAUTION, state.alert)
        assertTrue(state.message.contains("YAWNS"))
    }

    // ── Attention ────────────────────────────────────────────────────────────

    @Test
    fun `sustained look away is an eyes-off-road danger`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        val (_, state) = a.run(t + 66, cfg.distractionMs + 500) { SyntheticFace.build(yaw = 0.6f) }
        assertEquals(DriverAlertLevel.DANGER, state.alert)
        assertTrue(state.message.contains("EYES OFF ROAD"))
    }

    @Test
    fun `a quick glance is not a distraction`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        val (_, state) = a.run(t + 66, 500) { SyntheticFace.build(yaw = 0.6f) }
        assertEquals(DriverAlertLevel.NONE, state.alert)
    }

    @Test
    fun `looking back at the road clears the distraction`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        val (t2, away) = a.run(t + 66, cfg.distractionMs + 300) { SyntheticFace.build(yaw = 0.6f) }
        assertEquals(DriverAlertLevel.DANGER, away.alert)
        val back = a.update(SyntheticFace.build(yaw = 0f), t2 + 66)
        assertEquals(DriverAlertLevel.NONE, back.alert)
    }

    @Test
    fun `small head movements stay under the threshold`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        val (_, state) = a.run(t + 66, 5_000) { SyntheticFace.build(yaw = 0.2f) }
        assertEquals(DriverAlertLevel.NONE, state.alert)
    }

    // ── Missing face ─────────────────────────────────────────────────────────

    @Test
    fun `brief face loss is tolerated silently`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        val state = a.update(null, t + 200)
        assertEquals(DriverAlertLevel.NONE, state.alert)
    }

    @Test
    fun `prolonged face loss raises a not-visible caution`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        val (_, state) = a.run(t + 66, cfg.faceLostMs + 500) { null }
        assertEquals(DmsPhase.NO_FACE, state.phase)
        assertEquals(DriverAlertLevel.CAUTION, state.alert)
        assertTrue(state.message.contains("NOT VISIBLE"))
    }

    @Test
    fun `closure does not accumulate across a face gap`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        val (t2, _) = a.run(t + 66, 1_000) { SyntheticFace.build(eyeOpenness = 0f) }
        a.update(null, t2 + 66)
        val state = a.update(SyntheticFace.build(eyeOpenness = 0f), t2 + 132)
        assertTrue("closure timer must restart after the gap", state.eyesClosedMs < cfg.microsleepMs)
    }

    @Test
    fun `ear is null while no face is visible`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        assertNull(a.update(null, t + 100).ear)
    }

    @Test
    fun `reset clears the calibrated baseline`() {
        val a = DrowsinessAnalyzer(cfg)
        val t = a.calibrate()
        assertNotNull(a.update(SyntheticFace.build(), t + 66).baselineEar)
        a.reset()
        val state = a.update(SyntheticFace.build(), t + 132)
        assertEquals(DmsPhase.CALIBRATING, state.phase)
        assertNull(state.baselineEar)
    }

    // ── Simulated source script ──────────────────────────────────────────────

    @Test
    fun `simulated script exercises every severity level`() {
        val sim = SimulatedFaceLandmarkSource()
        val a = DrowsinessAnalyzer(cfg)
        val seen = mutableSetOf<DriverAlertLevel>()
        val messages = mutableSetOf<String>()
        var t = 0L
        while (t < 46_000) {
            val s = a.update(sim.frameAt(t), t)
            seen.add(s.alert)
            if (s.message.isNotEmpty()) messages.add(s.message)
            t += 66
        }
        assertTrue("expected a NONE stretch", DriverAlertLevel.NONE in seen)
        assertTrue("expected a CAUTION stretch", DriverAlertLevel.CAUTION in seen)
        assertTrue("expected a DANGER stretch", DriverAlertLevel.DANGER in seen)
        assertTrue("expected a microsleep", messages.any { it.contains("MICROSLEEP") })
        assertTrue("expected an attention warning", messages.any { it.contains("EYES OFF ROAD") })
        assertTrue("expected a not-visible warning", messages.any { it.contains("NOT VISIBLE") })
    }

    @Test
    fun `simulated script drops the face at the end of the loop`() {
        val sim = SimulatedFaceLandmarkSource()
        assertNotNull(sim.frameAt(1_000))
        assertNull(sim.frameAt(44_000))
    }
}
