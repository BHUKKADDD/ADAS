package com.example.adas.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Real ELM327-over-Bluetooth-LE [TelemetrySource].
 *
 * Pipeline: scan → connect GATT → discover a write+notify characteristic pair
 * (known ELM327 layouts first, then property-based auto-discovery) → enable
 * notifications → run the ELM327 AT handshake → poll OBD-II PID 010D (speed).
 *
 * BLE allows exactly one outstanding GATT operation at a time, so all commands
 * run sequentially through [sendCommand], which writes and then suspends until
 * [onCharacteristicChanged] delivers a reply terminated by the '>' ready prompt.
 */
@SuppressLint("MissingPermission") // callers gated by [hasBtPermission]; UI requests the runtime grants
class ObdBleManager(context: Context) : TelemetrySource {

    private val appContext = context.applicationContext

    private val _connectionState = MutableStateFlow(ObdConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ObdConnectionState> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow(VehicleTelemetry())
    override val telemetry: StateFlow<VehicleTelemetry> = _telemetry.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var scope: CoroutineScope? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    // ── Command <-> response bridge (single outstanding op) ───────────────────
    private val lock = Any()
    private val responseBuf = StringBuilder()
    @Volatile private var pending: CompletableDeferred<String>? = null
    private var descriptorWritten: CompletableDeferred<Boolean>? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun start() {
        if (scope != null) return
        _lastError.value = null

        if (!hasBtPermission()) {
            fail("Bluetooth permission not granted")
            return
        }
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            fail("Bluetooth is off")
            return
        }
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            fail("BLE not available")
            return
        }

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        scanner = leScanner
        _connectionState.value = ObdConnectionState.SCANNING
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // No service filter: many ELM327 adapters don't advertise their service
        // UUID. Match by advertised name in the callback instead.
        leScanner.startScan(null, settings, scanCallback)

        s.launch {
            delay(SCAN_TIMEOUT_MS)
            if (_connectionState.value == ObdConnectionState.SCANNING) {
                fail("No ELM327 adapter found")
            }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanner = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {}
        gatt = null
        writeChar = null
        notifyChar = null
        pending = null
        synchronized(lock) { responseBuf.setLength(0) }
        _telemetry.value = VehicleTelemetry()
        _connectionState.value = ObdConnectionState.DISCONNECTED
    }

