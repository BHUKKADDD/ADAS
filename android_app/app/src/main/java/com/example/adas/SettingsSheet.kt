package com.example.adas

import androidx.compose.foundation.background
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adas.theme.AdasTheme
import com.example.adas.theme.HudFontFamily

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

            SettingsInfoRow("Active Neural Model",   "YOLOv8n · IDD (float32)")
            SettingsInfoRow("Input Frame Resolution", "320 × 320 px")
            SettingsInfoRow("Inference Acceleration", "NNAPI / GPU Auto")
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
