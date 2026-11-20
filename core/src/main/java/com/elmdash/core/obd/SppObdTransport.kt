package com.elmdash.core.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * Classic Bluetooth RFCOMM/SPP transport for Vgate iCar Pro 2S.
 * Connects directly by saved MAC address without scanning.
 */
class SppObdTransport(
    private val context: Context,
    private val macAddress: String
) : ObdTransport {

    override val name: String = "SPP ($macAddress)"

    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    @Volatile
    private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    override suspend fun connect() = withContext(Dispatchers.IO) {
        disconnect()
        _state.value = TransportState.CONNECTING

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = TransportState.ERROR
            throw IOException("Bluetooth-Berechtigung fehlt — bitte unter Adapter → Scannen erlauben")
        }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: throw IOException("Bluetooth-Adapter nicht verfügbar")
        if (!adapter.isEnabled) {
            _state.value = TransportState.ERROR
            throw IOException("Bluetooth ist ausgeschaltet")
        }

        // Cancel any ongoing discovery so RFCOMM connect succeeds fast and reliably
        runCatching { adapter.cancelDiscovery() }

        val device = try {
            adapter.getRemoteDevice(macAddress.uppercase())
        } catch (e: IllegalArgumentException) {
            _state.value = TransportState.ERROR
            throw IOException("Ungültige Bluetooth-MAC: $macAddress", e)
        }

        try {
            val s = try {
                device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
            } catch (_: IOException) {
                device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
            }
            socket = s
            _state.value = TransportState.CONNECTED
        } catch (e: Exception) {
            _state.value = TransportState.ERROR
            throw IOException("SPP connect failed ($macAddress): ${e.message}", e)
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        val current = socket
        socket = null
        try {
            current?.close()
        } catch (_: Exception) {
        }
        _state.value = TransportState.DISCONNECTED
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("SPP socket not connected")
        try {
            s.outputStream.write(data)
            s.outputStream.flush()
        } catch (e: IOException) {
            _state.value = TransportState.ERROR
            throw e
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("SPP socket not connected")
        val input = s.inputStream
        val buf = ByteArray(1024)

        // Poll non-blocking available() until timeout to avoid hanging RFCOMM reads
        withTimeoutOrNull(timeoutMs) {
            while (input.available() == 0) {
                if (!s.isConnected) throw IOException("SPP socket closed")
                kotlinx.coroutines.delay(5L)
            }
            val count = input.read(buf)
            if (count == -1) {
                _state.value = TransportState.DISCONNECTED
                throw IOException("SPP stream ended")
            }
            buf.copyOf(count)
        } ?: ByteArray(0)
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
