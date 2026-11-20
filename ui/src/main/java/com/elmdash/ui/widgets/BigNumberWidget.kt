package com.elmdash.ui.widgets

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.elmdash.core.telemetry.TelemetrySnapshot
import com.elmdash.ui.engine.DashboardTheme
import com.elmdash.ui.engine.DashboardWidget
import java.util.Locale

class BigNumberWidget(
    override val id: String,
    private val pid: String,
    private val label: String,
    private val unit: String,
    private val decimals: Int = 1,
    private val warnThreshold: Double? = null,
    private val critThreshold: Double? = null,
    private val showSign: Boolean = false
) : DashboardWidget {

    override val requiredPids: Set<String> = setOf(pid)

    private val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentStripPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = WidgetCardDrawer.labelPaint().apply { textAlign = Paint.Align.LEFT }
    private val valuePaint = WidgetCardDrawer.valuePaint().apply { textAlign = Paint.Align.CENTER }
    private val unitPaint = WidgetCardDrawer.labelPaint().apply { textAlign = Paint.Align.RIGHT }

    override fun draw(canvas: Canvas, bounds: RectF, state: TelemetrySnapshot, theme: DashboardTheme) {
        WidgetCardDrawer.drawCardBackground(canvas, bounds, theme, cardFillPaint, cardBorderPaint)

        val padX = (bounds.width() * 0.075f).coerceIn(10f, 20f)
        val padY = (bounds.height() * 0.13f).coerceIn(8f, 16f)

        val rawValue = state.getValue(pid)
        val accentColor = when {
            rawValue == null -> theme.cardBorder
            critThreshold != null && rawValue >= critThreshold -> theme.criticalRed
            warnThreshold != null && rawValue >= warnThreshold -> theme.warningAmber
            showSign && rawValue < -0.5 -> theme.regenGreen
            else -> theme.accent
        }

        // Subtle left accent bar for visual hierarchy
        accentStripPaint.color = Color.argb(
            if (state.isStale) 90 else 210,
            Color.red(accentColor),
            Color.green(accentColor),
            Color.blue(accentColor)
        )
        canvas.drawRoundRect(
            bounds.left + 4f,
            bounds.top + padY,
            bounds.left + 7.5f,
            bounds.bottom - padY,
            2f, 2f,
            accentStripPaint
        )

        val labelSize = (bounds.height() * 0.165f).coerceIn(11f, 18f)
        labelPaint.textSize = labelSize
        labelPaint.color = theme.textSecondary
        canvas.drawText(label.uppercase(Locale.US), bounds.left + padX + 4f, bounds.top + padY + labelSize * 0.78f, labelPaint)

        unitPaint.textSize = labelSize
        unitPaint.color = theme.textSecondary
        canvas.drawText(unit, bounds.right - padX, bounds.top + padY + labelSize * 0.78f, unitPaint)

        val formatted = if (rawValue == null) {
            "--"
        } else {
            val pattern = if (showSign) "%+.${decimals}f" else "%.${decimals}f"
            String.format(Locale.US, pattern, rawValue)
        }

        val valueColor = when {
            rawValue == null -> theme.textSecondary
            critThreshold != null && rawValue >= critThreshold -> theme.criticalRed
            warnThreshold != null && rawValue >= warnThreshold -> theme.warningAmber
            showSign && rawValue < -0.5 -> theme.regenGreen
            else -> theme.textPrimary
        }

        val targetSize = (bounds.height() * 0.45f).coerceIn(18f, 62f)
        valuePaint.textSize = targetSize
        val availableWidth = (bounds.width() - padX * 2f - 8f).coerceAtLeast(24f)
        val measured = valuePaint.measureText(formatted)
        if (measured > availableWidth) {
            valuePaint.textSize = targetSize * (availableWidth / measured)
        }
        valuePaint.color = valueColor
        WidgetCardDrawer.applyStaleAlpha(valuePaint, state.isStale, theme)

        val textY = bounds.centerY() + valuePaint.textSize * 0.42f
        canvas.drawText(formatted, bounds.centerX() + 2f, textY, valuePaint)
    }
}
