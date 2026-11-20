package com.elmdash.ui.widgets

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.elmdash.ui.engine.DashboardTheme

internal object WidgetCardDrawer {

    fun drawCardBackground(
        canvas: Canvas,
        bounds: RectF,
        theme: DashboardTheme,
        fillPaint: Paint,
        borderPaint: Paint,
        radiusRatio: Float = 0.08f
    ) {
        val radius = (minOf(bounds.width(), bounds.height()) * radiusRatio).coerceIn(8f, 18f)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = theme.cardBackground
        canvas.drawRoundRect(bounds, radius, radius, fillPaint)

        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = 1.5f
        borderPaint.color = theme.cardBorder
        canvas.drawRoundRect(bounds, radius, radius, borderPaint)
    }

    fun applyStaleAlpha(paint: Paint, isStale: Boolean, theme: DashboardTheme) {
        paint.alpha = if (isStale) theme.staleAlpha else 255
    }

    fun labelPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    fun valuePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
}
