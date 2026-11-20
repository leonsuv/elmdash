package com.elmdash.ui.widgets

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.elmdash.core.telemetry.TelemetrySnapshot
import com.elmdash.ui.engine.DashboardTheme
import com.elmdash.ui.engine.DashboardWidget
import java.util.Locale

/**
 * Modern EV battery SoC bar with segmented charge cells + thermal pill badge.
 */
class BatteryBarWidget(
    override val id: String,
    private val socPid: String = "soc",
    private val tempPid: String = "battery_temp",
    private val label: String = "Akku"
) : DashboardWidget {

    override val requiredPids: Set<String> = setOf(socPid, tempPid)

    private val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = WidgetCardDrawer.labelPaint().apply { textAlign = Paint.Align.LEFT }
    private val socPaint = WidgetCardDrawer.valuePaint().apply { textAlign = Paint.Align.LEFT }
    private val tempPillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tempPaint = WidgetCardDrawer.valuePaint().apply { textAlign = Paint.Align.CENTER }
    private val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f }
    private val pillRect = RectF()

    override fun draw(canvas: Canvas, bounds: RectF, state: TelemetrySnapshot, theme: DashboardTheme) {
        WidgetCardDrawer.drawCardBackground(canvas, bounds, theme, cardFillPaint, cardBorderPaint)

        val padX = (bounds.width() * 0.055f).coerceIn(10f, 20f)
        val padY = (bounds.height() * 0.13f).coerceIn(8f, 16f)

        val soc = state.getValue(socPid)
        val temp = state.getValue(tempPid)

        val headerSize = (bounds.height() * 0.16f).coerceIn(11f, 17f)
        labelPaint.textSize = headerSize
        labelPaint.color = theme.textSecondary
        canvas.drawText(label.uppercase(Locale.US), bounds.left + padX, bounds.top + padY + headerSize * 0.8f, labelPaint)

        // Thermal color-coded pill badge
        val tempColor = when {
            temp == null -> theme.textSecondary
            temp < 10.0 -> theme.coldBlue
            temp <= 35.0 -> theme.regenGreen
            temp <= 42.0 -> theme.warningAmber
            else -> theme.criticalRed
        }
        val tempText = temp?.let { String.format(Locale.US, "%.0f °C", it) } ?: "-- °C"
        tempPaint.textSize = (bounds.height() * 0.165f).coerceIn(11f, 18f)
        val pillW = tempPaint.measureText(tempText) + 18f
        val pillH = tempPaint.textSize * 1.65f
        pillRect.set(bounds.right - padX - pillW, bounds.top + padY - 2f, bounds.right - padX, bounds.top + padY - 2f + pillH)

        tempPillPaint.color = Color.argb(
            if (theme.isDark) 44 else 28,
            Color.red(tempColor),
            Color.green(tempColor),
            Color.blue(tempColor)
        )
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, tempPillPaint)

        tempPaint.color = tempColor
        WidgetCardDrawer.applyStaleAlpha(tempPaint, state.isStale, theme)
        canvas.drawText(tempText, pillRect.centerX(), pillRect.centerY() + tempPaint.textSize * 0.35f, tempPaint)

        // SoC big readout
        socPaint.textSize = (bounds.height() * 0.34f).coerceIn(18f, 44f)
        socPaint.color = theme.textPrimary
        WidgetCardDrawer.applyStaleAlpha(socPaint, state.isStale, theme)
        val socText = soc?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--%"
        canvas.drawText(socText, bounds.left + padX, bounds.centerY() + socPaint.textSize * 0.22f, socPaint)

        // Segmented SoC bar
        val barHeight = (bounds.height() * 0.21f).coerceIn(12f, 24f)
        val barBottom = bounds.bottom - padY
        val barTop = barBottom - barHeight
        val barLeft = bounds.left + padX
        val barRight = bounds.right - padX
        val barRect = RectF(barLeft, barTop, barRight, barBottom)

        barTrackPaint.color = theme.trackColor
        canvas.drawRoundRect(barRect, barHeight / 2f, barHeight / 2f, barTrackPaint)

        if (soc != null) {
            val frac = (soc / 100.0).toFloat().coerceIn(0f, 1f)
            val fillColor = when {
                soc < 15.0 -> theme.criticalRed
                soc < 25.0 -> theme.warningAmber
                else -> theme.regenGreen
            }
            barFillPaint.color = fillColor
            WidgetCardDrawer.applyStaleAlpha(barFillPaint, state.isStale, theme)
            val fillRight = barLeft + (barRight - barLeft) * frac
            if (fillRight > barLeft + 4f) {
                canvas.drawRoundRect(barLeft, barTop, fillRight, barBottom, barHeight / 2f, barHeight / 2f, barFillPaint)
            }
        }

        tickPaint.color = theme.cardBackground
        for (q in 1..9) {
            val x = barLeft + (barRight - barLeft) * (q * 0.1f)
            canvas.drawLine(x, barTop + 2f, x, barBottom - 2f, tickPaint)
        }
    }
}
