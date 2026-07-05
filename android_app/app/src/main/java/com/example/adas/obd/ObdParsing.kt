package com.example.adas.obd

import java.util.UUID

/**
 * Pure, Android-free helpers for the OBD-II / ELM327 layer.
 *
 * Kept transport-agnostic and side-effect-free so [parseSpeedKmh] can be
 * exercised by plain JVM unit tests (see `ObdParsingTest`).
 */

// ── ELM327 response parsing ──────────────────────────────────────────────────

// Status strings an ELM327 can return in place of a data frame. Compared against
// whitespace-stripped, uppercased lines, so no spaces here.
private val NON_DATA_TOKENS = listOf(
    "NODATA", "SEARCHING", "STOPPED", "UNABLETOCONNECT",
    "BUSINIT", "BUSERROR", "CANERROR", "ERROR", "?", "OK"
)

private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * Parse the ELM327 reply to a Mode 01 PID 0D (vehicle speed) request.
 *
 * A positive response echoes the mode with +0x40 and the PID, followed by one
 * data byte: `41 0D XX`, where `XX` is the speed in km/h (0–255). We tolerate
 * spaces on/off, command echo, header/prompt characters, and interleaved status
 * lines ("SEARCHING...", "NO DATA", …).
 *
 * @return speed in km/h, or null if the reply holds no valid `41 0D` frame.
 */
fun parseSpeedKmh(raw: String): Int? {
    if (raw.isBlank()) return null
    // ELM327 delimits frames with CR; '>' is the ready prompt.
    val lines = raw.uppercase().split('\r', '\n', '>')
    for (line in lines) {
        val compact = line.replace(" ", "").trim()
        if (compact.isEmpty()) continue
        if (NON_DATA_TOKENS.any { compact.contains(it) }) continue
        if (!compact.all { it in HEX_DIGITS }) continue // not a data frame
        val idx = compact.indexOf("410D")
        if (idx >= 0 && idx + 6 <= compact.length) {
            return compact.substring(idx + 4, idx + 6).toIntOrNull(16)
        }
    }
    return null
}

// ── GATT layout discovery ────────────────────────────────────────────────────

/** A candidate ELM327 GATT layout: the service and its write/notify characteristics. */
data class GattUuidTriple(
    val service: UUID,
    val notify: UUID,
    val write: UUID
)

private fun uuid16(short: String): UUID =
    UUID.fromString("0000$short-0000-1000-8000-00805F9B34FB")

/**
 * Known ELM327-BLE GATT layouts, tried before falling back to property-based
 * auto-discovery (find any writable characteristic + any notifiable one).
 */
val KNOWN_ELM327_GATT: List<GattUuidTriple> = listOf(
    // Veepeak OBDCheck BLE and many FFF0 clones: notify FFF1, write FFF2.
    GattUuidTriple(uuid16("FFF0"), uuid16("FFF1"), uuid16("FFF2")),
    // Vgate iCar Pro BLE 4.0 / FFE0 clones: a single FFE1 (write + notify).
    GattUuidTriple(uuid16("FFE0"), uuid16("FFE1"), uuid16("FFE1")),
    // Nordic UART Service, used by some ELM327 BLE bridges.
    GattUuidTriple(
        UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
        UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
        UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    )
)

/** Client Characteristic Configuration Descriptor — used to enable notifications. */
val CCCD_UUID: UUID = uuid16("2902")

/** Advertised-name fragments that identify an ELM327-class adapter (uppercased match). */
val ELM327_NAME_HINTS = listOf(
    "OBD", "ELM", "VLINK", "VGATE", "ICAR", "VEEPEAK", "OBDII", "IOS-VLINK", "V-LINK"
)
