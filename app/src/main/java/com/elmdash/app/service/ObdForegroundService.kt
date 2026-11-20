package com.elmdash.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.elmdash.app.CorsaDashRuntime
import com.elmdash.app.R

/**
 * Foreground service (connectedDevice) that keeps OBD2 telemetry active while
 * the user switches between Corsa-e Dash and Maps/Spotify in Android Auto.
 */
class ObdForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CorsaDashRuntime.ensureInitialized(applicationContext)

        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stopping OBD foreground service (12V protection)")
            CorsaDashRuntime.stopPolling()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // On Android 14+, connectedDevice FGS requires runtime BT permission when not in TCP-only debug;
            // fall back cleanly to in-process polling without crashing.
            Log.w(TAG, "Foreground promotion skipped (${e.message}); continuing in-process polling")
        }

        CorsaDashRuntime.startPolling(applicationContext)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CorsaDashRuntime.stopPolling()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Corsa-e OBD2 Telemetry",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Live EV telemetry active")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ObdForegroundService"
        private const val CHANNEL_ID = "corsa_obd_telemetry"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.elmdash.app.action.START_OBD"
        const val ACTION_STOP = "com.elmdash.app.action.STOP_OBD"

        fun start(context: Context) {
            val intent = Intent(context, ObdForegroundService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "FGS start fallback to direct polling: ${e.message}")
                CorsaDashRuntime.startPolling(context)
            }
        }

        fun stop(context: Context) {
            CorsaDashRuntime.stopPolling()
            val intent = Intent(context, ObdForegroundService::class.java).setAction(ACTION_STOP)
            try {
                context.stopService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
