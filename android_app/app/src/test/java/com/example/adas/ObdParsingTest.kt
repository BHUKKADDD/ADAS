package com.example.adas

import com.example.adas.obd.parseSpeedKmh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM unit tests for the ELM327 PID-010D (vehicle speed) reply parser. */
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
}
