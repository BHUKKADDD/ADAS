package com.example.adas

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adas.theme.AdasTheme
import com.example.adas.theme.HudFontFamily
import kotlin.math.cos
import kotlin.math.sin

/**
 * Main Menu / Home Dashboard — the first screen users see.
 * Fully supports Light/Dark theme configuration dynamically.
 */
@Composable
fun HomeScreen(
    onStartADAS: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: AdasViewModel
) {
    val colors = AdasTheme.colors
    val infiniteTransition = rememberInfiniteTransition(label = "home")

    // Rotating radar sweep
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 360f,
        animationSpec = infiniteRepeatable(
            animation  = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar"
    )

    // Pulsing glow on the START button
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue  = 0.65f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    // Scanline sweep
    val scanlineY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {

        // ── Animated radar + grid background ──────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx   = size.width / 2f
            val cy   = size.height * 0.30f
            val maxR = size.width * 0.48f

            // Grid lines (horizontal)
            for (i in 1..8) {
                drawLine(
                    color       = colors.cyan.copy(alpha = 0.05f),
                    start       = Offset(0f, size.height * i / 8f),
                    end         = Offset(size.width, size.height * i / 8f),
                    strokeWidth = 1f
                )
            }
            // Grid lines (vertical)
            for (i in 1..5) {
                drawLine(
                    color       = colors.cyan.copy(alpha = 0.05f),
                    start       = Offset(size.width * i / 5f, 0f),
                    end         = Offset(size.width * i / 5f, size.height),
                    strokeWidth = 1f
                )
            }

            // Concentric radar rings
            listOf(0.25f, 0.5f, 0.75f, 1.0f).forEach { f ->
                drawCircle(
                    color  = colors.cyan.copy(alpha = 0.08f),
                    radius = maxR * f,
                    center = Offset(cx, cy),
                    style  = Stroke(width = 1f)
                )
            }

            // Cross-hair lines
            drawLine(colors.cyan.copy(alpha = 0.1f), Offset(cx, cy - maxR), Offset(cx, cy + maxR))
            drawLine(colors.cyan.copy(alpha = 0.1f), Offset(cx - maxR, cy), Offset(cx + maxR, cy))

            // Sweep arc fill
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Transparent,
                        colors.cyan.copy(alpha = 0.08f),
                        colors.cyan.copy(alpha = 0.22f),
                    ),
                    center = Offset(cx, cy)
                ),
                startAngle = sweepAngle - 70f,
                sweepAngle = 70f,
                useCenter  = true,
                topLeft    = Offset(cx - maxR, cy - maxR),
                size       = androidx.compose.ui.geometry.Size(maxR * 2, maxR * 2)
            )

            // Sweep leading edge
            val rad = Math.toRadians(sweepAngle.toDouble())
            drawLine(
                color       = colors.cyan.copy(alpha = 0.7f),
                start       = Offset(cx, cy),
                end         = Offset(cx + (maxR * cos(rad)).toFloat(), cy + (maxR * sin(rad)).toFloat()),
                strokeWidth = 1.5f,
                cap         = StrokeCap.Round
            )

            // Radar center dot
            drawCircle(colors.cyan.copy(alpha = 0.9f), 4f, Offset(cx, cy))

            // Scanline sweep
            val sy = scanlineY * size.height
            drawLine(
                color       = colors.cyan.copy(alpha = 0.08f),
                start       = Offset(0f, sy),
                end         = Offset(size.width, sy),
                strokeWidth = 1.5f
            )
        }

        // ── Content ────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(modifier = Modifier.height(32.dp))

            // ── Logo + version header ──────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Live dot
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(colors.green)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text     = "SYSTEM READY",
                    fontFamily  = HudFontFamily,
                    fontSize    = 10.sp,
                    letterSpacing = 2.sp,
                    color    = colors.green
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text      = "ADAS",
                fontFamily   = HudFontFamily,
                fontWeight   = FontWeight.Bold,
                fontSize     = 56.sp,
                letterSpacing = 12.sp,
                color     = colors.cyan
            )

            Text(
                text      = "ADVANCED DRIVER ASSISTANCE",
                fontFamily   = HudFontFamily,
                fontWeight   = FontWeight.Medium,
                fontSize     = 10.sp,
                letterSpacing = 2.5.sp,
                color     = colors.hudText,
                textAlign = TextAlign.Center
            )

            Text(
                text      = "YOLOv8n  ·  IDD-trained  ·  India Roads  ·  v1.0",
                fontFamily   = HudFontFamily,
                fontSize     = 9.sp,
                letterSpacing = 1.sp,
                color     = colors.hudDim,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(52.dp))

            // ── PRIMARY: Start ADAS button ─────────────────────────────────────
            Box(contentAlignment = Alignment.Center) {
                // Outer glow ring (pulsing)
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .clip(CircleShape)
                        .background(colors.cyan.copy(alpha = glowAlpha * 0.18f))
                )
                // Mid ring
                Box(
                    modifier = Modifier
                        .size(152.dp)
                        .clip(CircleShape)
                        .background(colors.cyan.copy(alpha = 0.08f))
                )
                // Button core
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    colors.cyan.copy(alpha = 0.35f),
                                    colors.cyan.copy(alpha = 0.15f)
                                )
                            )
                        )
                        .clickable(role = Role.Button, onClick = onStartADAS),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "▶", fontSize = 32.sp, color = colors.cyan)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text      = "START",
                            fontFamily   = HudFontFamily,
                            fontWeight   = FontWeight.Bold,
                            fontSize     = 13.sp,
                            letterSpacing = 2.sp,
                            color     = colors.cyan
                        )
                        Text(
                            text      = "ADAS",
                            fontFamily   = HudFontFamily,
                            fontWeight   = FontWeight.Bold,
                            fontSize     = 11.sp,
                            letterSpacing = 2.sp,
                            color     = colors.cyan.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // ── Quick-stat cards row ───────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatCard(
                    icon  = "🎯",
                    label = "MODEL",
                    value = "Fixed ADAS",
                    sub   = "YOLOv8n",
                    color = colors.cyan,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon  = "📡",
                    label = "STATUS",
                    value = "READY",
                    sub   = "Locked",
                    color = colors.green,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon  = "⚡",
                    label = "TARGET",
                    value = "20fps",
                    sub   = "TFLite",
                    color = colors.amber,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── Second row of cards ────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatCard(
                    icon  = "🇮🇳",
                    label = "REGION",
                    value = "India",
                    sub   = "Road data",
                    color = colors.amber,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon  = "🔒",
                    label = "PRIVACY",
                    value = "100%",
                    sub   = "On-device",
                    color = colors.green,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon  = "📋",
                    label = "EVENTS",
                    value = "6",
                    sub   = "Logged",
                    color = colors.purple,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── Secondary nav row ──────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.glass)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                NavButton(icon = "📋", label = "EVENT LOG",  onClick = onOpenLog)
                NavDivider()
                NavButton(icon = "⚙",  label = "SETTINGS",  onClick = onOpenSettings)
                NavDivider()
                NavButton(icon = "ℹ",  label = "ABOUT",     onClick = { /* Phase E */ })
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Footer ─────────────────────────────────────────────────────────
            Text(
                text      = "🇮🇳  Making India's roads safer, one edge case at a time.",
                fontFamily   = HudFontFamily,
                fontSize     = 9.sp,
                color     = colors.hudDim.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
            // IDD attribution — required by the India Driving Dataset research license
            // (non-commercial). See DATASET_LICENSE.md.
            Text(
                text      = "Detection model trained on the India Driving Dataset (IDD)\nidd.insaan.iiit.ac.in · non-commercial research use",
                fontFamily   = HudFontFamily,
                fontSize     = 8.sp,
                color     = colors.hudDim.copy(alpha = 0.4f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ── Sub-composables ────────────────────────────────────────────────────────────

@Composable
private fun StatCard(
    icon: String,
    label: String,
    value: String,
    sub: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val colors = AdasTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = icon, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text      = label,
            fontFamily   = HudFontFamily,
            fontSize     = 8.sp,
            letterSpacing = 1.sp,
            color     = colors.hudDim
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text      = value,
            fontFamily   = HudFontFamily,
            fontWeight   = FontWeight.Bold,
            fontSize     = 11.sp, // Reduced font size to avoid wrapping in light theme text
            color     = color,
            textAlign = TextAlign.Center
        )
        Text(
            text      = sub,
            fontFamily   = HudFontFamily,
            fontSize     = 8.sp,
            color     = colors.hudDim.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun NavButton(icon: String, label: String, onClick: () -> Unit) {
    val colors = AdasTheme.colors
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = icon, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text      = label,
            fontFamily   = HudFontFamily,
            fontSize     = 8.sp,
            letterSpacing = 0.8.sp,
            color     = colors.hudDim
        )
    }
}

@Composable
private fun NavDivider() {
    val colors = AdasTheme.colors
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 32.dp)
            .background(colors.hudDim.copy(alpha = 0.2f))
    )
}
