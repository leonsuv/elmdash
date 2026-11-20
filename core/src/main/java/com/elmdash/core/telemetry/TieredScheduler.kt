package com.elmdash.core.telemetry

import com.elmdash.core.obd.Elm327Session
import com.elmdash.core.obd.ObdRawLogBuffer
import com.elmdash.core.obd.ObdRawLogEntry
import com.elmdash.core.obd.ObdTransport
import com.elmdash.core.protocol.DtcDecoder
import com.elmdash.core.protocol.EcuDiagnosticReport
import com.elmdash.core.protocol.IsoTpAssembler
import com.elmdash.core.protocol.IsoTpResult
import com.elmdash.core.protocol.PidDefinition
import com.elmdash.core.protocol.PidRegistry
import com.elmdash.core.protocol.PollingTier
import com.elmdash.core.protocol.UdsParseResult
import com.elmdash.core.protocol.UdsParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Tiered OBD2 polling engine:
 * - FAST (250 ms), MEDIUM (2500 ms), SLOW (30 s, plus immediate initial poll)
 * - Groups due PIDs by ECU header (ATSH + optional ATCRA) to minimize AT header switches
 * - Appends expected frame counts when defined
 * - Automatic backoff reconnect on transport drop (keeps cached values visible as stale)
 */
