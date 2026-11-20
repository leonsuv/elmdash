package com.elmdash.core.obd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * TCP byte-stream transport targeting tools/corsa_elm_sim.py.
 * Default 10.0.2.2:35000 reaches the host Mac's localhost:35000 from an Android Emulator (AVD).
 */
class TcpObdTransport(
    private val host: String = DEFAULT_EMULATOR_HOST,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMs: Int = 3000
) : ObdTransport {

    override val name: String = "TCP ($host:$port)"

    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    @Volatile
    private var socket: Socket? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        disconnect()
        _state.value = TransportState.CONNECTING
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket = s
            _state.value = TransportState.CONNECTED
        } catch (e: Exception) {
            _state.value = TransportState.ERROR
            throw IOException("TCP connect failed ($host:$port): ${e.message}", e)
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
        val s = socket ?: throw IOException("TCP socket is not connected")
        try {
            val out = s.getOutputStream()
            out.write(data)
            out.flush()
        } catch (e: Exception) {
            _state.value = TransportState.ERROR
            throw IOException("TCP write failed: ${e.message}", e)
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("TCP socket is not connected")
        val buf = ByteArray(1024)
        try {
            s.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
            val count = s.getInputStream().read(buf)
            if (count == -1) {
                _state.value = TransportState.DISCONNECTED
                throw IOException("TCP peer closed connection")
            }
            buf.copyOf(count)
        } catch (_: SocketTimeoutException) {
            ByteArray(0)
        } catch (e: IOException) {
            _state.value = TransportState.ERROR
            throw e
        }
    }

    companion object {
        const val DEFAULT_EMULATOR_HOST = "10.0.2.2"
        const val DEFAULT_LOCALHOST = "127.0.0.1"
        const val DEFAULT_PORT = 35000
    }
}
