package com.elmdash.ui.widgets

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.elmdash.core.telemetry.TelemetrySnapshot
import com.elmdash.ui.engine.DashboardTheme
import com.elmdash.ui.engine.DashboardWidget
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Modern EV cockpit powermeter / tachometer:
 * - 250° radial scale with major & minor tick marks + numeric scale labels
 * - Color-coded active power arc (emerald Regen < 0, cyan ECO, orange-red Boost)
 * - Crisp radial needle with glowing tip + mode pill badge (REKU / ECO / POWER)
 */
class ArcGaugeWidget(
    override val id: String,
    private val pid: String,
    private val label: String,
    private val unit: String,
    private val min: Double = -45.0,
    private val max: Double = 105.0,
    private val decimals: Int = 1,
    private val autoScale: Boolean = false
) : DashboardWidget {

    private var observedMin: Double = Double.POSITIVE_INFINITY
    private var observedMax: Double = Double.NEGATIVE_INFINITY

    override val requiredPids: Set<String> = setOf(pid)

    private val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val scaleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillTextPaint = WidgetCardDrawer.labelPaint().apply { textAlign = Paint.Align.CENTER }
    private val labelPaint = WidgetCardDrawer.labelPaint().apply { textAlign = Paint.Align.LEFT }
    private val valuePaint = WidgetCardDrawer.valuePaint().apply { textAlign = Paint.Align.CENTER }
    private val unitPaint = WidgetCardDrawer.labelPaint().apply { textAlign = Paint.Align.CENTER }
    private val arcRect = RectF()
    private val innerRect = RectF()
    private val pillRect = RectF()

    override fun draw(canvas: Canvas, bounds: RectF, state: TelemetrySnapshot, theme: DashboardTheme) {
        WidgetCardDrawer.drawCardBackground(canvas, bounds, theme, cardFillPaint, cardBorderPaint)

        val pad = (minOf(bounds.width(), bounds.height()) * 0.055f).coerceIn(8f, 16f)
        val headerSize = (bounds.height() * 0.065f).coerceIn(11f, 16f)
        labelPaint.textSize = headerSize
        labelPaint.color = theme.textSecondary
        canvas.drawText(label.uppercase(Locale.US), bounds.left + pad, bounds.top + pad + headerSize * 0.85f, labelPaint)

        val side = minOf(bounds.width() - pad * 1.6f, bounds.height() - pad * 1.4f)
        val strokeWidth = (side * 0.072f).coerceIn(7f, 18f)
        val outerRadius = (side * 0.44f).coerceAtLeast(36f)
        val cx = bounds.centerX()
        val cy = bounds.centerY() + side * 0.05f

        arcRect.set(
            cx - outerRadius,
            cy - outerRadius,
            cx + outerRadius,
            cy + outerRadius
        )
        val innerRadius = outerRadius - strokeWidth * 0.95f
        innerRect.set(
            cx - innerRadius,
            cy - innerRadius,
            cx + innerRadius,
            cy + innerRadius
        )

        // Subtle inner tech ring
        innerRingPaint.color = theme.cardBorder
        canvas.drawArc(innerRect, START_ANGLE, SWEEP_ANGLE, false, innerRingPaint)

        // Outer track
        trackPaint.strokeWidth = strokeWidth
        trackPaint.color = theme.trackColor
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        val rawValue = state.getValue(pid)
        if (autoScale && rawValue != null) {
            if (rawValue < observedMin) observedMin = rawValue
            if (rawValue > observedMax) observedMax = rawValue
        }

        val effMin = if (autoScale && observedMin.isFinite() && observedMax.isFinite()) {
            val padRange = ((observedMax - observedMin) * 0.15).coerceAtLeast(1.0)
            minOf(0.0, observedMin - padRange)
        } else {
            min
        }
        val effMax = if (autoScale && observedMin.isFinite() && observedMax.isFinite()) {
            val padRange = ((observedMax - observedMin) * 0.15).coerceAtLeast(1.0)
            maxOf(effMin + 10.0, observedMax + padRange)
        } else {
            max
        }

        val range = (effMax - effMin).coerceAtLeast(1e-3)
        val isBipolar = effMin < 0.0 && effMax > 0.0

        // Draw segmented radial ticks + numeric scale labels
        val tickCount = 24
        val majorEvery = 4
        scaleTextPaint.textSize = (outerRadius * 0.125f).coerceIn(9f, 14f)
        scaleTextPaint.color = theme.textSecondary

        for (i in 0..tickCount) {
            val frac = i.toFloat() / tickCount
            val angleDeg = START_ANGLE + frac * SWEEP_ANGLE
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val isMajor = i % majorEvery == 0

            val rOuter = outerRadius + strokeWidth * 0.48f
            val rInner = outerRadius - strokeWidth * (if (isMajor) 0.55f else 0.25f)
            tickPaint.strokeWidth = if (isMajor) 2.2f else 1.2f
            tickPaint.color = if (isMajor) theme.textSecondary else theme.cardBorder
            canvas.drawLine(
                cx + (cos(angleRad) * rInner).toFloat(),
                cy + (sin(angleRad) * rInner).toFloat(),
                cx + (cos(angleRad) * rOuter).toFloat(),
                cy + (sin(angleRad) * rOuter).toFloat(),
                tickPaint
            )

            if (isMajor) {
                val labelRadius = outerRadius - strokeWidth * 1.48f
                val tickVal = (effMin + frac * range).roundToInt()
                val tx = cx + (cos(angleRad) * labelRadius).toFloat()
                val ty = cy + (sin(angleRad) * labelRadius).toFloat() + scaleTextPaint.textSize * 0.35f
                canvas.drawText(tickVal.toString(), tx, ty, scaleTextPaint)
            }
        }

        // Active colored arc + radial pointer
        val activeColor = when {
            rawValue == null -> theme.textSecondary
            isBipolar && rawValue < -0.5 -> theme.regenGreen
            rawValue > effMin + range * 0.78 -> theme.powerOrange
            else -> theme.accent
        }

        if (rawValue != null) {
            val clamped = rawValue.coerceIn(effMin, effMax)
            val valueFrac = ((clamped - effMin) / range).toFloat()
            val valueAngle = START_ANGLE + valueFrac * SWEEP_ANGLE

            val anchorFrac = if (isBipolar) ((0.0 - effMin) / range).toFloat() else 0f
            val anchorAngle = START_ANGLE + anchorFrac * SWEEP_ANGLE
            val sweep = (valueFrac - anchorFrac) * SWEEP_ANGLE

            if (abs(sweep) > 0.4f) {
                // Soft outer glow halo
                glowPaint.strokeWidth = strokeWidth * 1.55f
                glowPaint.color = Color.argb(
                    if (state.isStale) 25 else 55,
                    Color.red(activeColor),
                    Color.green(activeColor),
                    Color.blue(activeColor)
                )
                canvas.drawArc(arcRect, anchorAngle, sweep, false, glowPaint)

                // Crisp main power arc
                arcPaint.strokeWidth = strokeWidth
                arcPaint.color = activeColor
                WidgetCardDrawer.applyStaleAlpha(arcPaint, state.isStale, theme)
                canvas.drawArc(arcRect, anchorAngle, sweep, false, arcPaint)
            }

            // Crisp pointer needle along the outer rim
            val rad = Math.toRadians(valueAngle.toDouble())
            val rTipOuter = outerRadius + strokeWidth * 0.62f
            val rTipInner = outerRadius - strokeWidth * 0.85f
            needlePaint.strokeWidth = 3.2f
            needlePaint.color = theme.textPrimary
            WidgetCardDrawer.applyStaleAlpha(needlePaint, state.isStale, theme)
            canvas.drawLine(
                cx + (cos(rad) * rTipInner).toFloat(),
                cy + (sin(rad) * rTipInner).toFloat(),
                cx + (cos(rad) * rTipOuter).toFloat(),
                cy + (sin(rad) * rTipOuter).toFloat(),
                needlePaint
            )

            dotPaint.color = activeColor
            WidgetCardDrawer.applyStaleAlpha(dotPaint, state.isStale, theme)
            canvas.drawCircle(
                cx + (cos(rad) * outerRadius).toFloat(),
                cy + (sin(rad) * outerRadius).toFloat(),
                strokeWidth * 0.38f,
                dotPaint
            )
        }

        // Center digital readout
        val formatted = rawValue?.let {
            val pattern = if (isBipolar) "%+.${decimals}f" else "%.${decimals}f"
            String.format(Locale.US, pattern, it)
        } ?: "--"

        val baseValSize = (outerRadius * 0.48f).coerceIn(22f, 54f)
        valuePaint.textSize = baseValSize
        val maxTextW = outerRadius * 1.25f
        val measured = valuePaint.measureText(formatted)
        if (measured > maxTextW) {
            valuePaint.textSize = baseValSize * (maxTextW / measured)
        }
        valuePaint.color = if (rawValue == null) theme.textSecondary else theme.textPrimary
        WidgetCardDrawer.applyStaleAlpha(valuePaint, state.isStale, theme)
        canvas.drawText(formatted, cx, cy + valuePaint.textSize * 0.16f, valuePaint)

        unitPaint.textSize = (outerRadius * 0.18f).coerceIn(11f, 20f)
        unitPaint.color = theme.textSecondary
        canvas.drawText(unit, cx, cy + valuePaint.textSize * 0.16f + unitPaint.textSize * 1.45f, unitPaint)

        // Bottom mode pill (REKU / ECO / POWER / CHARGE)
        val modeText = when {
            rawValue == null -> "STANDBY"
            isBipolar && rawValue < -1.0 -> "REKUPERATION"
            isBipolar && rawValue > max * 0.72 -> "BOOST"
            isBipolar -> "ECO DRIVE"
            rawValue > 1.0 -> "ACTIVE"
            else -> "IDLE"
        }
        pillTextPaint.textSize = (outerRadius * 0.135f).coerceIn(10f, 15f)
        val pillW = (pillTextPaint.measureText(modeText) + 22f).coerceAtMost(bounds.width() * 0.72f)
        val pillH = pillTextPaint.textSize * 1.85f
        val pillTop = (bounds.bottom - pad - pillH).coerceAtLeast(cy + outerRadius * 0.56f)
        pillRect.set(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillTop + pillH)

        pillBgPaint.color = Color.argb(
            if (theme.isDark) 42 else 28,
            Color.red(activeColor),
            Color.green(activeColor),
            Color.blue(activeColor)
        )
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, pillBgPaint)

        pillTextPaint.color = activeColor
        WidgetCardDrawer.applyStaleAlpha(pillTextPaint, state.isStale, theme)
        canvas.drawText(modeText, cx, pillRect.centerY() + pillTextPaint.textSize * 0.34f, pillTextPaint)
    }

    companion object {
        private const val START_ANGLE = 145f
        private const val SWEEP_ANGLE = 250f
    }
}