class TieredScheduler(
    private val registry: PidRegistry,
    private val repository: TelemetryRepository,
    private val rawLog: ObdRawLogBuffer? = null,
    private val fastIntervalMs: Long = 250L,
    private val mediumIntervalMs: Long = 2500L,
    private val slowIntervalMs: Long = 30_000L,
    private val onValuesUpdated: ((Map<String, Double>) -> Unit)? = null,
    private val onPollCycle: ((Map<String, Double>) -> Unit)? = null
) {
    private var pollingJob: Job? = null

    val isRunning: Boolean
        get() = pollingJob?.isActive == true

    @Volatile
    private var activeSession: Elm327Session? = null

    suspend fun sendDiagnosticCommand(command: String, timeoutMs: Long = 1500L): String {
        val session = activeSession ?: throw IOException("Keine aktive OBD2-Verbindung")
        return session.sendCommand(command.trim().uppercase(), timeoutMs)
    }

    /**
     * Scans all ECUs defined in corsa_e_pids.json using UDS Service 0x19 02 FF (ReadDTCInformation).
     */
    suspend fun scanAllEcuDtcs(
        onEcuProgress: ((String) -> Unit)? = null
    ): List<EcuDiagnosticReport> {
        val session = activeSession ?: throw IOException("Keine aktive OBD2-Verbindung")
        val reports = mutableListOf<EcuDiagnosticReport>()
        for ((ecuKey, ecu) in registry.catalog.ecus) {
            onEcuProgress?.invoke(ecu.name)
            try {
                session.selectEcu(ecu.txHeader, ecu.rxHeader)
                val raw = session.sendCommand("1902FF", timeoutMs = 2000L)
                when (val assembled = IsoTpAssembler.assemble(raw, ecu.rxHeader)) {
                    is IsoTpResult.Success -> {
                        val dtcs = DtcDecoder.decodeUds1902(assembled.payload, ecuKey, ecu.name)
                        reports.add(
                            EcuDiagnosticReport(
                                ecuKey = ecuKey,
                                ecuName = ecu.name,
                                txHeader = ecu.txHeader,
                                reachable = true,
                                dtcs = dtcs
                            )
                        )
                    }

                    is IsoTpResult.Error -> {
                        reports.add(
                            EcuDiagnosticReport(
                                ecuKey = ecuKey,
                                ecuName = ecu.name,
                                txHeader = ecu.txHeader,
                                reachable = false,
                                dtcs = emptyList(),
                                error = assembled.reason
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                reports.add(
                    EcuDiagnosticReport(
                        ecuKey = ecuKey,
                        ecuName = ecu.name,
                        txHeader = ecu.txHeader,
                        reachable = false,
                        dtcs = emptyList(),
                        error = e.message
                    )
                )
            }
        }
        return reports
    }

    /**
     * Clears DTC memory on one ECU (or all ECUs when ecuKey == null) using UDS Service 0x14 FF FF FF.
     */
    suspend fun clearDtcs(ecuKey: String? = null): Boolean {
        val session = activeSession ?: throw IOException("Keine aktive OBD2-Verbindung")
        val targets = if (ecuKey != null) {
            listOfNotNull(registry.getEcu(ecuKey)?.let { ecuKey to it })
        } else {
            registry.catalog.ecus.entries.map { it.key to it.value }
        }
        var allOk = true
        for ((_, ecu) in targets) {
            try {
                session.selectEcu(ecu.txHeader, ecu.rxHeader)
                val raw = session.sendCommand("14FFFFFF", timeoutMs = 2000L)
                val assembled = IsoTpAssembler.assemble(raw, ecu.rxHeader)
                if (assembled !is IsoTpResult.Success || assembled.payload.firstOrNull()?.toInt()?.and(0xFF) != 0x54) {
                    allOk = false
                }
            } catch (_: Exception) {
                allOk = false
            }
        }
        return allOk
    }

    fun start(scope: CoroutineScope, transportFactory: () -> ObdTransport) {
        stop()
        pollingJob = scope.launch {
            var backoffMs = 500L
            while (isActive) {
                val transport = transportFactory()
                val session = Elm327Session(transport, rawLog)
                try {
                    repository.updateConnection(ConnectionState.CONNECTING, transport.name)
                    rawLog?.append(ObdRawLogEntry.Direction.INFO, "Connecting via ${transport.name}...")
                    transport.connect()
                    session.initialize()
                    activeSession = session
                    repository.updateConnection(ConnectionState.LIVE, transport.name)
                    backoffMs = 500L

                    runPollLoop(session)
                } catch (ce: CancellationException) {
                    activeSession = null
                    safeDisconnect(transport)
                    repository.updateConnection(ConnectionState.DISCONNECTED)
                    throw ce
                } catch (e: Exception) {
                    activeSession = null
                    rawLog?.append(ObdRawLogEntry.Direction.ERROR, "Link error: ${e.message}")
                    safeDisconnect(transport)
                    repository.updateConnection(
                        ConnectionState.RECONNECTING,
                        transport.name,
                        errorMessage = e.message
                    )
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2L).coerceAtMost(5000L)
                }
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun runPollLoop(session: Elm327Session) {
        var lastFastMs = 0L
        var lastMediumMs = 0L
        var lastSlowMs = 0L
        var consecutiveErrors = 0

        var pollWindowStartMs = System.currentTimeMillis()
        var pollsInWindow = 0
        var lastCachePersistMs = 0L

        while (true) {
            val now = System.currentTimeMillis()
            val duePids = ArrayList<PidDefinition>()

            if (now - lastFastMs >= fastIntervalMs) {
                duePids.addAll(registry.pidsForTier(PollingTier.FAST))
                lastFastMs = now
            }
            if (now - lastMediumMs >= mediumIntervalMs) {
                duePids.addAll(registry.pidsForTier(PollingTier.MEDIUM))
                lastMediumMs = now
            }
            if (now - lastSlowMs >= slowIntervalMs) {
                duePids.addAll(registry.pidsForTier(PollingTier.SLOW))
                lastSlowMs = now
            }

            if (duePids.isEmpty()) {
                delay(25L)
                continue
            }

            // ECU-Grouping: query all due PIDs for one ECU before switching ATSH/ATCRA
            val groupedByEcu = duePids.groupBy { it.ecu }
            for ((ecuKey, pids) in groupedByEcu) {
                val ecu = registry.getEcu(ecuKey) ?: continue
                session.selectEcu(ecu.txHeader, ecu.rxHeader)

                for (pid in pids) {
                    val cmd = pid.buildRequestCommand()
                    val raw = session.sendCommand(cmd, timeoutMs = 1500L)
                    when (val assembled = IsoTpAssembler.assemble(raw, ecu.rxHeader)) {
                        is IsoTpResult.Success -> {
                            consecutiveErrors = 0
                            pollsInWindow++
                            when (val parsed = UdsParser.parse(assembled.payload, pid)) {
                                is UdsParseResult.Scalar -> repository.updateScalar(pid.id, parsed.value)
                                is UdsParseResult.ArrayValue -> repository.updateArray(pid.id, parsed.values)
                                is UdsParseResult.NegativeResponse -> {
                                    rawLog?.append(
                                        ObdRawLogEntry.Direction.ERROR,
                                        "UDS NRC 0x${parsed.nrc.toString(16)} for ${pid.id}"
                                    )
                                }
                                is UdsParseResult.Error -> {
                                    rawLog?.append(ObdRawLogEntry.Direction.ERROR, parsed.reason)
                                    session.resync()
                                }
                            }
                        }

                        is IsoTpResult.Error -> {
                            consecutiveErrors++
                            rawLog?.append(
                                ObdRawLogEntry.Direction.ERROR,
                                "PID ${pid.id}: ${assembled.reason}"
                            )
                            session.resync()
                            if (consecutiveErrors >= 8) {
                                throw IOException("Too many consecutive OBD errors ($consecutiveErrors)")
                            }
                        }
                    }
                }
            }

            onPollCycle?.invoke(repository.snapshot.value.values)

            val windowElapsed = System.currentTimeMillis() - pollWindowStartMs
            if (windowElapsed >= 1000L) {
                repository.updatePollRate(pollsInWindow * 1000.0 / windowElapsed)
                pollsInWindow = 0
                pollWindowStartMs = System.currentTimeMillis()
            }

            if (onValuesUpdated != null && System.currentTimeMillis() - lastCachePersistMs >= 5000L) {
                onValuesUpdated.invoke(repository.snapshot.value.values)
                lastCachePersistMs = System.currentTimeMillis()
            }
        }
    }

    private suspend fun safeDisconnect(transport: ObdTransport) {
        try {
            transport.disconnect()
        } catch (_: Exception) {
        }
    }
}
