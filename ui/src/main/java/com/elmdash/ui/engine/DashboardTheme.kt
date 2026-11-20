package com.elmdash.ui.engine

import android.graphics.Color

/**
 * Modern EV-cockpit Day/Night color tokens derived from CarContext.isDarkMode.
 */
data class DashboardTheme(
    val isDark: Boolean,
    val background: Int,
    val cardBackground: Int,
    val cardSurfaceElevated: Int,
    val cardBorder: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val accentGlow: Int,
    val regenGreen: Int,
    val powerOrange: Int,
    val warningAmber: Int,
    val criticalRed: Int,
    val coldBlue: Int,
    val trackColor: Int,
    val gridLineColor: Int,
    val staleAlpha: Int
) {
    companion object {
        fun forDarkMode(isDark: Boolean): DashboardTheme = if (isDark) dark() else light()

        fun dark(): DashboardTheme = DashboardTheme(
            isDark = true,
            background = Color.parseColor("#070B14"),
            cardBackground = Color.parseColor("#0F172A"),
            cardSurfaceElevated = Color.parseColor("#162033"),
            cardBorder = Color.parseColor("#1E293B"),
            textPrimary = Color.parseColor("#F8FAFC"),
            textSecondary = Color.parseColor("#94A3B8"),
            accent = Color.parseColor("#06B6D4"),
            accentGlow = Color.parseColor("#38BDF8"),
            regenGreen = Color.parseColor("#10B981"),
            powerOrange = Color.parseColor("#F97316"),
            warningAmber = Color.parseColor("#F59E0B"),
            criticalRed = Color.parseColor("#EF4444"),
            coldBlue = Color.parseColor("#60A5FA"),
            trackColor = Color.parseColor("#1E293B"),
            gridLineColor = Color.parseColor("#1A2438"),
            staleAlpha = 145
        )

        fun light(): DashboardTheme = DashboardTheme(
            isDark = false,
            background = Color.parseColor("#EEF2F6"),
            cardBackground = Color.parseColor("#FFFFFF"),
            cardSurfaceElevated = Color.parseColor("#F8FAFC"),
            cardBorder = Color.parseColor("#D5DEE8"),
            textPrimary = Color.parseColor("#0B1324"),
            textSecondary = Color.parseColor("#526277"),
            accent = Color.parseColor("#0284C7"),
            accentGlow = Color.parseColor("#0EA5E9"),
            regenGreen = Color.parseColor("#059669"),
            powerOrange = Color.parseColor("#EA580C"),
            warningAmber = Color.parseColor("#D97706"),
            criticalRed = Color.parseColor("#DC2626"),
            coldBlue = Color.parseColor("#2563EB"),
            trackColor = Color.parseColor("#E2E8F0"),
            gridLineColor = Color.parseColor("#E8EEF5"),
            staleAlpha = 145
        )
    }
}
