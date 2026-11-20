package com.elmdash.app.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point for Android Auto (Navigation category for full SurfaceCallback canvas access).
 * Uses ALLOW_ALL_HOSTS_VALIDATOR as required for DHU and sideloaded builds.
 */
class CorsaCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return CorsaCarSession()
    }
}
