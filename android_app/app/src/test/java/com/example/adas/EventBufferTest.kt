package com.example.adas

import com.example.adas.rec.Clip
import com.example.adas.rec.EventBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event-recorder ring buffer tests.
 *
 * Frames are plain integers here; on device they are redacted JPEG bytes. Time is
 * passed in explicitly, so every windowing and cooldown boundary is exercised
 * deterministically.
 */
class EventBufferTest {

    private val frameMs = 100L

    /** Feed frames from [fromMs] up to but not including [toMs]. */
    private fun EventBuffer<Int>.feed(
        fromMs: Long,
        toMs: Long,
        onClip: (Clip<Int>) -> Unit = {}
    ): Long {
        var t = fromMs
        var seq = (fromMs / frameMs).toInt()
        while (t < toMs) {
            add(seq++, t)?.let(onClip)
            t += frameMs
        }
        return t
    }

    // ── Idle buffering ───────────────────────────────────────────────────────

    @Test
    fun `idle buffer keeps only the pre-roll window`() {
        val b = EventBuffer<Int>(preRollMs = 1_000, postRollMs = 500)
        b.feed(0, 5_000)
        val held = b.snapshot()
        assertTrue("buffer grew past the pre-roll window", held.size <= 11)
        assertTrue(held.last().tMs - held.first().tMs <= 1_000)
    }

    @Test
    fun `idle buffer never exceeds the frame cap`() {
        val b = EventBuffer<Int>(preRollMs = 1_000_000, postRollMs = 500, maxFrames = 20)
        b.feed(0, 10_000)
        assertEquals(20, b.bufferedFrames)
    }

    @Test
    fun `nothing is emitted without a trigger`() {
        val b = EventBuffer<Int>(preRollMs = 1_000, postRollMs = 500)
        var clips = 0
        b.feed(0, 10_000) { clips++ }
        assertEquals(0, clips)
    }

    // ── Trigger / clip assembly ──────────────────────────────────────────────

    @Test
    fun `clip contains both pre-roll history and post-roll tail`() {
        val b = EventBuffer<Int>(preRollMs = 2_000, postRollMs = 1_000)
        var t = b.feed(0, 5_000)
        assertTrue(b.trigger("DANGER", t))

        var clip: Clip<Int>? = null
        b.feed(t, t + 2_000) { clip = it }

        assertNotNull("clip should have completed", clip)
        val c = clip!!
        assertTrue("missing pre-roll", c.frames.first().tMs <= c.triggeredAtMs - 1_500)
        assertTrue("missing post-roll", c.frames.last().tMs >= c.triggeredAtMs + 1_000)
    }

    @Test
    fun `clip records the trigger reason`() {
        val b = EventBuffer<Int>(preRollMs = 1_000, postRollMs = 500)
        var t = b.feed(0, 2_000)
        b.trigger("DANGER: person ahead", t)
        var clip: Clip<Int>? = null
        b.feed(t, t + 1_000) { clip = it }
        assertEquals("DANGER: person ahead", clip!!.reason)
    }

    @Test
    fun `clip duration spans pre-roll plus post-roll`() {
        val b = EventBuffer<Int>(preRollMs = 2_000, postRollMs = 1_000)
        var t = b.feed(0, 6_000)
        b.trigger("DANGER", t)
        var clip: Clip<Int>? = null
        b.feed(t, t + 2_000) { clip = it }
        assertTrue("clip too short: ${clip!!.durationMs}ms", clip!!.durationMs >= 2_800)
    }

    @Test
    fun `frames stop being evicted while recording`() {
        val b = EventBuffer<Int>(preRollMs = 500, postRollMs = 2_000)
        var t = b.feed(0, 2_000)
        val beforeTrigger = b.bufferedFrames
        b.trigger("DANGER", t)
        b.feed(t, t + 1_500)   // still recording, no clip yet
        assertTrue("post-roll frames were evicted", b.bufferedFrames > beforeTrigger)
    }

    @Test
    fun `isRecording reflects the recording state`() {
        val b = EventBuffer<Int>(preRollMs = 1_000, postRollMs = 500)
        var t = b.feed(0, 1_000)
        assertFalse(b.isRecording)
        b.trigger("DANGER", t)
        assertTrue(b.isRecording)
        b.feed(t, t + 1_000)
        assertFalse(b.isRecording)
    }

    // ── Re-trigger, cooldown, caps ───────────────────────────────────────────

