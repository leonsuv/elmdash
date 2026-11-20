package com.elmdash.app.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object EvColors {
    val Background = Color(0xFF070B14)
    val Surface = Color(0xFF0F172A)
    val SurfaceElevated = Color(0xFF162033)
    val Border = Color(0xFF1E293B)
    val Cyan = Color(0xFF06B6D4)
    val CyanLight = Color(0xFF38BDF8)
    val Emerald = Color(0xFF10B981)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFEF4444)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
}

private val EvDarkColorScheme = darkColorScheme(
    primary = EvColors.Cyan,
    onPrimary = Color(0xFF04121A),
    primaryContainer = Color(0xFF0E3A4A),
    onPrimaryContainer = EvColors.CyanLight,
    secondary = EvColors.Emerald,
    onSecondary = Color(0xFF031912),
    secondaryContainer = Color(0xFF0B3B2B),
    onSecondaryContainer = Color(0xFF6EE7B7),
    tertiary = EvColors.Amber,
    background = EvColors.Background,
    onBackground = EvColors.TextPrimary,
    surface = EvColors.Surface,
    onSurface = EvColors.TextPrimary,
    surfaceVariant = EvColors.SurfaceElevated,
    onSurfaceVariant = EvColors.TextSecondary,
    outline = EvColors.Border,
    error = EvColors.Red
)

@Composable
fun CorsaEvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EvDarkColorScheme,
        content = content
    )
}
