package com.example.adas.obd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * A [TelemetrySource] that fabricates a realistic vehicle-speed curve — no
 * Bluetooth, no car. It exists so the HUD, alerts, and full UI can be exercised
 * on the bench (the same reason the detector was first validated off a monitor).
 *
 * It drives the exact same [TelemetrySource] contract as [ObdBleManager], so the
 * ViewModel and HUD cannot tell which one is active.
 */
class SimulatedTelemetrySource : TelemetrySource {

    private val _connectionState = MutableStateFlow(ObdConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ObdConnectionState> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow(VehicleTelemetry())
    override val telemetry: StateFlow<VehicleTelemetry> = _telemetry.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var scope: CoroutineScope? = null

    // A loop of target speeds (km/h). The curve ramps toward each in turn and
    // dwells briefly on arrival: idle → cruise → city-traffic slowdown → highway
    // → hard stop, then repeats.
    private val targets = intArrayOf(0, 45, 60, 30, 55, 0, 25, 70, 40, 0)

    override fun start() {
        if (scope != null) return // already running
        _lastError.value = null
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch {
            _connectionState.value = ObdConnectionState.CONNECTING
            delay(600) // mimic connect + init latency
            _connectionState.value = ObdConnectionState.CONNECTED

            var current = 0.0
            var index = 0
            var dwellTicks = 0
            while (isActive) {
                val target = targets[index].toDouble()
                if (abs(current - target) <= STEP_KMH) {
                    current = target
                    if (dwellTicks++ >= DWELL_TICKS) {
                        dwellTicks = 0
                        index = (index + 1) % targets.size
                    }
                } else {
                    current += sign(target - current) * STEP_KMH
                }
                _telemetry.value = VehicleTelemetry(
                    speedKmh = current.roundToInt(),
                    updatedAtMs = System.currentTimeMillis()
                )
                delay(TICK_MS)
            }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        _telemetry.value = VehicleTelemetry()
        _connectionState.value = ObdConnectionState.DISCONNECTED
    }

    private companion object {
        const val TICK_MS = 400L       // emit cadence
        const val STEP_KMH = 3.0       // ≈7.5 km/h per second ramp
        const val DWELL_TICKS = 5      // hold ~2 s on reaching a target
    }
}
