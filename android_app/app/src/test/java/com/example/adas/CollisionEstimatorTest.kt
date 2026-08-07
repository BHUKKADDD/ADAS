package com.example.adas

import com.example.adas.fcw.Box
import com.example.adas.fcw.CollisionAssessment
import com.example.adas.fcw.CollisionEstimator
import com.example.adas.fcw.FcwConfig
import com.example.adas.fcw.HORIZON_Y
import com.example.adas.fcw.ScaleKalman
import com.example.adas.fcw.ThreatLevel
import com.example.adas.fcw.approximateDistanceMeters
import com.example.adas.fcw.corridorHalfWidth
import com.example.adas.fcw.iou
import com.example.adas.fcw.isInEgoPath
import com.example.adas.fcw.timeToCollision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Forward Collision Warning tests.
 *
 * Scenes are projected from real geometry rather than hand-drawn boxes: an object
 * of known size at distance `d` and lateral offset `x` projects to a box via the
 * pinhole relation, and its ground-contact row rises toward the horizon as it
 * recedes. A constant-speed approach therefore has a known true TTC at every
 * frame, which makes it possible to assert the estimator alerts at roughly the
 * *right time*, not merely that it alerts.
 */
class CollisionEstimatorTest {

    private val frameMs = 66L                 // ~15 FPS, the app's measured rate
    private val focal = 0.785f                // normalized focal length, 65° HFOV
    private val cameraHeightM = 1.2f          // dashcam height above the road

    private data class Size(val widthM: Float, val heightM: Float)

    private val sizes = mapOf(
        "car" to Size(1.7f, 1.5f),
        "truck" to Size(2.4f, 3.0f),
        "person" to Size(0.5f, 1.7f)
    )

