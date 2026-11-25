package com.elmdash.ui.widgets

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.elmdash.core.telemetry.TelemetrySnapshot
import com.elmdash.ui.engine.DashboardTheme
import com.elmdash.ui.engine.DashboardWidget
import java.util.Locale

/**
 * Renders either a smooth gradient-filled rolling time-series sparkline
 * or a high-density 108s cell-voltage bar spectrum with min/max highlighting.
 */
class SparklineWidget(
    override val id: String,
    private val pid: String,
    private val label: String,
    private val unit: String,
    private val minHint: Double = 0.0,
    private val maxHint: Double = 100.0,
    private val arrayMode: Boolean = false,
    private val autoScale: Boolean = false
) : DashboardWidget {

    override val requiredPids: Set<String> = setOf(pid)

    private val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = WidgetCardDrawer.labelPaint().apply { textAlign = Paint.Align.LEFT }
    private val metaPaint = WidgetCardDrawer.valuePaint().apply { textAlign = Paint.Align.RIGHT }
    private val axisPaint = WidgetCardDrawer.valuePaint().apply { textAlign = Paint.Align.LEFT }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val linePath = Path()
    private val fillPath = Path()

    override fun draw(canvas: Canvas, bounds: RectF, state: TelemetrySnapshot, theme: DashboardTheme) {
        WidgetCardDrawer.drawCardBackground(canvas, bounds, theme, cardFillPaint, cardBorderPaint)

        val padX = (bounds.width() * 0.032f).coerceIn(10f, 18f)
        val padY = (bounds.height() * 0.11f).coerceIn(8f, 14f)
        val headerSize = (bounds.height() * 0.14f).coerceIn(11f, 16f)

        labelPaint.textSize = headerSize
        labelPaint.color = theme.textSecondary
        canvas.drawText(label.uppercase(Locale.US), bounds.left + padX, bounds.top + padY + headerSize * 0.82f, labelPaint)

        val isArray = arrayMode || state.getArray(pid) != null || pid in ARRAY_PIDS
        val samples = if (isArray) {
            state.getArray(pid).orEmpty()
        } else {
            state.getHistory(pid)
        }

        metaPaint.textSize = headerSize * 1.05f
        metaPaint.color = theme.textPrimary
        WidgetCardDrawer.applyStaleAlpha(metaPaint, state.isStale, theme)
        val metaText = if (samples.isEmpty()) {
            "--"
        } else if (isArray) {
            when (pid) {
                "cell_soh" -> String.format(Locale.US, "%d cells • Min %.1f%%", samples.size, samples.min())
                "temp_probes" -> String.format(Locale.US, "%d Fühler • %.0f–%.0f °C", samples.size, samples.min(), samples.max())
                else -> {
                    val spreadMv = (samples.max() - samples.min()) * 1000.0
                    String.format(Locale.US, "%d cells • Δ%.0f mV", samples.size, spreadMv)
                }
            }
        } else {
            String.format(Locale.US, "%+.1f %s", samples.last(), unit)
        }
        canvas.drawText(metaText, bounds.right - padX, bounds.top + padY + headerSize * 0.82f, metaPaint)

        val plotTop = bounds.top + padY + headerSize * 1.6f
        val plotBottom = bounds.bottom - padY
        val plotLeft = bounds.left + padX
        val plotRight = bounds.right - padX
        val plotW = (plotRight - plotLeft).coerceAtLeast(10f)
        val plotH = (plotBottom - plotTop).coerceAtLeast(10f)

        // Subtle horizontal reference gridlines
        gridPaint.color = theme.gridLineColor
        for (i in 1..2) {
            val gy = plotTop + plotH * (i / 3f)
            canvas.drawLine(plotLeft, gy, plotRight, gy, gridPaint)
        }

        if (samples.isEmpty()) return

        val dataMin = samples.min()
        val dataMax = samples.max()
        val autoPad = ((dataMax - dataMin) * 0.12).coerceAtLeast(if (isArray) 0.015 else 1.0)
        val yMin = when {
            isArray || autoScale -> dataMin - autoPad
            else -> minOf(minHint, dataMin)
        }
        val yMax = when {
            isArray || autoScale -> dataMax + autoPad
            else -> maxOf(maxHint, dataMax)
        }
        val yRange = (yMax - yMin).coerceAtLeast(1e-3)

        if (isArray) {
            val count = samples.size
            val gap = if (count > 48) 1.5f else (plotW * 0.01f).coerceIn(2f, 5f)
            val barW = ((plotW - gap * (count - 1)) / count).coerceAtLeast(1.5f)
            val radius = if (barW >= 6f) 3f else 1f
            var minIdx = 0
            var maxIdx = 0
            for (i in samples.indices) {
                if (samples[i] < samples[minIdx]) minIdx = i
                if (samples[i] > samples[maxIdx]) maxIdx = i
            }

            for (i in samples.indices) {
                val frac = ((samples[i] - yMin) / yRange).toFloat().coerceIn(0.08f, 1f)
                val x = plotLeft + i * (barW + gap)
                val top = plotBottom - frac * plotH
                barPaint.color = when (i) {
                    minIdx -> theme.warningAmber
                    maxIdx -> theme.regenGreen
                    else -> theme.accent
                }
                WidgetCardDrawer.applyStaleAlpha(barPaint, state.isStale, theme)
                canvas.drawRoundRect(x, top, x + barW, plotBottom, radius, radius, barPaint)
            }
            return
        }

        if (yMin < 0.0 && yMax > 0.0) {
            val zeroY = plotBottom - ((0.0 - yMin) / yRange).toFloat() * plotH
            zeroLinePaint.color = theme.cardBorder
            canvas.drawLine(plotLeft, zeroY, plotRight, zeroY, zeroLinePaint)
        }

        // Min/Max subtle axis labels on left
        axisPaint.textSize = (headerSize * 0.78f).coerceIn(9f, 12f)
        axisPaint.color = theme.textSecondary
        canvas.drawText(String.format(Locale.US, "%.0f", yMax), plotLeft + 2f, plotTop + axisPaint.textSize, axisPaint)
        canvas.drawText(String.format(Locale.US, "%.0f", yMin), plotLeft + 2f, plotBottom - 2f, axisPaint)

        if (samples.size < 2) return

        linePath.reset()
        fillPath.reset()

        val stepX = plotW / (samples.size - 1).coerceAtLeast(1)
        var lastX = plotLeft
        var lastY = plotBottom
        for (i in samples.indices) {
            val x = plotLeft + i * stepX
            val frac = ((samples[i] - yMin) / yRange).toFloat().coerceIn(0f, 1f)
            val y = plotBottom - frac * plotH
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, plotBottom)
                fillPath.lineTo(x, y)
            } else {
                val midX = (lastX + x) * 0.5f
                linePath.cubicTo(midX, lastY, midX, y, x, y)
                fillPath.cubicTo(midX, lastY, midX, y, x, y)
            }
            lastX = x
            lastY = y
        }
        fillPath.lineTo(plotRight, plotBottom)
        fillPath.close()

        val baseColor = if (samples.last() < -0.5) theme.regenGreen else theme.accent
        fillPathPaint.shader = LinearGradient(
            0f, plotTop, 0f, plotBottom,
            Color.argb(if (state.isStale) 35 else 95, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
            Color.argb(0, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPathPaint)

        linePaint.color = baseColor
        WidgetCardDrawer.applyStaleAlpha(linePaint, state.isStale, theme)
        canvas.drawPath(linePath, linePaint)

        // Glowing live endpoint dot
        dotPaint.color = baseColor
        WidgetCardDrawer.applyStaleAlpha(dotPaint, state.isStale, theme)
        canvas.drawCircle(lastX, lastY, 4.5f, dotPaint)
    }

    companion object {
        private val ARRAY_PIDS = setOf("cell_voltages", "cell_soh", "temp_probes")
    }
}
