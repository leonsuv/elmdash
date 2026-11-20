package com.elmdash.core.telemetry

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.elmdash.core.obd.TcpObdTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.obdDataStore by preferencesDataStore(name = "elmdash_obd_prefs")

enum class TransportMode {
    TCP,
    BLE,
    SPP
}

data class ObdSettings(
    val transportMode: TransportMode = TransportMode.TCP,
    val adapterMac: String = "",
    val carBluetoothMac: String = "",
    val tcpHost: String = TcpObdTransport.DEFAULT_EMULATOR_HOST,
    val tcpPort: Int = TcpObdTransport.DEFAULT_PORT
)

/**
 * DataStore-backed configuration for Instant-Connect (MAC saved directly, no scan on startup).
 */
class ObdPreferences(private val context: Context) {

    val settingsFlow: Flow<ObdSettings> = context.obdDataStore.data.map { prefs ->
        val modeName = prefs[KEY_TRANSPORT_MODE] ?: TransportMode.TCP.name
        val mode = runCatching { TransportMode.valueOf(modeName) }.getOrDefault(TransportMode.TCP)
        ObdSettings(
            transportMode = mode,
            adapterMac = prefs[KEY_ADAPTER_MAC].orEmpty(),
            carBluetoothMac = prefs[KEY_CAR_BT_MAC].orEmpty(),
            tcpHost = prefs[KEY_TCP_HOST] ?: TcpObdTransport.DEFAULT_EMULATOR_HOST,
            tcpPort = prefs[KEY_TCP_PORT] ?: TcpObdTransport.DEFAULT_PORT
        )
    }

    suspend fun getSettings(): ObdSettings = settingsFlow.first()

    suspend fun saveSettings(settings: ObdSettings) {
        context.obdDataStore.edit { prefs ->
            prefs[KEY_TRANSPORT_MODE] = settings.transportMode.name
            prefs[KEY_ADAPTER_MAC] = settings.adapterMac.trim().uppercase()
            prefs[KEY_CAR_BT_MAC] = settings.carBluetoothMac.trim().uppercase()
            prefs[KEY_TCP_HOST] = settings.tcpHost.trim().ifEmpty { TcpObdTransport.DEFAULT_EMULATOR_HOST }
            prefs[KEY_TCP_PORT] = settings.tcpPort.coerceIn(1, 65535)
        }
    }

    companion object {
        private val KEY_TRANSPORT_MODE = stringPreferencesKey("transport_mode")
        private val KEY_ADAPTER_MAC = stringPreferencesKey("adapter_mac")
        private val KEY_CAR_BT_MAC = stringPreferencesKey("car_bt_mac")
        private val KEY_TCP_HOST = stringPreferencesKey("tcp_host")
        private val KEY_TCP_PORT = intPreferencesKey("tcp_port")
    }
}
