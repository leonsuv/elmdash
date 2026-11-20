package com.elmdash.core.obd

import kotlinx.coroutines.flow.StateFlow

enum class TransportState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Pure byte-stream abstraction for ELM327 adapters.
 * Implemented by TcpObdTransport, BleObdTransport, and SppObdTransport.
 */
interface ObdTransport {
    val name: String
    val state: StateFlow<TransportState>

    suspend fun connect()
    suspend fun disconnect()
    suspend fun write(data: ByteArray)

    /**
     * Reads the next available chunk of bytes within [timeoutMs].
     * Returns an empty ByteArray on timeout, or throws IOException if the link drops.
     */
    suspend fun read(timeoutMs: Long): ByteArray
}
