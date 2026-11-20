package com.elmdash.app.car

import android.graphics.Rect
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.elmdash.app.CorsaDashRuntime
import com.elmdash.app.R
import com.elmdash.ui.engine.DashboardPage
import com.elmdash.ui.engine.LayoutLoader
import com.elmdash.ui.engine.SurfaceRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android Auto NavigationTemplate screen hosting the live customizable widget dashboard.
 * Uses a single compact ActionStrip switcher pill ("⇄ NextPage") so the custom top tab/status
 * strip sits cleanly beside it and the 12x6 widget grid reclaims almost the entire screen.
 */
class DashboardScreen(carContext: CarContext) : Screen(carContext) {

    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var pages: List<DashboardPage> =
        LayoutLoader.buildPages(CorsaDashRuntime.layoutConfig.value, onlyEnabled = true)

    private val renderer = SurfaceRenderer(
        initialPages = pages,
        telemetryFlow = CorsaDashRuntime.repository.snapshot,
        isDarkModeProvider = { carContext.isDarkMode }
    ).apply {
        setEndlessScrollMode(CorsaDashRuntime.layoutConfig.value.endlessScrollMode)
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            Log.i(
                TAG,
                "Surface available: ${surfaceContainer.width}x${surfaceContainer.height} @ ${surfaceContainer.dpi} dpi"
            )
            renderer.attachSurface(
                surface = surfaceContainer.surface,
                width = surfaceContainer.width,
                height = surfaceContainer.height,
                dpi = surfaceContainer.dpi
            )
            renderer.start(renderScope)
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            renderer.updateVisibleArea(visibleArea)
        }

        override fun onStableAreaChanged(stableArea: Rect) {
            Log.d(TAG, "Stable area changed: $stableArea")
            renderer.updateStableArea(stableArea)
        }

        override fun onClick(x: Float, y: Float) {
            val tappedTab = renderer.handleTabTap(x, y)
            if (tappedTab != null) {
                invalidate()
            }
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            if (renderer.handleScroll(distanceX)) {
                invalidate()
            }
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            if (renderer.handleFling(velocityX)) {
                invalidate()
            }
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            Log.i(TAG, "Surface destroyed")
            renderer.stop()
            renderer.detachSurface()
        }
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)

        // Hot-reload custom layouts edited in the Phone Companion App in real time
        renderScope.launch {
            CorsaDashRuntime.layoutConfig.collect { config ->
                val rebuilt = LayoutLoader.buildPages(config, onlyEnabled = true)
                renderer.setEndlessScrollMode(config.endlessScrollMode)
                if (rebuilt.isNotEmpty()) {
                    pages = rebuilt
                    renderer.updatePages(rebuilt)
                    invalidate()
                }
            }
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                renderer.stop()
                renderer.detachSurface()
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                renderScope.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        // NavigationTemplate requires an ActionStrip; keep a single compact icon-only reconnect button
        // while tabs are tapped directly or swiped on the canvas!
        val refreshIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_refresh)
        ).build()

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(refreshIcon)
                    .setOnClickListener { CorsaDashRuntime.restartPolling(carContext) }
                    .build()
            )
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }

    companion object {
        private const val TAG = "DashboardScreen"
    }
}
