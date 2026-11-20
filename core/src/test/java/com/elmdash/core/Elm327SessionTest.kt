package com.elmdash.core

import com.elmdash.core.obd.Elm327Session
import com.elmdash.core.obd.ObdTransport
import com.elmdash.core.obd.TransportState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327SessionTest {

    private class FakeObdTransport(
        private val onReadTimeout: (Long) -> Unit = {},
        private val responder: (String) -> String?
    ) : ObdTransport {
        override val name: String = "FakeTransport"
        override val state: StateFlow<TransportState> = MutableStateFlow(TransportState.CONNECTED)

        val sentCommands = mutableListOf<String>()
        private val rxQueue = ArrayDeque<ByteArray>()

        override suspend fun connect() {}
        override suspend fun disconnect() {}

        override suspend fun write(data: ByteArray) {
            val cmd = String(data, Charsets.US_ASCII).trimEnd('\r', '\n')
            sentCommands.add(cmd)
            val reply = responder(cmd)
            if (reply != null) {
                rxQueue.addLast(reply.toByteArray(Charsets.US_ASCII))
            }
        }

        override suspend fun read(timeoutMs: Long): ByteArray {
            return if (rxQueue.isEmpty()) {
                onReadTimeout(timeoutMs)
                ByteArray(0)
            } else {
                rxQueue.removeFirst()
            }
        }
    }

    @Test
    fun `fast-wake skips ATZ when adapter responds to CR and ATI`() = runTest {
        val transport = FakeObdTransport { cmd ->
            when (cmd) {
                "" -> ">"
                "ATI" -> "ELM327 v2.3\r\r>"
                else -> "OK\r\r>"
            }
        }
        val session = Elm327Session(transport)
        val version = session.initialize()

        assertEquals("ELM327 v2.3", version)
        assertTrue(session.isInitialized)
        assertFalse("ATZ must not be sent when Fast-Wake succeeds", transport.sentCommands.contains("ATZ"))
        assertTrue(transport.sentCommands.contains("ATSP6"))
        assertTrue(transport.sentCommands.contains("ATCFC1"))
    }

    @Test
    fun `falls back to ATZ when fast-wake times out`() = runTest {
        var virtualTime = 0L
        val transport = FakeObdTransport(
            onReadTimeout = { waited -> virtualTime += waited }
        ) { cmd ->
            when (cmd) {
                "" -> null // simulate timeout during fast-wake
                "ATZ" -> "ELM327 v2.3\r\r>"
                else -> "OK\r\r>"
            }
        }
        val session = Elm327Session(transport, clockMs = { virtualTime })
        val version = session.initialize()

        assertEquals("ELM327 v2.3", version)
        assertTrue("ATZ must be sent when Fast-Wake misses", transport.sentCommands.contains("ATZ"))
    }

    @Test
    fun `caches active ECU TX and RX headers to avoid redundant AT commands`() = runTest {
        val transport = FakeObdTransport { "OK\r\r>" }
        val session = Elm327Session(transport)

        session.selectEcu("6B4", "694")
        session.selectEcu("6B4", "694") // duplicate -> should not resend
        session.selectEcu("6A2", "682")

        assertEquals(
            listOf("ATSH6B4", "ATFCSH6B4", "ATCRA694", "ATSH6A2", "ATFCSH6A2", "ATCRA682"),
            transport.sentCommands
        )
    }
}
