package com.example.adas

import com.example.adas.narrate.EventContext
import com.example.adas.narrate.NarrationObject
import com.example.adas.narrate.TemplateNarrator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Event narration: context assembly, prompt rendering, deterministic narrator. */
class EventNarratorTest {

    private fun context(
        objects: List<NarrationObject> = emptyList(),
        speedKmh: Int? = 48,
        reason: String = "⚠️  PERSON AHEAD — BRAKE!  ·  TTC 1.2s"
    ) = EventContext(
        reason = reason,
        triggeredAtMs = 1_700_000_000_000,
        durationMs = 12_000,
        frameCount = 180,
        speedKmh = speedKmh,
        objects = objects
    )

    private val person = NarrationObject("person", 0.88f, minTtcSeconds = 1.2f, wasInEgoPath = true)
    private val car = NarrationObject("car", 0.94f, minTtcSeconds = 6.0f, wasInEgoPath = true)
    private val autorickshaw = NarrationObject("autorickshaw", 0.71f, wasInEgoPath = false)

    // ── Primary object selection ─────────────────────────────────────────────

    @Test
    fun `primary object is the closest in-path threat, not the most confident`() {
        // The car scores higher but the person is the one nearly hit.
        val c = context(listOf(car, person))
        assertEquals("person", c.primaryObject?.label)
    }

    @Test
    fun `primary falls back to confidence when nothing was closing`() {
        val c = context(listOf(autorickshaw, NarrationObject("bus", 0.9f)))
        assertEquals("bus", c.primaryObject?.label)
    }

    @Test
    fun `primary is null with no objects`() {
        assertEquals(null, context().primaryObject)
    }

    @Test
    fun `an out-of-path object never outranks an in-path threat`() {
        val closeButBeside = NarrationObject("truck", 0.99f, minTtcSeconds = 0.5f, wasInEgoPath = false)
        assertEquals("person", context(listOf(closeButBeside, person)).primaryObject?.label)
    }

    // ── Prompt rendering ─────────────────────────────────────────────────────

    @Test
    fun `prompt carries the trigger, speed and road users`() {
        val p = context(listOf(person, autorickshaw)).toPrompt()
        assertTrue(p.contains("PERSON AHEAD"))
        assertTrue(p.contains("48 km/h"))
        assertTrue(p.contains("person"))
        assertTrue(p.contains("autorickshaw"))
    }

    @Test
    fun `prompt marks in-path objects`() {
        assertTrue(context(listOf(person)).toPrompt().contains("in ego lane"))
    }

    @Test
    fun `prompt states when nothing was detected`() {
        assertTrue(context().toPrompt().contains("none detected"))
    }

    @Test
    fun `prompt omits speed when unknown rather than inventing one`() {
        val p = context(speedKmh = null).toPrompt()
        assertTrue(!p.contains("ego speed"))
    }

    @Test
    fun `prompt includes location only when both coordinates are present`() {
        val withLoc = context().copy(latitude = 12.9716, longitude = 77.5946)
        assertTrue(withLoc.toPrompt().contains("12.97"))
        assertTrue(!context().copy(latitude = 12.97).toPrompt().contains("location"))
    }

    @Test
    fun `prompt instructs against speculation`() {
        // Small models editorialise unless told not to.
        assertTrue(context().toPrompt().contains("do not speculate"))
    }

    // ── Template narration ───────────────────────────────────────────────────

    @Test
    fun `narration names the primary road user and the closing time`() = runTest {
        val text = TemplateNarrator().narrate(context(listOf(person)))
        assertTrue(text.contains("person"))
        assertTrue(text.contains("1.2"))
        assertTrue(text.contains("48 km/h"))
    }

    @Test
    fun `narration describes a stationary vehicle correctly`() = runTest {
        val text = TemplateNarrator().narrate(context(listOf(person), speedKmh = 0))
        assertTrue(text.startsWith("Stationary"))
    }

    @Test
    fun `narration handles an event with no detections`() = runTest {
        val text = TemplateNarrator().narrate(context())
        assertTrue(text.isNotBlank())
        assertTrue(text.contains("triggered"))
    }

    @Test
    fun `narration summarises additional road users`() = runTest {
        val text = TemplateNarrator().narrate(
            context(listOf(person, autorickshaw, NarrationObject("car", 0.5f)))
        )
        assertTrue(text.contains("Also present"))
    }

    @Test
    fun `narration pluralises repeated classes`() = runTest {
        val text = TemplateNarrator().narrate(
            context(listOf(person, NarrationObject("car", 0.5f), NarrationObject("car", 0.4f)))
        )
        assertTrue("expected a pluralised count, got: $text", text.contains("2 cars"))
    }

    @Test
    fun `narration underscores are rendered as words`() = runTest {
        val text = TemplateNarrator().narrate(
            context(listOf(NarrationObject("vehicle_fallback", 0.9f, 2f, true)))
        )
        assertTrue(text.contains("vehicle fallback"))
        assertTrue(!text.contains("vehicle_fallback"))
    }

    @Test
    fun `narration strips alert glyphs from the trigger reason`() = runTest {
        val text = TemplateNarrator().narrate(context())
        assertTrue("raw alert markup leaked into prose: $text", !text.contains("⚠"))
    }

    @Test
    fun `narration includes driver and lane state when present`() = runTest {
        val c = context(listOf(person)).copy(
            driverAlert = "microsleep detected",
            laneDeparture = "drifting left"
        )
        val text = TemplateNarrator().narrate(c)
        assertTrue(text.contains("microsleep"))
        assertTrue(text.contains("drifting left"))
    }

    @Test
    fun `narration reports clip length`() = runTest {
        assertTrue(TemplateNarrator().narrate(context()).contains("12s"))
    }

    @Test
    fun `narration is deterministic`() = runTest {
        val c = context(listOf(person, autorickshaw))
        assertEquals(TemplateNarrator().narrate(c), TemplateNarrator().narrate(c))
    }
}
