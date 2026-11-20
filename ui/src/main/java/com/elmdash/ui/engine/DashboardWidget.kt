package com.elmdash.ui.engine

import android.graphics.Canvas
import android.graphics.RectF
import com.elmdash.core.telemetry.TelemetrySnapshot

/**
 * Modular widget contract rendered directly onto the hardware-accelerated Android Auto Canvas.
 */
interface DashboardWidget {
    val id: String
    val requiredPids: Set<String>
    fun draw(canvas: Canvas, bounds: RectF, state: TelemetrySnapshot, theme: DashboardTheme)
}
