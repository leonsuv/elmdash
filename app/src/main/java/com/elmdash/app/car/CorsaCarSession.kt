package com.elmdash.app.car

import android.content.Intent
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.elmdash.app.CorsaDashRuntime
import com.elmdash.app.service.ObdForegroundService

class CorsaCarSession : Session() {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                CorsaDashRuntime.ensureInitialized(carContext)
                ObdForegroundService.start(carContext)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                Log.i(TAG, "Car session destroyed — stopping OBD polling & service (12V protection)")
                ObdForegroundService.stop(carContext)
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        CorsaDashRuntime.ensureInitialized(carContext)
        return DashboardScreen(carContext)
    }

    companion object {
        private const val TAG = "CorsaCarSession"
    }
}
