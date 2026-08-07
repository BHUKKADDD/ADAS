package com.example.adas

import android.Manifest
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adas.dms.DmsPhase
import com.example.adas.dms.DriverAlertLevel
import com.example.adas.obd.ObdConnectionState
import com.example.adas.theme.AdasTheme
import com.example.adas.theme.HudFontFamily
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

/**
 * Modal bottom sheet for professional ADAS settings.
 * All detection and logic parameters are locked and read-only for safety.
 * Driver can only customize UI theme (Dark/Light).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    viewModel: AdasViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()

    ModalBottomSheet(
        onDismissRequest  = onDismiss,
        sheetState        = sheetState,
        containerColor    = AdasTheme.colors.surface,
        dragHandle        = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AdasTheme.colors.hudDim.copy(alpha = 0.5f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // ── Header ─────────────────────────────────────────────────────────
            Text(
                text = "⚙  ADAS SYSTEM CONFIG",
                fontFamily = HudFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 2.sp,
                color = AdasTheme.colors.cyan
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section: User Customization ────────────────────────────────────
            SettingsSectionHeader("DRIVER PREFERENCES")
            Spacer(modifier = Modifier.height(12.dp))

            SettingsThemeRow(
                isDarkTheme = isDarkTheme,
                onToggle = { viewModel.toggleTheme() }
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = AdasTheme.colors.hudDim.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(16.dp))

            // ── Section: Vehicle Telemetry (OBD-II) ───────────────────────────
            ObdTelemetrySection(viewModel = viewModel)

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = AdasTheme.colors.hudDim.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(16.dp))

            // ── Section: Driver Monitoring (DDAWS / AIS-184) ──────────────────
            DriverMonitoringSection(viewModel = viewModel)

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = AdasTheme.colors.hudDim.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(16.dp))

            // ── Section: Locked ADAS Presets ──────────────────────────────────
            SettingsSectionHeader("SAFETY PRESETS (LOCKED BY ADMIN)")
            Spacer(modifier = Modifier.height(12.dp))

            SettingsInfoRow("Confidence Filter", "45% (Locked)")
            SettingsInfoRow("Distance Estimation", "ON (Locked)")
            SettingsInfoRow("Scanline Sweep Overlay", "ON (Locked)")
            SettingsInfoRow("HUD Overlay Opacity", "85% (Locked)")
            SettingsInfoRow("Forward Collision Alert", "ACTIVE")

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = AdasTheme.colors.hudDim.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(16.dp))

            // ── Section: Technical Info ───────────────────────────────────────
            SettingsSectionHeader("MODEL & HARDWARE INFO")
            Spacer(modifier = Modifier.height(12.dp))

            SettingsInfoRow("Active Neural Model",   "YOLOv8n · IDD (INT8)")
            SettingsInfoRow("Input Frame Resolution", "320 × 320 px")
            // NNAPI was deprecated in Android 15; the real path is XNNPACK CPU,
            // with the GPU delegate available but opt-in until measured.
            SettingsInfoRow("Inference Acceleration", "XNNPACK CPU · 4 threads")
            SettingsInfoRow("Classes Configured",     "12 IDD (autorickshaw, rider, animal, …)")

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Notice: Safety-critical ADAS settings are locked by administrator policies to ensure strict compliance with collision avoidance and hazard detection parameters.",
                fontFamily = HudFontFamily,
                fontSize = 10.sp,
                color = AdasTheme.colors.hudDim.copy(alpha = 0.7f),
                lineHeight = 14.sp
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * OBD-II telemetry controls: adapter status + live speed, a bench-test simulator
 * toggle, and Connect/Disconnect for a real ELM327 BLE adapter (which requests the
 * Bluetooth runtime permissions on first use).
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ObdTelemetrySection(viewModel: AdasViewModel) {
    val colors = AdasTheme.colors
    val obdState by viewModel.obdConnectionState.collectAsState()
    val speedKmh by viewModel.speedKmh.collectAsState()
    val rpm by viewModel.rpm.collectAsState()
    val coolantC by viewModel.coolantC.collectAsState()
    val throttlePct by viewModel.throttlePct.collectAsState()
    val obdError by viewModel.obdError.collectAsState()
    val isSimulated by viewModel.isObdSimulated.collectAsState()

    val btPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val btPermissionState = rememberMultiplePermissionsState(btPermissions)
    var pendingConnect by remember { mutableStateOf(false) }
    // Auto-connect once the user has both asked to connect and granted permissions.
    LaunchedEffect(btPermissionState.allPermissionsGranted, pendingConnect) {
        if (pendingConnect && btPermissionState.allPermissionsGranted) {
            pendingConnect = false
            viewModel.connectObd()
        }
    }

    val isActive = obdState != ObdConnectionState.DISCONNECTED &&
        obdState != ObdConnectionState.ERROR
    val statusText = when (obdState) {
        ObdConnectionState.DISCONNECTED -> "Disconnected"
        ObdConnectionState.SCANNING     -> "Scanning…"
        ObdConnectionState.CONNECTING   -> "Connecting…"
        ObdConnectionState.INITIALIZING -> "Initializing…"
        ObdConnectionState.CONNECTED    -> "Connected"
        ObdConnectionState.ERROR        -> "Error"
    }
    val statusColor = when (obdState) {
        ObdConnectionState.CONNECTED    -> colors.green
        ObdConnectionState.ERROR        -> colors.offline
        ObdConnectionState.DISCONNECTED -> colors.hudDim
        else                            -> colors.amber
    }

    SettingsSectionHeader("VEHICLE TELEMETRY (OBD-II)")
    Spacer(modifier = Modifier.height(12.dp))

    // Status + live speed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Adapter Status",
            fontFamily = HudFontFamily,
            fontSize = 12.sp,
            color = colors.hudText,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = speedKmh?.let { "$statusText  ·  $it km/h" } ?: statusText,
            fontFamily = HudFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = statusColor
        )
    }
    if (obdState == ObdConnectionState.ERROR) {
        obdError?.let {
            Text(
                text = it,
                fontFamily = HudFontFamily,
                fontSize = 10.sp,
                color = colors.offline.copy(alpha = 0.85f)
            )
        }
    }

    // Secondary PIDs (RPM / coolant / throttle) — shown once each has been read.
    val secondary = listOfNotNull(
        rpm?.let { "$it rpm" },
        coolantC?.let { "$it °C" },
        throttlePct?.let { "$it% throttle" }
    )
    if (secondary.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Engine",
                fontFamily = HudFontFamily,
                fontSize = 12.sp,
                color = colors.hudText,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = secondary.joinToString("  ·  "),
                fontFamily = HudFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = colors.cyan
            )
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // Simulator toggle (no hardware needed)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Simulated Telemetry",
                fontFamily = HudFontFamily,
                fontSize = 13.sp,
                color = colors.textPrimary
            )
            Text(
                text = "Bench test with a synthetic speed curve (no car)",
                fontFamily = HudFontFamily,
                fontSize = 10.sp,
                color = colors.hudDim
            )
        }
        Switch(
            checked = isSimulated,
            onCheckedChange = { viewModel.setObdSimulated(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor   = colors.amber,
                checkedTrackColor   = colors.amber.copy(alpha = 0.3f),
                uncheckedThumbColor = colors.hudDim,
                uncheckedTrackColor = colors.hudDim.copy(alpha = 0.2f)
            )
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Connect / Disconnect a real ELM327 BLE adapter (disabled while simulating)
    val buttonEnabled = !isSimulated
    val buttonLabel = if (isActive) "DISCONNECT ADAPTER" else "CONNECT BLE ADAPTER"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (buttonEnabled) colors.cyan.copy(alpha = 0.15f)
                else colors.hudDim.copy(alpha = 0.08f)
            )
            .then(
                if (buttonEnabled) {
                    Modifier.clickable(role = Role.Button) {
                        when {
                            isActive -> viewModel.disconnectObd()
                            btPermissionState.allPermissionsGranted -> viewModel.connectObd()
                            else -> {
                                pendingConnect = true
                                btPermissionState.launchMultiplePermissionRequest()
                            }
                        }
                    }
                } else Modifier
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = buttonLabel,
            fontFamily = HudFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            color = if (buttonEnabled) colors.cyan else colors.hudDim
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        fontFamily = HudFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 2.sp,
        color = AdasTheme.colors.hudDim
    )
}

@Composable
private fun SettingsThemeRow(
    isDarkTheme: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Night Mode Theme",
                fontFamily = HudFontFamily,
                fontSize = 13.sp,
                color = AdasTheme.colors.textPrimary
            )
            Text(
                text = if (isDarkTheme) "High-contrast tactical night theme" else "Daylight high-contrast theme",
                fontFamily = HudFontFamily,
                fontSize = 10.sp,
                color = AdasTheme.colors.hudDim
            )
        }
        Switch(
            checked = isDarkTheme,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor   = AdasTheme.colors.cyan,
                checkedTrackColor   = AdasTheme.colors.cyan.copy(alpha = 0.3f),
                uncheckedThumbColor = AdasTheme.colors.hudDim,
                uncheckedTrackColor = AdasTheme.colors.hudDim.copy(alpha = 0.2f)
            )
        )
    }
}

/**
 * Driver monitoring (DDAWS / AIS-184) controls: live driver state, the calibrated
 * baseline, PERCLOS, and the bench simulator toggle.
 *
 * The real front-camera source is not wired yet (it needs the MediaPipe dependency
 * and the face_landmarker.task asset — see the note at the foot of
 * dms/FaceLandmarkSource.kt), so the simulator is the only source today. The
 * analyzer behind it is the production one.
 */
