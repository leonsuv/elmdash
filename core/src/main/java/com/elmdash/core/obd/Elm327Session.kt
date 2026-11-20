package com.elmdash.core.obd

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * ELM327 v2.3 session controller with:
 * - Fast-Wake (\r + ATI before falling back to slow ATZ)
 * - High-throughput CAN init (ATE0, ATL0, ATS0, ATSP6, ATCAF1, ATCFC1, ATAT2, ATST0A)
 * - Prompt ('>') framing parser
 * - Active ECU header (ATSH) and RX filter (ATCRA) caching
 */
class Elm327Session(
    private val transport: ObdTransport,
    private val rawLog: ObdRawLogBuffer? = null,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val rxAccumulator = StringBuilder()

    private var initialized = false
    private var activeTxHeader: String? = null
    private var activeRxHeader: String? = null

    val isInitialized: Boolean
        get() = initialized

    suspend fun initialize(): String = mutex.withLock {
        rxAccumulator.clear()
        activeTxHeader = null
        activeRxHeader = null
        initialized = false

        // 1. Fast-Wake: send bare CR + ATI; only fall back to ATZ if adapter doesn't answer cleanly.
        val version = tryFastWake() ?: run {
            rawLog?.append(ObdRawLogEntry.Direction.INFO, "Fast-Wake missed; falling back to ATZ")
            drainBufferLocked()
            sendCommandLocked("ATZ", timeoutMs = 2500L)
        }

        // 2. High-throughput Corsa-e / e-CMP ISO 15765-4 CAN (11-bit, 500 kbaud) init
        for (cmd in INIT_COMMANDS) {
            val resp = sendCommandLocked(cmd, timeoutMs = 1000L)
            if (resp.contains("?", ignoreCase = true) || resp.contains("ERROR", ignoreCase = true)) {
                rawLog?.append(ObdRawLogEntry.Direction.ERROR, "Init warning on $cmd: $resp")
            }
        }

        initialized = true
        rawLog?.append(ObdRawLogEntry.Direction.INFO, "ELM327 initialized: $version")
        version
    }

    private suspend fun tryFastWake(): String? {
        return try {
            // Wake adapter from idle without full reset
            sendCommandLocked("", timeoutMs = 800L)
            val ati = sendCommandLocked("ATI", timeoutMs = 800L)
            if (ati.contains("ELM327", ignoreCase = true)) ati else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Drains any stray late-arriving bytes from the transport to prevent 1-frame command/response desync.
     */
    suspend fun resync() = mutex.withLock {
        drainBufferLocked()
    }

    private suspend fun drainBufferLocked() {
        rxAccumulator.clear()
        while (true) {
            val stray = try {
                transport.read(40L)
            } catch (_: Exception) {
                ByteArray(0)
            }
            if (stray.isEmpty()) break
        }
    }

    /**
     * Selects ECU TX header (ATSH) and optional RX filter (ATCRA) only if changed.
     */
    suspend fun selectEcu(txHeader: String, rxHeader: String? = null) = mutex.withLock {
        val normTx = txHeader.uppercase()
        val normRx = rxHeader?.uppercase()?.takeIf { it.isNotBlank() }

        if (activeTxHeader != normTx) {
            sendCommandLocked("ATSH$normTx", timeoutMs = 800L)
            // Stellantis e-CMP uses non-standard RX IDs (e.g. 6B4->694, 6A2->682),
            // so ISO-TP multi-frame flow control must explicitly target the active TX header.
            sendCommandLocked("ATFCSH$normTx", timeoutMs = 800L)
            activeTxHeader = normTx
        }
        if (activeRxHeader != normRx) {
            if (normRx != null) {
                sendCommandLocked("ATCRA$normRx", timeoutMs = 800L)
            } else {
                sendCommandLocked("ATCRA", timeoutMs = 800L)
            }
            activeRxHeader = normRx
        }
    }

    /**
     * Sends a single AT or UDS command and returns the cleaned response before '>'.
     */
    suspend fun sendCommand(command: String, timeoutMs: Long = 1500L): String = mutex.withLock {
        sendCommandLocked(command, timeoutMs)
    }

    fun resetState() {
        initialized = false
        activeTxHeader = null
        activeRxHeader = null
        rxAccumulator.clear()
    }

    private suspend fun sendCommandLocked(command: String, timeoutMs: Long): String {
        val wire = "$command\r"
        rawLog?.append(ObdRawLogEntry.Direction.TX, command.ifEmpty { "<CR>" })
        transport.write(wire.toByteArray(Charsets.US_ASCII))

        val deadline = clockMs() + timeoutMs
        while (true) {
            val promptIdx = rxAccumulator.indexOf('>')
            if (promptIdx >= 0) {
                val rawResponse = rxAccumulator.substring(0, promptIdx)
                rxAccumulator.delete(0, promptIdx + 1)
                val cleaned = cleanResponse(rawResponse, command)
                rawLog?.append(ObdRawLogEntry.Direction.RX, cleaned)
                return cleaned
            }

            val remaining = deadline - clockMs()
            if (remaining <= 0L) {
                val partial = rxAccumulator.toString()
                rxAccumulator.clear()
                rawLog?.append(ObdRawLogEntry.Direction.ERROR, "Timeout on '$command' (partial='$partial')")
                throw IOException("ELM327 timeout waiting for '>' on command '$command'")
            }

            val chunk = transport.read(remaining.coerceAtMost(250L))
            if (chunk.isNotEmpty()) {
                rxAccumulator.append(String(chunk, Charsets.US_ASCII))
            }
        }
    }

    private fun cleanResponse(raw: String, sentCommand: String): String {
        val normalized = raw
            .replace("\u0000", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val lines = normalized
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Strip echoed command if ATE1 was still active (e.g. during Fast-Wake / ATZ)
        val filtered = if (sentCommand.isNotEmpty() && lines.firstOrNull().equals(sentCommand, ignoreCase = true)) {
            lines.drop(1)
        } else {
            lines
        }
        return filtered.joinToString("\n")
    }

    companion object {
        val INIT_COMMANDS = listOf(
            "ATE0",         // Echo off
            "ATL0",         // Linefeeds off
            "ATS0",         // Spaces off (max throughput)
            "ATSP6",        // ISO 15765-4 CAN (11-bit, 500 kbaud)
            "ATCAF1",       // CAN auto formatting on
            "ATCFC1",       // CAN flow control on (required for ISO-TP multi-frame)
            "ATFCSD300000", // Flow Control Data: CTS, BS=0, STmin=0 (e-CMP non-standard RX IDs)
            "ATFCSM1",      // Flow Control Mode: user-defined header & data
            "ATAT2",        // Aggressive adaptive timing (auto-shortens wait on fast replies)
            "ATST32"        // 200ms max timeout (safe for slower e-CMP ECUs & multi-frame; fast replies return immediately via expectedFrames)
        )
    }
}