    // ── Scanning ──────────────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = try {
                result.device.name ?: result.scanRecord?.deviceName
            } catch (_: SecurityException) { null } ?: return
            val upper = name.uppercase()
            if (ELM327_NAME_HINTS.none { upper.contains(it) }) return
            Log.d(TAG, "Found candidate adapter: $name")
            try { scanner?.stopScan(this) } catch (_: Exception) {}
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            fail("BLE scan failed (code $errorCode)")
        }
    }

    private fun connect(device: BluetoothDevice) {
        _connectionState.value = ObdConnectionState.CONNECTING
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // ── GATT callbacks ────────────────────────────────────────────────────────

    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT connected, discovering services")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (scope != null && _connectionState.value != ObdConnectionState.DISCONNECTED) {
                    fail("Adapter disconnected")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Service discovery failed ($status)")
                return
            }
            val pair = pickCharacteristics(g)
            if (pair == null) {
                fail("No compatible ELM327 GATT service")
                return
            }
            notifyChar = pair.notify
            writeChar = pair.write
            Log.d(TAG, "Using notify=${pair.notify.uuid} write=${pair.write.uuid}")
            startSession(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorWritten?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+ delivers the value directly.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) = handleIncoming(value)

        // Pre-33 path.
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) = handleIncoming(characteristic.value)
    }

    private fun handleIncoming(bytes: ByteArray?) {
        if (bytes == null) return
        val complete: String?
        synchronized(lock) {
            responseBuf.append(String(bytes, Charsets.US_ASCII))
            complete = if (responseBuf.contains('>')) responseBuf.toString() else null
        }
        if (complete != null) {
            val d = pending
            pending = null
            d?.complete(complete)
        }
    }

    // ── Session: init handshake + poll loop ───────────────────────────────────

    private fun startSession(g: BluetoothGatt) {
        val s = scope ?: return
        val notify = notifyChar ?: return
        s.launch {
            _connectionState.value = ObdConnectionState.INITIALIZING
            val ready = CompletableDeferred<Boolean>()
            descriptorWritten = ready
            if (!enableNotifications(g, notify)) {
                fail("Could not enable notifications")
                return@launch
            }
            // Proceed even if the CCCD write isn't confirmed — some stacks notify anyway.
            withTimeoutOrNull(DESCRIPTOR_TIMEOUT_MS) { ready.await() }

            for (cmd in INIT_COMMANDS) {
                runCatching { sendCommand(cmd, INIT_TIMEOUT_MS) }
                    .onFailure { Log.w(TAG, "init '$cmd' failed: ${it.message}") }
                delay(120)
            }

            while (isActive) {
                val speed = runCatching { sendCommand(PID_SPEED, CMD_TIMEOUT_MS) }
                    .getOrNull()
                    ?.let { parseSpeedKmh(it) }
                if (speed != null) {
                    _telemetry.value = VehicleTelemetry(speed, System.currentTimeMillis())
                    if (_connectionState.value != ObdConnectionState.CONNECTED) {
                        _connectionState.value = ObdConnectionState.CONNECTED
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun sendCommand(cmd: String, timeoutMs: Long): String {
        val g = gatt ?: throw IllegalStateException("no gatt")
        val ch = writeChar ?: throw IllegalStateException("no write characteristic")
        val deferred = CompletableDeferred<String>()
        synchronized(lock) { responseBuf.setLength(0) }
        pending = deferred
        writeToCharacteristic(g, ch, (cmd + "\r").toByteArray(Charsets.US_ASCII))
        return withTimeout(timeoutMs) { deferred.await() }
    }

    // ── BLE write helpers (version-guarded for the API-33 signature split) ────

    private fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(ch, true)) return false
        val cccd = ch.getDescriptor(CCCD_UUID) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        return true
    }

    private fun writeToCharacteristic(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray) {
        val writeType =
            if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, data, writeType)
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = writeType
            @Suppress("DEPRECATION")
            ch.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    // ── Characteristic selection ──────────────────────────────────────────────

    private data class CharPair(
        val notify: BluetoothGattCharacteristic,
        val write: BluetoothGattCharacteristic
    )

    private fun pickCharacteristics(g: BluetoothGatt): CharPair? {
        // 1. Known ELM327 layouts.
        for (t in KNOWN_ELM327_GATT) {
            val svc = g.getService(t.service) ?: continue
            val n = svc.getCharacteristic(t.notify)
            val w = svc.getCharacteristic(t.write)
            if (n != null && w != null && n.canNotify() && w.canWrite()) return CharPair(n, w)
        }
        // 2. Property-based fallback: a single service exposing both roles.
        for (svc in g.services) {
            val n = svc.characteristics.firstOrNull { it.canNotify() }
            val w = svc.characteristics.firstOrNull { it.canWrite() }
            if (n != null && w != null) return CharPair(n, w)
        }
        return null
    }

    private fun BluetoothGattCharacteristic.canNotify(): Boolean =
        properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

    private fun BluetoothGattCharacteristic.canWrite(): Boolean =
        properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hasBtPermission(): Boolean {
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(appContext, p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun fail(reason: String) {
        Log.w(TAG, "OBD error: $reason")
        _lastError.value = reason
        _connectionState.value = ObdConnectionState.ERROR
    }

    private companion object {
        const val TAG = "ObdBleManager"
        const val PID_SPEED = "010D"
        val INIT_COMMANDS = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0")
        const val SCAN_TIMEOUT_MS = 12_000L
        const val DESCRIPTOR_TIMEOUT_MS = 3_000L
        const val INIT_TIMEOUT_MS = 5_000L
        const val CMD_TIMEOUT_MS = 2_500L
        const val POLL_INTERVAL_MS = 400L
    }
}
