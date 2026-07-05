package com.example.adas.obd

import kotlinx.coroutines.flow.StateFlow

/**
 * Vehicle telemetry infrastructure — deliberately decoupled from the detector.
 *
 * This package reads OBD-II data from an ELM327 adapter and knows nothing about
 * the TFLite model, the IDD dataset, or inference. It is model-agnostic edge
 * infrastructure: a [TelemetrySource] produces a live [VehicleTelemetry] stream,
 * and the app renders it on the HUD. Two implementations sit behind this one
 * interface — [ObdBleManager] (real ELM327 over Bluetooth LE) and
 * [SimulatedTelemetrySource] (a synthetic speed curve for bench testing without
 * a car) — so callers never depend on the transport.
 */

/** Connection lifecycle of a [TelemetrySource]. */
enum class ObdConnectionState {
    /** No source active / adapter released. */
    DISCONNECTED,
    /** BLE scan in progress, looking for an ELM327 adapter. */
    SCANNING,
    /** Adapter found; GATT connection + service discovery underway. */
    CONNECTING,
    /** GATT ready; running the ELM327 AT init handshake. */
    INITIALIZING,
    /** Streaming valid telemetry. */
    CONNECTED,
    /** Fatal error (see [TelemetrySource.lastError]). */
    ERROR
}

/**
 * A snapshot of vehicle telemetry. Speed-first for now; new PIDs (RPM, coolant,
 * throttle, …) become additional nullable fields plus one line in the poller.
 *
 * @param speedKmh   Vehicle speed from OBD-II PID 010D, or null if not yet read.
 * @param updatedAtMs [System.currentTimeMillis] when this snapshot was produced.
 */
data class VehicleTelemetry(
    val speedKmh: Int? = null,
    val updatedAtMs: Long = 0L
)

/**
 * A live source of [VehicleTelemetry]. All state is exposed as [StateFlow] so the
 * ViewModel can mirror whichever source is currently active.
 */
interface TelemetrySource {
    val connectionState: StateFlow<ObdConnectionState>
    val telemetry: StateFlow<VehicleTelemetry>
    /** Human-readable reason for the most recent [ObdConnectionState.ERROR], or null. */
    val lastError: StateFlow<String?>

    /** Begin connecting / producing telemetry. Idempotent. */
    fun start()

    /** Stop, release the transport, and reset to [ObdConnectionState.DISCONNECTED]. */
    fun stop()
}
