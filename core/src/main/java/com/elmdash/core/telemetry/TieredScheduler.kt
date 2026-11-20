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
import com.elmdash.core.protocol.ValueType
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

    /**
     * Reads BMS manufacture date (0xF18B BCD), hardware version (0xF193 ASCII),
     * and software version (0xF195 ASCII) using read-only UDS Service 0x22.
     */
    suspend fun readBmsMetadata(): BmsMetadata {
        val session = activeSession ?: throw IOException("Keine aktive OBD2-Verbindung")
        val bms = registry.getEcu("BMS") ?: return BmsMetadata()
        session.selectEcu(bms.txHeader, bms.rxHeader)

        var mfgDate: String? = null
        var hwVer: String? = null
        var swVer: String? = null

        runCatching {
            val raw = session.sendCommand("22F18B", timeoutMs = 1500L)
            val res = IsoTpAssembler.assemble(raw, bms.rxHeader)
            if (res is IsoTpResult.Success && res.payload.size >= 7 && (res.payload[0].toInt() and 0xFF) == 0x62) {
                // 4-byte BCD date: YYYYMMDD
                val b0 = res.payload[3].toInt() and 0xFF
                val b1 = res.payload[4].toInt() and 0xFF
                val b2 = res.payload[5].toInt() and 0xFF
                val b3 = res.payload[6].toInt() and 0xFF
                mfgDate = String.format("%02X%02X-%02X-%02X", b0, b1, b2, b3)
            }
        }
        runCatching {
            val raw = session.sendCommand("22F193", timeoutMs = 1500L)
            val res = IsoTpAssembler.assemble(raw, bms.rxHeader)
            if (res is IsoTpResult.Success && res.payload.size > 3 && (res.payload[0].toInt() and 0xFF) == 0x62) {
                hwVer = String(res.payload.copyOfRange(3, res.payload.size), Charsets.US_ASCII)
                    .filter { it in ' '..'~' }.trim().ifEmpty { null }
            }
        }
        runCatching {
            val raw = session.sendCommand("22F195", timeoutMs = 1500L)
            val res = IsoTpAssembler.assemble(raw, bms.rxHeader)
            if (res is IsoTpResult.Success && res.payload.size > 3 && (res.payload[0].toInt() and 0xFF) == 0x62) {
                swVer = String(res.payload.copyOfRange(3, res.payload.size), Charsets.US_ASCII)
                    .filter { it in ' '..'~' }.trim().ifEmpty { null }
            }
        }
        return BmsMetadata(
            manufactureDate = mfgDate,
            hardwareVersion = hwVer,
            softwareVersion = swVer,
            readAtMs = System.currentTimeMillis()
        )
    }

    /**
     * Safe read-only ECU link test (UDS 0x3E TesterPresent with 0x22 F186 fallback)
     * across catalogued Stellantis e-CMP ECUs (never sweeps below 0x600).
     */
    suspend fun runEcuLinkTest(): List<EcuLinkTestResult> {
        val session = activeSession ?: throw IOException("Keine aktive OBD2-Verbindung")
        val catalogTargets = listOf(
            Triple("BMS", "Akku-Management (BMS)", "6B4" to "694"),
            Triple("VCU", "Fahrzeug-Steuergerät (VCU)", "6A2" to "682"),
            Triple("MCU", "Inverter & E-Motor (MCU)", "6A6" to "686"),
            Triple("OBC", "On-Board Charger & DC-DC", "590" to "58F"),
            Triple("ABS", "ABS / ESP Bremsregelung", "6AD" to "68D"),
            Triple("EPS", "Elektrische Servolenkung", "6B5" to "695"),
            Triple("CVM", "Frontkamera / Assistenz", "74A" to "64A"),
            Triple("AVAS", "Fußgänger-Warnton (AVAS)", "5B0" to "5AF")
        )

        val results = mutableListOf<EcuLinkTestResult>()
        for ((id, label, hdrPair) in catalogTargets) {
            val (tx, rx) = hdrPair
            val t0 = System.currentTimeMillis()
            var reachable = false
            var note = "Keine Antwort"
            try {
                session.selectEcu(tx, rx)
                val raw3e = session.sendCommand("3E00", timeoutMs = 800L)
                val res3e = IsoTpAssembler.assemble(raw3e, rx)
                if (res3e is IsoTpResult.Success && (res3e.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x7E)) {
                    reachable = true
                    note = "TesterPresent 0x7E OK"
                } else {
                    val rawF186 = session.sendCommand("22F186", timeoutMs = 800L)
                    val resF186 = IsoTpAssembler.assemble(rawF186, rx)
                    if (resF186 is IsoTpResult.Success && (resF186.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x62)) {
                        reachable = true
                        note = "Session 0xF186 OK"
                    }
                }
            } catch (e: Exception) {
                note = e.message ?: "Timeout"
            }
            val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            results += EcuLinkTestResult(
                ecuId = id,
                name = label,
                txHeader = tx,
                rxHeader = rx,
                reachable = reachable,
                latencyMs = elapsed,
                note = note
            )
        }
        return results
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
        var consecutiveTotalMisses = 0
        var consecutiveTimeouts = 0

        // Per-PID cooldown: sleeping ECUs (e.g. OBC while driving) or unsupported DIDs
        // back off for 60 s after 3 misses instead of spamming the bus or disconnecting!
        val pidMissCounts = HashMap<String, Int>()
        val pidCooldownUntilMs = HashMap<String, Long>()

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

            val activeDuePids = duePids.filter { pid ->
                val until = pidCooldownUntilMs[pid.id] ?: 0L
                now >= until
            }

            if (activeDuePids.isEmpty()) {
                delay(25L)
                continue
            }

            // ECU-Grouping: query all due PIDs for one ECU before switching ATSH/ATFCSH/ATCRA
            val groupedByEcu = activeDuePids.groupBy { it.ecu }
            for ((ecuKey, pids) in groupedByEcu) {
                val ecu = registry.getEcu(ecuKey) ?: continue
                session.selectEcu(ecu.txHeader, ecu.rxHeader)

                for (pid in pids) {
                    val cmd = pid.buildRequestCommand()
                    val isMultiFrame = pid.expectedFrames == 0 || pid.valueType == ValueType.ARRAY
                    val cmdTimeoutMs = if (isMultiFrame) 4500L else 1800L

                    val raw = try {
                        val resp = session.sendCommand(cmd, timeoutMs = cmdTimeoutMs)
                        consecutiveTimeouts = 0
                        resp
                    } catch (e: IOException) {
                        val isPromptTimeout = e.message?.contains("timeout", ignoreCase = true) == true
                        if (!isPromptTimeout) {
                            // Genuine socket / BLE / SPP link drop -> reconnect immediately
                            throw e
                        }
                        consecutiveTimeouts++
                        rawLog?.append(ObdRawLogEntry.Direction.ERROR, "PID ${pid.id}: timeout #$consecutiveTimeouts")
                        session.resync()
                        if (consecutiveTimeouts >= 3) {
                            throw IOException("ELM327 not responding ($consecutiveTimeouts consecutive timeouts)", e)
                        }
                        continue
                    }

                    // Check for fatal bus/adapter errors vs normal ECU NO DATA
                    val upperRaw = raw.uppercase()
                    val isFatalBusError = upperRaw.contains("UNABLE TO CONNECT") ||
                        upperRaw.contains("BUS INIT") ||
                        upperRaw.contains("CAN ERROR") ||
                        upperRaw.contains("BUS ERROR")

                    when (val assembled = IsoTpAssembler.assemble(raw, ecu.rxHeader)) {
                        is IsoTpResult.Success -> {
                            consecutiveTotalMisses = 0
                            pidMissCounts.remove(pid.id)
                            pidCooldownUntilMs.remove(pid.id)
                            pollsInWindow++
                            when (val parsed = UdsParser.parse(assembled.payload, pid)) {
                                is UdsParseResult.Scalar -> {
                                    if (parsed.value.isFinite()) {
                                        repository.updateScalar(pid.id, parsed.value)
                                    }
                                }
                                is UdsParseResult.ArrayValue -> {
                                    val clean = parsed.values.filter { it.isFinite() }
                                    if (clean.isNotEmpty()) {
                                        repository.updateArray(pid.id, clean)
                                    }
                                }
                                is UdsParseResult.NegativeResponse -> {
                                    // Normal UDS 0x7F (e.g. 0x31 unsupported DID or 0x22 wrong state):
                                    // never disconnect! Just cool down this PID for 60 s.
                                    val misses = (pidMissCounts[pid.id] ?: 0) + 1
                                    pidMissCounts[pid.id] = misses
                                    if (misses >= 3) {
                                        pidCooldownUntilMs[pid.id] = System.currentTimeMillis() + 60_000L
                                    }
                                    rawLog?.append(
                                        ObdRawLogEntry.Direction.ERROR,
                                        "UDS NRC 0x${parsed.nrc.toString(16)} for ${pid.id} (miss #$misses)"
                                    )
                                }
                                is UdsParseResult.Error -> {
                                    rawLog?.append(ObdRawLogEntry.Direction.ERROR, parsed.reason)
                                    session.resync()
                                }
                            }
                        }

                        is IsoTpResult.Error -> {
                            val misses = (pidMissCounts[pid.id] ?: 0) + 1
                            pidMissCounts[pid.id] = misses
                            if (misses >= 3) {
                                // Back off sleeping ECU / unsupported PID for 60 s
                                pidCooldownUntilMs[pid.id] = System.currentTimeMillis() + 60_000L
                            }
                            rawLog?.append(
                                ObdRawLogEntry.Direction.ERROR,
                                "PID ${pid.id}: ${assembled.reason} (miss #$misses)"
                            )
                            if (isFatalBusError) {
                                consecutiveTotalMisses += 5
                                session.resync()
                            } else if (!upperRaw.contains("NO DATA")) {
                                // Only resync on framing/garbage errors, NOT on clean NO DATA
                                session.resync()
                                consecutiveTotalMisses++
                            } else {
                                consecutiveTotalMisses++
                            }
                            if (consecutiveTotalMisses >= 25) {
                                throw IOException("No valid OBD responses ($consecutiveTotalMisses consecutive misses)")
                            }
                        }
                    }
                }
            }

            runCatching { onPollCycle?.invoke(repository.snapshot.value.values) }

            val windowElapsed = System.currentTimeMillis() - pollWindowStartMs
            if (windowElapsed >= 1000L) {
                repository.updatePollRate(pollsInWindow * 1000.0 / windowElapsed)
                pollsInWindow = 0
                pollWindowStartMs = System.currentTimeMillis()
            }

            if (onValuesUpdated != null && System.currentTimeMillis() - lastCachePersistMs >= 5000L) {
                runCatching { onValuesUpdated?.invoke(repository.snapshot.value.values) }
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
