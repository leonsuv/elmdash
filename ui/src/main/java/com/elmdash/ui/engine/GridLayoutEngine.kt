package com.elmdash.ui.engine

import android.graphics.Rect
import android.graphics.RectF

/**
 * 12x6 virtual grid layout engine.
 * Automatically scales to display resolution & DPI and renders strictly inside Android Auto's stableArea.
 */
class GridLayoutEngine(
    val columns: Int = DEFAULT_COLUMNS,
    val rows: Int = DEFAULT_ROWS
) {
    fun computeWidgetBounds(
        col: Int,
        row: Int,
        colSpan: Int,
        rowSpan: Int,
        gridArea: RectF,
        gutterPx: Float
    ): RectF {
        val safeCol = col.coerceIn(0, columns - 1)
        val safeRow = row.coerceIn(0, rows - 1)
        val safeColSpan = colSpan.coerceIn(1, columns - safeCol)
        val safeRowSpan = rowSpan.coerceIn(1, rows - safeRow)

        val totalGutterX = gutterPx * (columns - 1)
        val totalGutterY = gutterPx * (rows - 1)
        val cellW = ((gridArea.width() - totalGutterX) / columns).coerceAtLeast(1f)
        val cellH = ((gridArea.height() - totalGutterY) / rows).coerceAtLeast(1f)

        val left = gridArea.left + safeCol * (cellW + gutterPx)
        val top = gridArea.top + safeRow * (cellH + gutterPx)
        val right = left + safeColSpan * cellW + (safeColSpan - 1) * gutterPx
        val bottom = top + safeRowSpan * cellH + (safeRowSpan - 1) * gutterPx
        return RectF(left, top, right, bottom)
    }

    fun computeGridArea(stableArea: Rect, density: Float, statusHeaderDp: Float = 28f): RectF {
        val pad = 10f * density
        val headerPx = statusHeaderDp * density
        return RectF(
            stableArea.left + pad,
            stableArea.top + pad + headerPx,
            (stableArea.right - pad).coerceAtLeast(stableArea.left + pad + 1f),
            (stableArea.bottom - pad).coerceAtLeast(stableArea.top + pad + headerPx + 1f)
        )
    }

    companion object {
        const val DEFAULT_COLUMNS = 12
        const val DEFAULT_ROWS = 6
    }
}
