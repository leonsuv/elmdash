package com.elmdash.core.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardened BLE GATT transport for Vgate iCar Pro 2S (ELM327 v2.3, Bluetooth 5.3).
 * - Direct MAC connect via getRemoteDevice(mac) (no scan on startup)
 * - Primary Vgate UUIDs (18F0 / 2AF0 / 2AF1) with fallback to FFF0 / FFE0 ELM327 GATT profiles
 * - Safe 150ms post-connect settle + MTU 185 negotiation with timeout fallback to discoverServices()
 * - Dynamic WRITE_TYPE_NO_RESPONSE vs WRITE_TYPE_DEFAULT & NOTIFY vs INDICATE detection
 */
class BleObdTransport(
    private val context: Context,
    private val macAddress: String
) : ObdTransport {

    override val name: String = "BLE ($macAddress)"

    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val rxChannel = Channel<ByteArray>(capacity = 128)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var writeChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var notifyCharUuid: UUID = NOTIFY_CHAR_UUID

    @Volatile
    private var mtuPayloadSize: Int = 20

    private var readyDeferred: CompletableDeferred<Unit>? = null
    private var writeDeferred: CompletableDeferred<Boolean>? = null

    @SuppressLint("MissingPermission")
    override suspend fun connect() {
        disconnect()
        _state.value = TransportState.CONNECTING

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = TransportState.ERROR
            throw IOException("Bluetooth-Berechtigung fehlt — bitte unter Adapter → Scannen erlauben")
        }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = btManager?.adapter
            ?: throw IOException("Bluetooth-Adapter nicht verfügbar")
        if (!adapter.isEnabled) {
            _state.value = TransportState.ERROR
            throw IOException("Bluetooth ist ausgeschaltet")
        }

        val device = try {
            adapter.getRemoteDevice(macAddress.uppercase())
        } catch (e: IllegalArgumentException) {
            _state.value = TransportState.ERROR
            throw IOException("Ungültige Bluetooth-MAC: $macAddress", e)
        }

        val deferred = CompletableDeferred<Unit>()
        readyDeferred = deferred
        val discoveryStarted = AtomicBoolean(false)

        fun triggerDiscoveryOnce(g: BluetoothGatt) {
            if (discoveryStarted.compareAndSet(false, true)) {
                g.discoverServices()
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    _state.value = TransportState.ERROR
                    deferred.completeExceptionally(IOException("BLE GATT Fehler (status=$status)"))
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // 150ms settle avoids MagicOS/Samsung GATT 133/MTU race, plus 1.5s fallback
                        mainHandler.postDelayed({
                            if (gatt == g && !discoveryStarted.get()) {
                                if (!g.requestMtu(TARGET_MTU)) {
                                    triggerDiscoveryOnce(g)
                                } else {
                                    mainHandler.postDelayed({
                                        if (gatt == g && !discoveryStarted.get()) {
                                            triggerDiscoveryOnce(g)
                                        }
                                    }, 1500L)
                                }
                            }
                        }, 150L)
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        _state.value = TransportState.DISCONNECTED
                        rxChannel.trySend(ByteArray(0))
                        if (!deferred.isCompleted) {
                            deferred.completeExceptionally(IOException("BLE Verbindung während Setup getrennt"))
                        }
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && mtu > 23) {
                    mtuPayloadSize = (mtu - 3).coerceAtLeast(20)
                }
                triggerDiscoveryOnce(g)
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    deferred.completeExceptionally(IOException("BLE Service-Discovery fehlgeschlagen ($status)"))
                    return
                }

                val pair = resolveObdCharacteristics(g)
                if (pair == null) {
                    deferred.completeExceptionally(IOException("Vgate BLE Service (18F0/FFF0/FFE0) nicht gefunden"))
                    return
                }

                val (notifyChar, txChar) = pair
                notifyCharUuid = notifyChar.uuid
                writeChar = txChar
                g.setCharacteristicNotification(notifyChar, true)

                val cccd = notifyChar.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    val enableValue = if ((notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, enableValue)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = enableValue
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                } else {
                    _state.value = TransportState.CONNECTED
                    deferred.complete(Unit)
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == CCCD_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        _state.value = TransportState.CONNECTED
                        deferred.complete(Unit)
                    } else {
                        deferred.completeExceptionally(IOException("BLE Notification-Aktivierung fehlgeschlagen ($status)"))
                    }
                }
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                writeDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == notifyCharUuid) {
                    rxChannel.trySend(value.copyOf())
                }
            }

            @Deprecated("Deprecated in API 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == notifyCharUuid) {
                    characteristic.value?.let { rxChannel.trySend(it.copyOf()) }
                }
            }
        }

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }

        try {
            withTimeout(12_000L) {
                deferred.await()
            }
        } catch (e: Exception) {
            disconnect()
            _state.value = TransportState.ERROR
            throw IOException("BLE Verbindung fehlgeschlagen ($macAddress): ${e.message}", e)
        }
    }

    private fun resolveObdCharacteristics(
        g: BluetoothGatt
    ): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        // 1. Vgate iCar Pro 2S primary profile (18F0 / 2AF0 / 2AF1)
        g.getService(SERVICE_UUID)?.let { svc ->
            val rx = svc.getCharacteristic(NOTIFY_CHAR_UUID)
            val tx = svc.getCharacteristic(WRITE_CHAR_UUID)
            if (rx != null && tx != null) return rx to tx
        }
        // 2. Alternative ELM327 BLE profile (FFF0 / FFF1 / FFF2)
        g.getService(ALT_SERVICE_FFF0)?.let { svc ->
            val rx = svc.getCharacteristic(ALT_NOTIFY_FFF1)
            val tx = svc.getCharacteristic(ALT_WRITE_FFF2) ?: rx
            if (rx != null && tx != null) return rx to tx
        }
        // 3. Alternative ELM327 BLE profile (FFE0 / FFE1)
        g.getService(ALT_SERVICE_FFE0)?.let { svc ->
            val rw = svc.getCharacteristic(ALT_RW_FFE1)
            if (rw != null) return rw to rw
        }
        return null
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() {
        mainHandler.removeCallbacksAndMessages(null)
        val current = gatt
        gatt = null
        writeChar = null
        try {
            current?.disconnect()
            current?.close()
        } catch (_: Exception) {
        }
        while (rxChannel.tryReceive().isSuccess) {
            // drain stale chunks
        }
        _state.value = TransportState.DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    override suspend fun write(data: ByteArray) {
        val g = gatt ?: throw IOException("BLE GATT nicht verbunden")
        val ch = writeChar ?: throw IOException("BLE Write-Characteristic nicht verfügbar")

        val useNoResponse = (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val writeType = if (useNoResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        var offset = 0
        while (offset < data.size) {
            val end = (offset + mtuPayloadSize).coerceAtMost(data.size)
            val chunk = data.copyOfRange(offset, end)
            val ack = CompletableDeferred<Boolean>()
            writeDeferred = ack

            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, chunk, writeType) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.writeType = writeType
                @Suppress("DEPRECATION")
                ch.value = chunk
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }

            if (!started) {
                throw IOException("BLE writeCharacteristic fehlgeschlagen")
            }
            withTimeoutOrNull(800L) { ack.await() }
            offset = end
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray {
        if (_state.value != TransportState.CONNECTED) {
            throw IOException("BLE nicht verbunden")
        }
        return withTimeoutOrNull(timeoutMs) {
            rxChannel.receive()
        } ?: ByteArray(0)
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb")
        val NOTIFY_CHAR_UUID: UUID = UUID.fromString("00002af0-0000-1000-8000-00805f9b34fb")
        val WRITE_CHAR_UUID: UUID = UUID.fromString("00002af1-0000-1000-8000-00805f9b34fb")

        private val ALT_SERVICE_FFF0: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private val ALT_NOTIFY_FFF1: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val ALT_WRITE_FFF2: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        private val ALT_SERVICE_FFE0: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val ALT_RW_FFE1: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TARGET_MTU = 185
    }
}