    @Test
    fun `re-triggering extends the clip instead of starting a second one`() {
        val b = EventBuffer<Int>(preRollMs = 1_000, postRollMs = 1_000)
        var t = b.feed(0, 2_000)
        b.trigger("DANGER", t)

        val clips = mutableListOf<Clip<Int>>()
        // Keep re-triggering for a while, then let it run out.
        var now = t
        repeat(15) {
            b.add(0, now)?.let(clips::add)
            b.trigger("DANGER", now)
            now += frameMs
        }
        b.feed(now, now + 2_000) { clips.add(it) }

        assertEquals("should be one extended clip, not several", 1, clips.size)
        assertTrue(clips.first().durationMs > 2_000)
    }

    @Test
    fun `a clip cannot be extended past the maximum length`() {
        val b = EventBuffer<Int>(
            preRollMs = 500, postRollMs = 1_000, maxClipMs = 2_000, maxFrames = 10_000
        )
        var t = b.feed(0, 1_000)
        b.trigger("DANGER", t)
        val triggeredAt = t

        var clip: Clip<Int>? = null
        var now = t
        repeat(60) {
            b.add(0, now)?.let { clip = it }
            b.trigger("DANGER", now)   // hazard persists the whole time
            now += frameMs
        }
        assertNotNull("clip must terminate at the cap", clip)
        assertTrue(clip!!.frames.last().tMs <= triggeredAt + 2_000 + frameMs)
    }

    @Test
    fun `cooldown suppresses an immediate re-trigger`() {
        val b = EventBuffer<Int>(preRollMs = 500, postRollMs = 500, cooldownMs = 3_000)
        var t = b.feed(0, 1_000)
        b.trigger("DANGER", t)
        var clip: Clip<Int>? = null
        t = b.feed(t, t + 1_000) { clip = it }
        assertNotNull(clip)

        assertFalse("cooldown should block this", b.trigger("DANGER", t))
    }

    @Test
    fun `a trigger after the cooldown is accepted`() {
        val b = EventBuffer<Int>(preRollMs = 500, postRollMs = 500, cooldownMs = 1_000)
        var t = b.feed(0, 1_000)
        b.trigger("DANGER", t)
        t = b.feed(t, t + 1_000) {}
        t = b.feed(t, t + 2_000)   // wait out the cooldown
        assertTrue(b.trigger("DANGER", t))
    }

    @Test
    fun `frame cap still applies while recording`() {
        val b = EventBuffer<Int>(preRollMs = 10_000, postRollMs = 10_000, maxFrames = 15)
        var t = b.feed(0, 1_000)
        b.trigger("DANGER", t)
        b.feed(t, t + 5_000)
        assertTrue("memory ceiling breached: ${b.bufferedFrames}", b.bufferedFrames <= 15)
    }

    // ── Flush / reset ────────────────────────────────────────────────────────

    @Test
    fun `flush closes an in-progress recording early`() {
        val b = EventBuffer<Int>(preRollMs = 1_000, postRollMs = 10_000)
        var t = b.feed(0, 2_000)
        b.trigger("DANGER", t)
        t = b.feed(t, t + 1_000)
        val clip = b.flush(t)
        assertNotNull("teardown must not lose the clip", clip)
        assertFalse(b.isRecording)
    }

    @Test
    fun `flush with nothing recording returns null`() {
        val b = EventBuffer<Int>(preRollMs = 1_000, postRollMs = 500)
        val t = b.feed(0, 1_000)
        assertNull(b.flush(t))
    }

    @Test
    fun `pre-roll survives so a following event still has context`() {
        val b = EventBuffer<Int>(preRollMs = 2_000, postRollMs = 500, cooldownMs = 0)
        var t = b.feed(0, 3_000)
        b.trigger("DANGER", t)
        t = b.feed(t, t + 1_000) {}
        assertTrue("buffer should retain pre-roll after a clip", b.bufferedFrames > 1)
    }

    @Test
    fun `reset clears frames and the cooldown`() {
        val b = EventBuffer<Int>(preRollMs = 1_000, postRollMs = 500, cooldownMs = 10_000)
        var t = b.feed(0, 1_000)
        b.trigger("DANGER", t)
        t = b.feed(t, t + 1_000) {}
        b.reset()
        assertEquals(0, b.bufferedFrames)
        assertTrue("reset must clear the cooldown too", b.trigger("DANGER", t))
    }

    @Test
    fun `payloads are preserved in order`() {
        val b = EventBuffer<Int>(preRollMs = 10_000, postRollMs = 300)
        var t = 0L
        repeat(10) { b.add(it, t); t += frameMs }
        b.trigger("DANGER", t)
        var clip: Clip<Int>? = null
        repeat(5) { b.add(100 + it, t)?.let { c -> clip = c }; t += frameMs }
        val payloads = clip!!.frames.map { it.payload }
        assertEquals(payloads.sorted(), payloads)
        assertTrue(payloads.contains(0))
        assertTrue(payloads.any { it >= 100 })
    }
}
