package com.elmdash.ui.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.elmdash.core.telemetry.ConnectionState
import com.elmdash.core.telemetry.TelemetrySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware-accelerated Android Auto Surface renderer with dirty-flag throttling (up to 60 FPS).
 * - Places the compact tab pills + live connection pill in the top header strip (left of the
 *   single compact Android Auto ActionStrip pill), reclaiming ~10% screen height for the 12x6 grid.
 * - Keeps all telemetry widgets strictly inside Android Auto's stableArea.
 * - Supports live layout hot-reload and Phone Companion 16:9 preview rendering.
 */
class SurfaceRenderer(
    private val gridEngine: GridLayoutEngine = GridLayoutEngine(),
    initialPages: List<DashboardPage>,
    private val telemetryFlow: StateFlow<TelemetrySnapshot>,
    private val isDarkModeProvider: () -> Boolean
) {
    private val surfaceLock = Any()
    private val dirty = AtomicBoolean(true)

    @Volatile
    private var pages: List<DashboardPage> = initialPages

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var surfaceWidth: Int = 0

    @Volatile
    private var surfaceHeight: Int = 0

    @Volatile
    private var surfaceDpi: Int = 160

    @Volatile
    private var visibleArea: Rect = Rect()

    @Volatile
    private var stableArea: Rect = Rect()

    @Volatile
    private var activePageIndex: Int = 0

    @Volatile
    private var endlessScrollMode: Boolean = false

    @Volatile
    private var endlessScrollX: Float = 0f

    @Volatile
    private var swipeAccumX: Float = 0f

    @Volatile
    private var tabHitRects: List<Pair<Int, RectF>> = emptyList()

    @Volatile
    private var widgetHitRects: List<Pair<WidgetSlotConfig, RectF>> = emptyList()

    @Volatile
    private var lastGridArea: RectF = RectF()

    @Volatile
    private var dragGhostGridRect: RectF? = null

    private var renderJob: Job? = null
    private var collectJob: Job? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val tabTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillRect = RectF()

    val currentPageIndex: Int
        get() = activePageIndex

    fun updatePages(newPages: List<DashboardPage>) {
        pages = newPages
        if (pages.isNotEmpty() && activePageIndex > pages.lastIndex) {
            activePageIndex = 0
        }
        markDirty()
    }

    fun setEndlessScrollMode(enabled: Boolean) {
        if (endlessScrollMode != enabled) {
            endlessScrollMode = enabled
            val pageW = lastGridArea.width().takeIf { it > 10f } ?: surfaceWidth.toFloat().coerceAtLeast(1f)
            endlessScrollX = activePageIndex * pageW
            markDirty()
        }
    }

    fun setPage(index: Int) {
        if (pages.isEmpty()) return
        activePageIndex = index.coerceIn(0, pages.lastIndex)
        val pageW = lastGridArea.width().takeIf { it > 10f } ?: surfaceWidth.toFloat().coerceAtLeast(1f)
        endlessScrollX = activePageIndex * pageW
        markDirty()
    }

    fun nextPage(): Int {
        if (pages.isEmpty()) return 0
        setPage((activePageIndex + 1) % pages.size)
        return activePageIndex
    }

    fun prevPage(): Int {
        if (pages.isEmpty()) return 0
        setPage((activePageIndex - 1 + pages.size) % pages.size)
        return activePageIndex
    }

    /**
     * Handles horizontal touch scroll from Android Auto SurfaceCallback.onScroll.
     * - In Endless Mode: seamlessly pans across all dashboards stitched horizontally with wrap-around.
     * - In Standard Mode: accumulates swipe distance and snaps to next/prev tab cleanly.
     */
    fun handleScroll(distanceX: Float): Boolean {
        if (pages.size <= 1) return false
        val pageW = lastGridArea.width().takeIf { it > 10f } ?: surfaceWidth.toFloat().coerceAtLeast(1f)
        if (endlessScrollMode) {
            val totalW = pageW * pages.size
            var nextX = (endlessScrollX + distanceX) % totalW
            if (nextX < 0f) nextX += totalW
            endlessScrollX = nextX
            activePageIndex = ((nextX + pageW * 0.5f) / pageW).toInt() % pages.size
            markDirty()
            return true
        } else {
            swipeAccumX += distanceX
            val threshold = (pageW * 0.16f).coerceAtLeast(80f)
            if (swipeAccumX > threshold) {
                swipeAccumX = 0f
                nextPage()
                return true
            } else if (swipeAccumX < -threshold) {
                swipeAccumX = 0f
                prevPage()
                return true
            }
            return false
        }
    }

    fun handleFling(velocityX: Float): Boolean {
        if (pages.size <= 1) return false
        swipeAccumX = 0f
        if (endlessScrollMode) {
            // Smooth momentum nudge in endless mode
            handleScroll(-velocityX * 0.08f)
            return true
        }
        return when {
            velocityX < -300f -> { nextPage(); true }
            velocityX > 300f -> { prevPage(); true }
            else -> false
        }
    }

    /**
     * Handles a direct touch tap (from Android Auto SurfaceCallback.onClick or Phone preview)
     * against the rendered tab pills. Returns the newly selected page index, or null if no tab was hit.
     */
    fun handleTabTap(x: Float, y: Float): Int? {
        val pad = 10f
        for ((idx, rect) in tabHitRects) {
            if (x >= rect.left - pad && x <= rect.right + pad && y >= rect.top - pad && y <= rect.bottom + pad) {
                setPage(idx)
                return idx
            }
        }
        return null
    }

    fun hitTestWidget(x: Float, y: Float): WidgetSlotConfig? {
        for ((slot, rect) in widgetHitRects.asReversed()) {
            if (rect.contains(x, y)) return slot
        }
        return null
    }

    fun pixelToGridCell(x: Float, y: Float): Pair<Int, Int>? {
        val area = lastGridArea
        if (area.width() <= 1f || area.height() <= 1f) return null
        val col = (((x - area.left) / area.width()) * gridEngine.columns).toInt().coerceIn(0, gridEngine.columns - 1)
        val row = (((y - area.top) / area.height()) * gridEngine.rows).toInt().coerceIn(0, gridEngine.rows - 1)
        return col to row
    }

    fun setDragGhost(col: Int, row: Int, colSpan: Int, rowSpan: Int, dpi: Int) {
        val area = lastGridArea
        if (area.width() <= 1f || area.height() <= 1f) return
        val density = (dpi.takeIf { it > 0 } ?: 160) / 160f
        dragGhostGridRect = gridEngine.computeWidgetBounds(col, row, colSpan, rowSpan, area, 8f * density)
    }

    fun clearDragGhost() {
        dragGhostGridRect = null
    }

    fun attachSurface(surface: Surface?, width: Int, height: Int, dpi: Int) {
        synchronized(surfaceLock) {
            this.surface = surface
            this.surfaceWidth = width
            this.surfaceHeight = height
            this.surfaceDpi = dpi.takeIf { it > 0 } ?: 160
        }
        markDirty()
    }

    fun updateVisibleArea(area: Rect) {
        this.visibleArea = Rect(area)
        markDirty()
    }

    fun updateStableArea(area: Rect) {
        this.stableArea = Rect(area)
        markDirty()
    }

    fun detachSurface() {
        synchronized(surfaceLock) {
            this.surface = null
        }
    }

    fun markDirty() {
        dirty.set(true)
    }

    fun start(scope: CoroutineScope) {
        stop()
        collectJob = scope.launch(Dispatchers.Default) {
            telemetryFlow.collect {
                markDirty()
            }
        }
        renderJob = scope.launch(Dispatchers.Default) {
            var lastDarkMode = isDarkModeProvider()
            var lastDrawMs = 0L
            while (isActive) {
                val nowDark = isDarkModeProvider()
                if (nowDark != lastDarkMode) {
                    lastDarkMode = nowDark
                    markDirty()
                }
                val now = SystemClock.elapsedRealtime()
                if (dirty.getAndSet(false) || (now - lastDrawMs) >= 500L) {
                    drawOnce(telemetryFlow.value, nowDark)
                    lastDrawMs = SystemClock.elapsedRealtime()
                }
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        renderJob?.cancel()
        renderJob = null
        collectJob?.cancel()
        collectJob = null
    }

    /**
     * Direct canvas rendering helper used by the Phone Companion 16:9 Live Preview.
     */
    fun renderPreview(
        canvas: Canvas,
        width: Int,
        height: Int,
        dpi: Int,
        snapshot: TelemetrySnapshot,
        isDark: Boolean
    ) {
        val full = Rect(0, 0, width, height)
        val density = (dpi.takeIf { it > 0 } ?: 160) / 160f
        val topBarPx = (36f * density).toInt()
        val previewStable = Rect(0, topBarPx, width, height)
        renderScene(
            canvas = canvas,
            width = width,
            height = height,
            dpi = dpi,
            visible = full,
            stable = previewStable,
            snapshot = snapshot,
            isDark = isDark
        )
    }

    private fun drawOnce(snapshot: TelemetrySnapshot, isDark: Boolean) {
        synchronized(surfaceLock) {
            val s = surface ?: return
            if (!s.isValid || surfaceWidth <= 0 || surfaceHeight <= 0) return

            val canvas = try {
                // Use lockCanvas(null) to avoid HWUI/EGL surface-API contention when
                // CarAppService VirtualDisplay and MainActivity Compose RenderThread run in the same process.
                s.lockCanvas(null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to lock canvas", e)
                return
            }

            try {
                renderScene(
                    canvas = canvas,
                    width = surfaceWidth,
                    height = surfaceHeight,
                    dpi = surfaceDpi,
                    visible = visibleArea,
                    stable = stableArea,
                    snapshot = snapshot,
                    isDark = isDark
                )
            } finally {
                try {
                    s.unlockCanvasAndPost(canvas)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Surface released during unlockCanvasAndPost", e)
                }
            }
        }
    }

    private fun renderScene(
        canvas: Canvas,
        width: Int,
        height: Int,
        dpi: Int,
        visible: Rect,
        stable: Rect,
        snapshot: TelemetrySnapshot,
        isDark: Boolean
    ) {
        val theme = DashboardTheme.forDarkMode(isDark)
        val density = (dpi.takeIf { it > 0 } ?: 160) / 160f

        bgPaint.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val safeRect = stable.takeIf { !it.isEmpty } ?: Rect(0, 0, width, height)

        // Android Auto reserves top ~76-88dp above stableArea for the ActionStrip icon in the top-right.
        // Place our tappable tab pills + LIVE badge directly in the top-left of that strip (0..safeRect.top)
        // so ZERO vertical space is wasted and the 12x6 widget grid starts immediately at safeRect.top!
        val headerInsideTopStrip = safeRect.top >= (44f * density)

        val headerTop = if (headerInsideTopStrip) 4f * density else safeRect.top.toFloat()
        val headerHeight = if (headerInsideTopStrip) (safeRect.top - 4f * density) else (38f * density)

        drawTopHeaderStrip(
            canvas = canvas,
            left = safeRect.left.toFloat() + 10f * density,
            top = headerTop,
            height = headerHeight,
            maxRight = safeRect.right.toFloat() - (if (headerInsideTopStrip) 92f * density else 10f * density),
            density = density,
            snapshot = snapshot,
            theme = theme
        )

        val page = pages.getOrNull(activePageIndex) ?: return

        val gridHeaderOffsetDp = if (headerInsideTopStrip) 0f else 38f
        val gridArea = gridEngine.computeGridArea(safeRect, density, statusHeaderDp = gridHeaderOffsetDp)
        lastGridArea = RectF(gridArea)
        val gutterPx = 8f * density

        if (endlessScrollMode && pages.size > 1) {
            // Seamless endless horizontal ribbon stitching all enabled dashboards side-by-side
            val pageW = gridArea.width() + gutterPx * 2f
            val totalW = pageW * pages.size
            var normOffset = endlessScrollX % totalW
            if (normOffset < 0f) normOffset += totalW

            canvas.save()
            canvas.clipRect(gridArea)
            for (idx in pages.indices) {
                // Draw each page and its wrapped copy if near seam
                for (wrapShift in listOf(-totalW, 0f, totalW)) {
                    val offsetX = idx * pageW - normOffset + wrapShift
                    if (offsetX + pageW < -10f || offsetX > gridArea.width() + 10f) continue
                    val shiftedArea = RectF(
                        gridArea.left + offsetX,
                        gridArea.top,
                        gridArea.right + offsetX,
                        gridArea.bottom
                    )
                    for (bound in pages[idx].widgets) {
                        val slot = bound.slot
                        val bounds = gridEngine.computeWidgetBounds(
                            col = slot.col,
                            row = slot.row,
                            colSpan = slot.colSpan,
                            rowSpan = slot.rowSpan,
                            gridArea = shiftedArea,
                            gutterPx = gutterPx
                        )
                        bound.widget.draw(canvas, bounds, snapshot, theme)
                    }
                }
            }
            canvas.restore()
            widgetHitRects = emptyList()
            return
        }

        val hitList = ArrayList<Pair<WidgetSlotConfig, RectF>>(page.widgets.size)
        for (bound in page.widgets) {
            val slot = bound.slot
            val bounds = gridEngine.computeWidgetBounds(
                col = slot.col,
                row = slot.row,
                colSpan = slot.colSpan,
                rowSpan = slot.rowSpan,
                gridArea = gridArea,
                gutterPx = gutterPx
            )
            hitList.add(slot to RectF(bounds))
            bound.widget.draw(canvas, bounds, snapshot, theme)
        }
        widgetHitRects = hitList

        // Optional drag-and-drop snap preview highlight (used in Phone interactive editor)
        dragGhostGridRect?.let { ghost ->
            pillBgPaint.color = Color.argb(60, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent))
            canvas.drawRoundRect(ghost, 12f, 12f, pillBgPaint)
            pillBorderPaint.color = theme.accent
            pillBorderPaint.strokeWidth = 3f
            canvas.drawRoundRect(ghost, 12f, 12f, pillBorderPaint)
            pillBorderPaint.strokeWidth = 1.5f
        }
    }

    private fun drawTopHeaderStrip(
        canvas: Canvas,
        left: Float,
        top: Float,
        height: Float,
        maxRight: Float,
        density: Float,
        snapshot: TelemetrySnapshot,
        theme: DashboardTheme
    ) {
        val cy = top + height * 0.5f
        var cursorX = left

        // Larger, touch-friendly tappable tab pills
        tabTextPaint.textSize = 15f * density
        val pillH = (34f * density).coerceAtMost(height - 6f * density).coerceAtLeast(24f * density)
        val pillGap = 8f * density

        val newTabHits = ArrayList<Pair<Int, RectF>>(pages.size)
        pages.forEachIndexed { idx, page ->
            val label = page.title.uppercase(Locale.US)
            val textW = tabTextPaint.measureText(label)
            val pillW = textW + 26f * density
            if (cursorX + pillW > maxRight - 95f * density) return@forEachIndexed

            pillRect.set(cursorX, cy - pillH / 2f, cursorX + pillW, cy + pillH / 2f)
            newTabHits.add(idx to RectF(pillRect))

            val isActive = idx == activePageIndex
            pillBgPaint.color = if (isActive) {
                Color.argb(if (theme.isDark) 68 else 38, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent))
            } else {
                theme.cardBackground
            }
            canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, pillBgPaint)

            pillBorderPaint.color = if (isActive) theme.accent else theme.cardBorder
            pillBorderPaint.strokeWidth = if (isActive) 2.2f * density else 1.4f * density
            canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, pillBorderPaint)

            tabTextPaint.color = if (isActive) theme.accent else theme.textSecondary
            canvas.drawText(label, pillRect.centerX(), cy + tabTextPaint.textSize * 0.35f, tabTextPaint)

            cursorX += pillW + pillGap
        }
        tabHitRects = newTabHits

        // Compact live connection pill right next to the tab pills
        val (statusColor, statusLabel) = when (snapshot.connectionState) {
            ConnectionState.LIVE -> theme.regenGreen to String.format(Locale.US, "LIVE %.0f Hz", snapshot.pollRateHz)
            ConnectionState.CONNECTING -> theme.accent to "CONNECTING"
            ConnectionState.RECONNECTING -> theme.warningAmber to "CACHE"
            ConnectionState.ERROR -> theme.criticalRed to "ERROR"
            ConnectionState.DISCONNECTED -> theme.textSecondary to "OFFLINE"
        }

        cursorX += 6f * density
        statusPaint.textSize = 12.5f * density
        statusPaint.color = statusColor
        dotPaint.color = statusColor
        if (cursorX + 85f * density < maxRight) {
            canvas.drawCircle(cursorX + 4.5f * density, cy, 4.5f * density, dotPaint)
            canvas.drawText(statusLabel, cursorX + 14f * density, cy + statusPaint.textSize * 0.35f, statusPaint)
        }
    }

    companion object {
        private const val TAG = "SurfaceRenderer"
        private const val FRAME_INTERVAL_MS = 16L
    }
}