@Composable
private fun DriverMonitoringSection(viewModel: AdasViewModel) {
    val colors = AdasTheme.colors
    val dms by viewModel.dmsState.collectAsState()
    val isSimulated by viewModel.isDmsSimulated.collectAsState()

    val phaseText = when (dms.phase) {
        DmsPhase.IDLE -> "Inactive"
        DmsPhase.CALIBRATING -> "Calibrating ${(dms.calibrationProgress * 100).toInt()}%"
        DmsPhase.MONITORING -> "Monitoring"
        DmsPhase.NO_FACE -> "Driver not visible"
    }
    val phaseColor = when {
        dms.alert == DriverAlertLevel.DANGER -> colors.offline
        dms.alert == DriverAlertLevel.CAUTION -> colors.amber
        dms.phase == DmsPhase.MONITORING -> colors.green
        dms.phase == DmsPhase.IDLE -> colors.hudDim
        else -> colors.amber
    }

    SettingsSectionHeader("DRIVER MONITORING (DDAWS · AIS-184)")
    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Driver State",
            fontFamily = HudFontFamily,
            fontSize = 12.sp,
            color = colors.hudText,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = phaseText,
            fontFamily = HudFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = phaseColor
        )
    }

    // Live metrics, shown only once there is a calibrated baseline to compare against.
    if (dms.phase == DmsPhase.MONITORING) {
        val metrics = listOfNotNull(
            "PERCLOS ${(dms.perclos * 100).toInt()}%",
            dms.ear?.let { "EAR ${"%.2f".format(it)}" },
            if (dms.yawns > 0) "${dms.yawns} yawns" else null
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Fatigue",
                fontFamily = HudFontFamily,
                fontSize = 12.sp,
                color = colors.hudText,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = metrics.joinToString("  ·  "),
                fontFamily = HudFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = colors.cyan
            )
        }
    }

    if (dms.message.isNotEmpty()) {
        Text(
            text = dms.message,
            fontFamily = HudFontFamily,
            fontSize = 10.sp,
            color = phaseColor.copy(alpha = 0.85f)
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Simulated Driver",
                fontFamily = HudFontFamily,
                fontSize = 13.sp,
                color = colors.textPrimary
            )
            Text(
                text = "Bench test a scripted drowsy driver (no camera)",
                fontFamily = HudFontFamily,
                fontSize = 10.sp,
                color = colors.hudDim
            )
        }
        Switch(
            checked = isSimulated,
            onCheckedChange = { viewModel.setDmsSimulated(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.amber,
                checkedTrackColor = colors.amber.copy(alpha = 0.3f),
                uncheckedThumbColor = colors.hudDim,
                uncheckedTrackColor = colors.hudDim.copy(alpha = 0.2f)
            )
        )
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontFamily = HudFontFamily,
            fontSize = 12.sp,
            color = AdasTheme.colors.hudText,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontFamily = HudFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = AdasTheme.colors.cyan
        )
    }
}