    /** Project an object of class [label] at [distanceM] and [lateralM] offset. */
    private fun boxAt(distanceM: Float, lateralM: Float = 0f, label: String = "car"): Box {
        val size = sizes.getValue(label)
        val w = size.widthM * focal / distanceM
        val h = size.heightM * focal / distanceM
        val cx = 0.5f + lateralM * focal / distanceM
        // Ground contact projects below the horizon by cameraHeight·f/d.
        val bottom = (HORIZON_Y + cameraHeightM * focal / distanceM).coerceAtMost(1f)
        return Box(cx - w / 2f, bottom - h, cx + w / 2f, bottom)
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    @Test
    fun `iou is 1 for identical boxes and 0 for disjoint ones`() {
        val b = Box(0.1f, 0.1f, 0.3f, 0.3f)
        assertEquals(1f, iou(b, b), 1e-5f)
        assertEquals(0f, iou(b, Box(0.6f, 0.6f, 0.8f, 0.8f)), 1e-5f)
    }

    @Test
    fun `iou is partial for overlapping boxes`() {
        val a = Box(0f, 0f, 0.2f, 0.2f)
        val b = Box(0.1f, 0f, 0.3f, 0.2f)
        assertEquals(1f / 3f, iou(a, b), 1e-4f)
    }

    @Test
    fun `corridor widens toward the bumper`() {
        assertTrue(corridorHalfWidth(1.0f) > corridorHalfWidth(0.6f))
        assertTrue(corridorHalfWidth(0.6f) > corridorHalfWidth(HORIZON_Y))
    }

    @Test
    fun `vehicle in the ego lane is in the ego path at every range`() {
        for (d in listOf(8f, 15f, 25f, 40f)) {
            assertTrue("failed at ${d}m", isInEgoPath(boxAt(d)))
        }
    }

    @Test
    fun `vehicle one lane over is not in the ego path at any range`() {
        for (d in listOf(10f, 20f, 40f)) {
            assertTrue("false positive at ${d}m", !isInEgoPath(boxAt(d, lateralM = 3.5f)))
        }
    }

    @Test
    fun `object above the horizon is never in the ego path`() {
        // An overhead gantry or traffic light: entirely above the horizon row.
        assertTrue(!isInEgoPath(Box(0.45f, 0.1f, 0.55f, 0.3f)))
    }

    @Test
    fun `merely clipping the corridor edge is not in the ego path`() {
        // Meaningful overlap is required, otherwise a neighbouring vehicle whose
        // box just grazes the corridor would raise alerts.
        val half = corridorHalfWidth(0.6f)
        val edge = 0.5f + half
        assertTrue(!isInEgoPath(Box(edge - 0.005f, 0.5f, edge + 0.2f, 0.6f)))
    }

    // ── Kalman / TTC math ────────────────────────────────────────────────────

    @Test
    fun `ttc is null when not closing`() {
        assertNull(timeToCollision(inverseWidth = 16f, inverseWidthRate = 0f))
        assertNull(timeToCollision(inverseWidth = 16f, inverseWidthRate = 2f))
    }

    @Test
    fun `ttc is inverse width over closing rate`() {
        // u = 16 (40 m at K = 2.5), closing at 6 u/s (15 m/s) -> 2.67 s.
        assertEquals(2.667f, timeToCollision(16f, -6f)!!, 1e-3f)
    }

    @Test
    fun `kalman settles on a constant width and reports no collision`() {
        val k = ScaleKalman(initialWidth = 0.2f)
        repeat(40) { k.update(0.2f, 0.066f) }
        assertEquals(0.2f, k.width, 0.01f)
        assertNull("a static object must not produce a TTC", k.ttcSeconds)
    }

    @Test
    fun `kalman recovers the true ttc of a constant-speed approach`() {
        val k = ScaleKalman(initialWidth = 1.7f * focal / 40f)
        var d = 40f
        val v = 15f
        while (d > 12f) {
            d -= v * 0.066f
            k.update(1.7f * focal / d, 0.066f)
        }
        val trueTtc = d / v
        assertEquals(trueTtc, k.ttcSeconds!!, trueTtc * 0.2f)
    }

    @Test
    fun `kalman does not manufacture a collision out of jitter`() {
        val k = ScaleKalman(initialWidth = 0.2f)
        val jitter = listOf(0.19f, 0.21f, 0.18f, 0.22f, 0.20f, 0.19f, 0.21f, 0.20f)
        repeat(8) { jitter.forEach { w -> k.update(w, 0.066f) } }
        val ttc = k.ttcSeconds
        assertTrue("jitter produced a spurious TTC of $ttc", ttc == null || ttc > 20f)
    }

    @Test
    fun `approximate distance is nearer for a wider box`() {
        assertTrue(
            approximateDistanceMeters(boxAt(10f), "car")!! <
                approximateDistanceMeters(boxAt(40f), "car")!!
        )
    }

    @Test
    fun `approximate distance is null for an unknown class`() {
        assertNull(approximateDistanceMeters(boxAt(10f), "traffic_light"))
    }

    // ── Estimator: approach scenarios ────────────────────────────────────────

    private fun approach(
        estimator: CollisionEstimator,
        label: String = "car",
        startDistanceM: Float = 40f,
        closingSpeedMs: Float = 15f,
        stopAtDistanceM: Float = 8f,
        lateralM: Float = 0f,
        egoSpeedKmh: Int? = 54
    ): List<CollisionAssessment> {
        val out = mutableListOf<CollisionAssessment>()
        var d = startDistanceM
        var t = 1_000L
        while (d > stopAtDistanceM) {
            out.add(estimator.update(listOf(label to boxAt(d, lateralM, label)), t, egoSpeedKmh))
            d -= closingSpeedMs * (frameMs / 1000f)
            t += frameMs
        }
        return out
    }

    @Test
    fun `closing vehicle eventually raises a danger alert`() {
        val results = approach(CollisionEstimator())
        assertTrue("expected a DANGER at some point",
            results.any { it.level == ThreatLevel.DANGER })
        assertTrue(results.last().message.contains("BRAKE"))
    }

    @Test
    fun `caution precedes danger during an approach`() {
        val results = approach(CollisionEstimator())
        val firstCaution = results.indexOfFirst { it.level == ThreatLevel.CAUTION }
        val firstDanger = results.indexOfFirst { it.level == ThreatLevel.DANGER }
        assertTrue("no caution stage", firstCaution >= 0)
        assertTrue("no danger stage", firstDanger >= 0)
        assertTrue("caution must come first", firstCaution < firstDanger)
    }

    @Test
    fun `estimated ttc tracks the true ttc`() {
        val estimator = CollisionEstimator()
        val v = 15f
        var d = 40f
        var t = 1_000L
        var checked = 0
        while (d > 8f) {
            val a = estimator.update(listOf("car" to boxAt(d)), t, 54)
            val ttc = a.threats.firstOrNull()?.ttcSeconds
            if (ttc != null && d < 28f) {
                val trueTtc = d / v
                assertEquals("at ${d}m the TTC estimate was off", trueTtc, ttc, trueTtc * 0.35f)
                checked++
            }
            d -= v * (frameMs / 1000f)
            t += frameMs
        }
        assertTrue("never produced a TTC to check", checked > 5)
    }

    @Test
    fun `ttc estimate is not biased late`() {
        // The failure mode that motivated filtering inverse width: a filter that
        // lags reports more time than there is, and warns too late.
        val estimator = CollisionEstimator()
        val v = 15f
        var d = 40f
        var t = 1_000L
        var overestimates = 0
        var samples = 0
        while (d > 10f) {
            val ttc = estimator.update(listOf("car" to boxAt(d)), t, 54)
                .threats.firstOrNull()?.ttcSeconds
            if (ttc != null && d < 28f) {
                samples++
                if (ttc > d / v * 1.15f) overestimates++
            }
            d -= v * (frameMs / 1000f)
            t += frameMs
        }
        assertTrue("no samples", samples > 5)
        assertTrue("TTC overestimated in $overestimates/$samples frames",
            overestimates < samples / 4)
    }

    @Test
    fun `stationary object ahead does not alert`() {
        // The exact case the old centre-zone heuristic got wrong: a parked car
        // dead ahead, filling the middle of the frame, with no closing motion.
        val estimator = CollisionEstimator()
        var t = 1_000L
        var last = estimator.update(emptyList(), t, 54)
        repeat(60) {
            t += frameMs
            last = estimator.update(listOf("car" to boxAt(12f)), t, 54)
        }
        assertEquals(ThreatLevel.NONE, last.level)
    }

    @Test
    fun `receding vehicle does not alert`() {
        val estimator = CollisionEstimator()
        var d = 12f
        var t = 1_000L
        var last = estimator.update(emptyList(), t, 54)
        while (d < 40f) {
            t += frameMs
            last = estimator.update(listOf("car" to boxAt(d)), t, 54)
            d += 10f * (frameMs / 1000f)
        }
        assertEquals(ThreatLevel.NONE, last.level)
        assertNull(last.threats.first().ttcSeconds)
    }

    @Test
    fun `vehicle closing in the next lane does not alert`() {
        val results = approach(CollisionEstimator(), lateralM = 3.5f, stopAtDistanceM = 10f)
        assertTrue("alerted on a vehicle that was never in the ego lane",
            results.none { it.level != ThreatLevel.NONE })
    }

    @Test
    fun `alerts are suppressed when the vehicle is confirmed stopped`() {
        val results = approach(CollisionEstimator(), egoSpeedKmh = 0)
        assertTrue(results.all { it.level == ThreatLevel.NONE })
    }

    @Test
    fun `unknown ego speed does not suppress alerts`() {
        // null means "no OBD adapter", not "stopped" — the alert must still fire.
        val results = approach(CollisionEstimator(), egoSpeedKmh = null)
        assertTrue(results.any { it.level == ThreatLevel.DANGER })
    }

    @Test
    fun `a pedestrian is warned about earlier than a car`() {
        val carFirstDanger = approach(CollisionEstimator(), label = "car")
            .indexOfFirst { it.level == ThreatLevel.DANGER }
        val personFirstDanger = approach(CollisionEstimator(), label = "person")
            .indexOfFirst { it.level == ThreatLevel.DANGER }
        assertTrue("both should eventually alert", carFirstDanger > 0 && personFirstDanger > 0)
        assertTrue("vulnerable road users must be warned earlier",
            personFirstDanger < carFirstDanger)
    }

    @Test
    fun `no alert is raised before the track matures`() {
        val cfg = FcwConfig()
        val estimator = CollisionEstimator(cfg)
        var d = 15f
        var t = 1_000L
        repeat(cfg.minHits - 1) {
            val a = estimator.update(listOf("car" to boxAt(d)), t, 54)
            assertEquals(ThreatLevel.NONE, a.level)
            assertNull(a.threats.firstOrNull()?.ttcSeconds)
            d -= 15f * (frameMs / 1000f)
            t += frameMs
        }
    }

    // ── Estimator: tracking behaviour ────────────────────────────────────────

    @Test
    fun `the same object keeps its track id across frames`() {
        val estimator = CollisionEstimator()
        var t = 1_000L
        var d = 30f
        val id = estimator.update(listOf("car" to boxAt(d)), t, 54).threats.single().trackId
        repeat(10) {
            t += frameMs
            d -= 1f
            assertEquals(id, estimator.update(listOf("car" to boxAt(d)), t, 54).threats.single().trackId)
        }
    }

    @Test
    fun `two objects are tracked separately`() {
        val a = CollisionEstimator().update(
            listOf(
                "car" to boxAt(30f, lateralM = -3.5f),
                "truck" to boxAt(30f, lateralM = 3.5f, label = "truck")
            ),
            1_000L, 54
        )
        assertEquals(2, a.threats.size)
        assertEquals(2, a.threats.map { it.trackId }.toSet().size)
    }

    @Test
    fun `a track is dropped after too many missed frames`() {
        val cfg = FcwConfig()
        val estimator = CollisionEstimator(cfg)
        var t = 1_000L
        estimator.update(listOf("car" to boxAt(30f)), t, 54)
        var last = estimator.update(emptyList(), t, 54)
        repeat(cfg.maxMissedFrames + 2) {
            t += frameMs
            last = estimator.update(emptyList(), t, 54)
        }
        assertTrue("stale track should be gone", last.threats.isEmpty())
    }

    @Test
    fun `a brief detector dropout does not kill the track`() {
        val estimator = CollisionEstimator()
        var t = 1_000L
        val id = estimator.update(listOf("car" to boxAt(30f)), t, 54).threats.single().trackId
        t += frameMs
        estimator.update(emptyList(), t, 54)      // one dropped frame
        t += frameMs
        val back = estimator.update(listOf("car" to boxAt(29f)), t, 54)
        assertEquals(id, back.threats.single().trackId)
    }

    @Test
    fun `the most urgent threat drives the alert when several are in path`() {
        val estimator = CollisionEstimator()
        var far = 40f      // truck, closing slowly
        var near = 25f     // pedestrian, closing fast
        var t = 1_000L
        var last = estimator.update(emptyList(), t, 54)
        repeat(20) {
            t += frameMs
            last = estimator.update(
                listOf("truck" to boxAt(far, label = "truck"), "person" to boxAt(near, label = "person")),
                t, 54
            )
            far -= 3f * (frameMs / 1000f)
            near -= 15f * (frameMs / 1000f)
        }
        assertNotNull("expected an alert", last.primary)
        assertEquals("person", last.primary!!.label)
    }

    @Test
    fun `reset clears all tracks`() {
        val estimator = CollisionEstimator()
        estimator.update(listOf("car" to boxAt(30f)), 1_000L, 54)
        estimator.reset()
        val a = estimator.update(listOf("car" to boxAt(30f)), 2_000L, 54)
        assertEquals(1, a.threats.size)
        assertNull(a.threats.single().ttcSeconds)   // fresh, immature track
    }

    @Test
    fun `empty detection list is handled`() {
        val a = CollisionEstimator().update(emptyList(), 1_000L, 54)
        assertEquals(ThreatLevel.NONE, a.level)
        assertTrue(a.threats.isEmpty())
    }
}
