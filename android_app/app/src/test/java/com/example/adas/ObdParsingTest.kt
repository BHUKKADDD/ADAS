package com.example.adas

import com.example.adas.obd.parseCoolantC
import com.example.adas.obd.parseRpm
import com.example.adas.obd.parseSpeedKmh
import com.example.adas.obd.parseThrottlePct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM unit tests for the ELM327 Mode-01 PID reply parsers. */
class ObdParsingTest {

    @Test fun `spaced reply parses`() {
        assertEquals(60, parseSpeedKmh("41 0D 3C"))
    }

    @Test fun `unspaced reply parses`() {
        assertEquals(60, parseSpeedKmh("410D3C"))
    }

    @Test fun `zero speed is not null`() {
        assertEquals(0, parseSpeedKmh("41 0D 00"))
    }

    @Test fun `max speed parses`() {
        assertEquals(255, parseSpeedKmh("410DFF"))
    }

    @Test fun `command echo before frame is ignored`() {
        // Echo on, multi-line, trailing prompt.
        assertEquals(50, parseSpeedKmh("010D\r41 0D 32\r\r>"))
    }

    @Test fun `searching status yields null`() {
        assertNull(parseSpeedKmh("SEARCHING..."))
    }

    @Test fun `no data yields null`() {
        assertNull(parseSpeedKmh("NO DATA"))
    }

    @Test fun `question mark yields null`() {
        assertNull(parseSpeedKmh("?"))
    }

    @Test fun `blank yields null`() {
        assertNull(parseSpeedKmh("   "))
    }

    @Test fun `searching line then valid frame parses`() {
        assertEquals(30, parseSpeedKmh("SEARCHING...\r41 0D 1E\r>"))
    }

    // ── PID 010C (engine RPM: two bytes, /4) ─────────────────────────────────

    @Test fun `rpm spaced reply parses`() {
        // ((0x1A * 256) + 0xF8) / 4 = 6904 / 4
        assertEquals(1726, parseRpm("41 0C 1A F8"))
    }

    @Test fun `rpm unspaced reply parses`() {
        assertEquals(1726, parseRpm("410C1AF8\r>"))
    }

    @Test fun `rpm idle parses`() {
        // 0x0C80 / 4 = 800
        assertEquals(800, parseRpm("41 0C 0C 80"))
    }

    @Test fun `rpm truncated to one data byte yields null`() {
        assertNull(parseRpm("41 0C 1A"))
    }

    @Test fun `rpm no data yields null`() {
        assertNull(parseRpm("NO DATA"))
    }

    // ── PID 0105 (coolant temperature: one byte, −40 offset) ─────────────────

    @Test fun `coolant operating temperature parses`() {
        // 0x7B = 123 − 40 = 83 °C
        assertEquals(83, parseCoolantC("41 05 7B"))
    }

    @Test fun `coolant below zero parses`() {
        // 0x1E = 30 − 40 = −10 °C
        assertEquals(-10, parseCoolantC("41 05 1E"))
    }

    @Test fun `coolant echo and prompt tolerated`() {
        assertEquals(90, parseCoolantC("0105\r41 05 82\r\r>"))
    }

    // ── PID 0111 (throttle position: one byte, ×100/255) ─────────────────────

    @Test fun `throttle closed parses to zero`() {
        assertEquals(0, parseThrottlePct("41 11 00"))
    }

    @Test fun `throttle wide open parses to hundred`() {
        assertEquals(100, parseThrottlePct("41 11 FF"))
    }

    @Test fun `throttle mid position rounds`() {
        // 0x66 = 102 → 102 * 100 / 255 = 40.0
        assertEquals(40, parseThrottlePct("41 11 66"))
    }

    @Test fun `wrong pid frame yields null for other parsers`() {
        // A speed frame must not satisfy the RPM/coolant/throttle parsers.
        assertNull(parseRpm("41 0D 3C"))
        assertNull(parseCoolantC("41 0D 3C"))
        assertNull(parseThrottlePct("41 0D 3C"))
    }
}
