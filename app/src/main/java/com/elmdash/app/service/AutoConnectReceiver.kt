package com.elmdash.app.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.elmdash.app.CorsaDashRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Auto-starts OBD telemetry when the configured Corsa-e infotainment Bluetooth device connects,
 * and immediately stops polling + foreground service on disconnect (12V battery protection).
 */
class AutoConnectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED &&
            action != BluetoothDevice.ACTION_ACL_DISCONNECTED
        ) {
            return
        }

        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val deviceMac = device?.address?.uppercase() ?: return

        val pendingResult = goAsync()
        CorsaDashRuntime.ensureInitialized(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val configuredCarMac = CorsaDashRuntime.preferences.getSettings().carBluetoothMac
                if (configuredCarMac.isBlank() || configuredCarMac != deviceMac) {
                    return@launch
                }

                when (action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        Log.i(TAG, "Car BT connected ($deviceMac) — starting OBD telemetry")
                        ObdForegroundService.start(context)
                    }

                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        Log.i(TAG, "Car BT disconnected ($deviceMac) — stopping OBD telemetry (12V protection)")
                        ObdForegroundService.stop(context)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AutoConnectReceiver"
    }
}
