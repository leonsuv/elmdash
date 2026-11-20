package com.elmdash.core.obd

import com.elmdash.core.protocol.Endianness
import com.elmdash.core.protocol.PidDefinition
import com.elmdash.core.protocol.PidRegistry
import com.elmdash.core.protocol.ValueType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * In-app Stellantis e-CMP (Opel Corsa-e / Mokka-e) ELM327 v2.3 + UDS Demo Car transport.
 * Speaks the real ELM327/UDS/ISO-TP byte protocol driven exclusively by [PidRegistry]
 * so users without an OBD adapter can explore live telemetry, 108-cell health, under-load
 * cell sag, 54 temperature probes, DTC diagnostics, ECU link tests, and PDF reports.
 */
class DemoObdTransport(
    private val registry: PidRegistry
) : ObdTransport {

    override val name: String = "Demo-Auto (Stellantis e-CMP)"

    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    @Volatile
    private var isConnected: Boolean = false

    private val rxQueue = LinkedBlockingQueue<ByteArray>()
    private val txBuffer = StringBuilder()

    private var txHeader: String = "6B4"
    private var rxFilter: String? = null
    private var spaces: Boolean = false
    private var echo: Boolean = false

    private var startMs: Long = 0L
    private var lastTickMs: Long = 0L
    private var socPct: Double = 76.4
    private var odometerKm: Double = 28412.0
    private var lifetimeChargedKwh: Double = 4682.5
    private var lifetimeDischargedKwh: Double = 4398.2
    private val sohPct: Double = 95.375 // 1526 / 16 (realistic 1/16% BMS step)

    private val dtcStore = mutableMapOf<String, MutableList<ByteArray>>(
        "6B4" to mutableListOf(),
        "6A2" to mutableListOf(byteArrayOf(0xD2.toByte(), 0x08, 0x00, 0x04)),
        "590" to mutableListOf(byteArrayOf(0xD1.toByte(), 0x13, 0x87.toByte(), 0x09))
    )

    private val byRequest: Map<Triple<String, String, String>, List<PidDefinition>> by lazy {
        val map = mutableMapOf<Triple<String, String, String>, MutableList<PidDefinition>>()
        for (pid in registry.allPids) {
            val ecu = registry.getEcu(pid.ecu) ?: continue
            val key = Triple(ecu.txHeader.uppercase(), pid.service.uppercase(), pid.did.uppercase())
            map.getOrPut(key) { mutableListOf() }.add(pid)
        }
        map
    }

    override suspend fun connect() {
        _state.value = TransportState.CONNECTING
        rxQueue.clear()
        txBuffer.clear()
        startMs = System.currentTimeMillis()
        lastTickMs = startMs
        isConnected = true
        _state.value = TransportState.CONNECTED
    }

    override suspend fun write(data: ByteArray) {
        if (!isConnected) throw IOException("DemoObdTransport is not connected")
        txBuffer.append(String(data, Charsets.US_ASCII))
        while (true) {
            val idxR = txBuffer.indexOf('\r')
            val idxN = txBuffer.indexOf('\n')
            val cut = when {
                idxR >= 0 && idxN >= 0 -> min(idxR, idxN)
                idxR >= 0 -> idxR
                idxN >= 0 -> idxN
                else -> -1
            }
            if (cut < 0) break
            val line = txBuffer.substring(0, cut).trim()
            txBuffer.delete(0, cut + 1)
            handleCommand(line)
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray {
        if (!isConnected) throw IOException("DemoObdTransport is not connected")
        val chunk = rxQueue.poll(timeoutMs.coerceAtLeast(10L), TimeUnit.MILLISECONDS)
        return chunk ?: ByteArray(0)
    }

    override suspend fun disconnect() {
        isConnected = false
        _state.value = TransportState.DISCONNECTED
        rxQueue.clear()
    }

    private suspend fun handleCommand(rawCmd: String) {
        delay(3L) // realistic fast local response (~30-45 Hz)
        if (rawCmd.isEmpty()) {
            enqueue(">")
            return
        }
        val upper = rawCmd.uppercase().replace(" ", "")
        if (upper.startsWith("AT")) {
            val resp = handleAt(upper)
            enqueue("$resp\r\r>")
            return
        }

        val resp = handleUds(upper)
        enqueue("$resp\r\r>")
    }

    private fun handleAt(cmd: String): String = when {
        cmd == "ATZ" || cmd == "ATWS" || cmd == "ATI" -> "ELM327 v2.3 (Demo)"
        cmd == "ATE0" -> { echo = false; "OK" }
        cmd == "ATE1" -> { echo = true; "OK" }
        cmd == "ATS0" -> { spaces = false; "OK" }
        cmd == "ATS1" -> { spaces = true; "OK" }
        cmd == "ATL0" || cmd == "ATL1" || cmd == "ATH0" || cmd == "ATH1" -> "OK"
        cmd == "ATCAF0" || cmd == "ATCAF1" || cmd == "ATCFC0" || cmd == "ATCFC1" -> "OK"
        cmd.startsWith("ATAT") || cmd.startsWith("ATST") || cmd.startsWith("ATSP") -> "OK"
        cmd.startsWith("ATFCSH") || cmd.startsWith("ATFCSD") || cmd.startsWith("ATFCSM") -> "OK"
        cmd.startsWith("ATSH") && cmd.length >= 7 -> { txHeader = cmd.substring(4); "OK" }
        cmd == "ATCRA" -> { rxFilter = null; "OK" }
        cmd.startsWith("ATCRA") && cmd.length >= 8 -> { rxFilter = cmd.substring(5); "OK" }
        cmd == "ATRV" -> "14.3V"
        cmd == "ATDP" -> "ISO 15765-4 (CAN 11/500)"
        else -> "OK"
    }

    private fun handleUds(cmd: String): String {
        // 0x3E TesterPresent (ECU link test)
        if (cmd.startsWith("3E")) {
            return if (txHeader in KNOWN_DEMO_ECUS) formatIsoTp(byteArrayOf(0x7E, 0x00)) else "NO DATA"
        }

        // 0x19 ReadDTCInformation
        if (cmd.startsWith("1902") && cmd.length >= 6) {
            val records = dtcStore[txHeader].orEmpty()
            val payload = ByteArray(3 + records.size * 4)
            payload[0] = 0x59
            payload[1] = 0x02
            payload[2] = 0xFF.toByte()
            records.forEachIndexed { i, rec ->
                System.arraycopy(rec, 0, payload, 3 + i * 4, 4)
            }
            return formatIsoTp(payload)
        }

        // 0x14 ClearDiagnosticInformation
        if (cmd.startsWith("14") && cmd.length >= 8) {
            dtcStore[txHeader]?.clear()
            return formatIsoTp(byteArrayOf(0x54))
        }

        // 0x22 ReadDataByIdentifier
        if (cmd.startsWith("22") && cmd.length in 6..7) {
            val did = cmd.substring(2, 6)
            when (did) {
                "F186" -> return formatIsoTp(byteArrayOf(0x62, 0xF1.toByte(), 0x86.toByte(), 0x01))
                "F18B" -> return formatIsoTp(byteArrayOf(0x62, 0xF1.toByte(), 0x8B.toByte(), 0x20, 0x23, 0x05, 0x14))
                "F193" -> return formatIsoTp(byteArrayOf(0x62, 0xF1.toByte(), 0x93.toByte()) + "9842108880".toByteArray())
                "F195" -> return formatIsoTp(byteArrayOf(0x62, 0xF1.toByte(), 0x95.toByte()) + "9695412280".toByteArray())
            }

            val pids = byRequest[Triple(txHeader, "22", did)]
                ?: return formatIsoTp(byteArrayOf(0x7F, 0x22, 0x31))

            val state = computePhysics()
            val didBytes = byteArrayOf(
                did.substring(0, 2).toInt(16).toByte(),
                did.substring(2, 4).toInt(16).toByte()
            )
            val dataBytes = encodeGroup(pids, state)
            return formatIsoTp(byteArrayOf(0x62) + didBytes + dataBytes)
        }

        return "NO DATA"
    }

    private fun computePhysics(): DemoPhysics {
        val now = System.currentTimeMillis()
        val elapsedSec = (now - startMs) / 1000.0
        val dtSec = ((now - lastTickMs) / 1000.0).coerceIn(0.0, 2.0)
        lastTickMs = now

        // Cycle includes rest phases (for at-rest cell spread), acceleration (for under-load sag), and regen
        val cyclePos = elapsedSec % 48.0
        val speedKmh: Double
        val powerKw: Double
        when {
            cyclePos < 8.0 -> {
                // Rest / traffic light (|I| <= 5 A -> captures rest cell baseline!)
                speedKmh = 0.0
                powerKw = 0.75 + 0.15 * sin(elapsedSec * 0.6)
            }
            cyclePos < 22.0 -> {
                // Brisk acceleration up to 96 km/h (high load ~45..78 kW -> captures under-load cell sag!)
                val p = (cyclePos - 8.0) / 14.0
                speedKmh = 96.0 * sin(p * Math.PI * 0.5)
                powerKw = 18.0 + 58.0 * sin(p * Math.PI)
            }
            cyclePos < 34.0 -> {
                // Steady cruise ~85 km/h
                speedKmh = 85.0 + 4.0 * sin(elapsedSec * 0.4)
                powerKw = 14.5 + 3.0 * sin(elapsedSec * 0.7)
            }
            else -> {
                // Regenerative braking back to rest
                val p = (cyclePos - 34.0) / 14.0
                speedKmh = max(0.0, 85.0 * (1.0 - p))
                powerKw = if (speedKmh > 6.0) -28.0 * sin(p * Math.PI) else 0.8
            }
        }

        socPct = (socPct - (powerKw * dtSec / 3600.0) / 46.3 * 100.0).coerceIn(10.0, 99.0)
        odometerKm += speedKmh * (dtSec / 3600.0)
        if (powerKw > 0) lifetimeDischargedKwh += powerKw * dtSec / 3600.0
        else lifetimeChargedKwh += (-powerKw) * dtSec / 3600.0

        val ocv = 345.0 + (socPct / 100.0) * 65.0
        val hvVoltage = (ocv - powerKw * 0.17).coerceIn(310.0, 428.0)
        val hvCurrent = (powerKw * 1000.0) / hvVoltage
        val batteryTemp = 23.5 + 2.5 * sin(elapsedSec / 90.0)

        // Generate 108 cells with realistic rest offset + realistic extra IR sag on cells #33 & #101 under load
        val avgCellV = hvVoltage / 108.0
        val cells = List(108) { idx ->
            val restOffsetV = ((idx * 13 % 11) - 5) * 0.0012 // +/- 6 mV at rest
            val extraSagPer100A = when (idx) {
                32 -> 0.0068 // cell #33: 6.8 mV/100A
                100 -> 0.0054 // cell #101: 5.4 mV/100A
                else -> 0.0014 + ((idx * 7) % 5) * 0.0004
            }
            val loadDropV = (hvCurrent / 100.0) * extraSagPer100A
            (avgCellV + restOffsetV - loadDropV).coerceIn(2.8, 4.25)
        }

        val cellSoh = List(108) { idx ->
            when (idx) {
                32, 100 -> sohPct
                else -> min(100.0, sohPct + 1.1 + ((idx * 17) % 28) * 0.1)
            }
        }

        val tempProbes = List(54) { idx ->
            val mod = idx / 3
            (batteryTemp + sin(mod * 0.45) * 1.4 + ((idx % 3) - 1) * 0.4).roundToInt().toDouble()
        }

        val minCell = cells.min()
        val maxCell = cells.max()
        val minIdx = cells.indexOf(minCell) + 1
        val maxIdx = cells.indexOf(maxCell) + 1
        val motorKw = (powerKw - 0.9) * if (powerKw >= 0) 0.92 else 1.09
        val motorRpm = (speedKmh * 86.5).coerceIn(0.0, 14000.0)
        val motorTorque = if (motorRpm > 60.0) (motorKw * 9549.0 / motorRpm) else (motorKw * 18.0)

        return DemoPhysics(
            scalars = mapOf(
                "hv_voltage" to hvVoltage,
                "hv_current" to hvCurrent,
                "speed" to speedKmh,
                "motor_power" to motorKw,
                "motor_torque" to motorTorque.coerceIn(-150.0, 260.0),
                "motor_rpm" to motorRpm,
                "accel_pedal" to if (powerKw > 2.0) (powerKw / 90.0 * 100.0).coerceIn(0.0, 100.0) else 0.0,
                "soc" to socPct,
                "soc_raw" to (socPct * 0.94 + 3.0).coerceIn(3.0, 97.0),
                "available_energy" to (46.3 * (sohPct / 100.0) * (socPct / 100.0)),
                "battery_temp" to batteryTemp,
                "battery_temp_max" to (tempProbes.maxOrNull() ?: (batteryTemp + 2.0)),
                "battery_temp_min" to (tempProbes.minOrNull() ?: (batteryTemp - 1.5)),
                "battery_temp_delta" to ((tempProbes.maxOrNull() ?: 25.0) - (tempProbes.minOrNull() ?: 22.0)),
                "max_charge_power" to if (socPct < 35.0) 100.0 else max(22.0, 100.0 - (socPct - 35.0) * 1.2),
                "max_discharge_power" to 158.5,
                "aux_12v_voltage" to 14.32,
                "motor_temp" to (42.0 + abs(motorKw) * 0.35),
                "dcdc_temp" to 33.0,
                "obc_temp" to 28.0,
                "coolant_temp_hv" to (batteryTemp - 1.8),
                "coolant_temp_heater" to 35.5,
                "inverter_temp" to (35.0 + abs(powerKw) * 0.30),
                "dc_link_voltage" to (hvVoltage - 0.6),
                "ac_voltage" to 0.0,
                "aux_12v_current" to 21.0,
                "charge_cable_state" to 0.0,
                "soh" to sohPct,
                "cell_voltage_min" to minCell,
                "cell_voltage_max" to maxCell,
                "cell_voltage_avg" to (cells.sum() / cells.size),
                "lowest_cell_idx" to minIdx.toDouble(),
                "highest_cell_idx" to maxIdx.toDouble(),
                "hv_isolation_kohm" to 4850.0,
                "isolation_pos_kohm" to 4920.0,
                "isolation_neg_kohm" to 4780.0,
                "total_energy_charged" to lifetimeChargedKwh,
                "total_energy_discharged" to lifetimeDischargedKwh,
                "contactor_closes" to 3886.0,
                "time_above_55c" to 0.0,
                "ambient_temp" to 15.0,
                "odometer" to odometerKm,
                "drive_mode" to 6.0,
                "gear_selector" to if (speedKmh > 0.5) 3.0 else 0.0
            ),
            arrays = mapOf(
                "cell_voltages" to cells,
                "cell_soh" to cellSoh,
                "temp_probes" to tempProbes
            )
        )
    }

    private fun encodeGroup(pids: List<PidDefinition>, state: DemoPhysics): ByteArray {
        var totalSize = 0
        for (pid in pids) {
            val count = if (pid.valueType == ValueType.ARRAY) pid.elementCount else 1
            totalSize = max(totalSize, pid.byteOffset + count * pid.byteLength)
        }
        val buf = ByteArray(totalSize)
        for (pid in pids) {
            if (pid.valueType == ValueType.ARRAY) {
                val arr = state.arrays[pid.id] ?: continue
                arr.take(pid.elementCount).forEachIndexed { idx, v ->
                    writeEncoded(buf, pid.byteOffset + idx * pid.byteLength, v, pid)
                }
            } else {
                val v = state.scalars[pid.id] ?: continue
                writeEncoded(buf, pid.byteOffset, v, pid)
            }
        }
        return buf
    }

    private fun writeEncoded(buf: ByteArray, offset: Int, value: Double, pid: PidDefinition) {
        val clamped = value.coerceIn(pid.min, pid.max)
        var raw = ((clamped - pid.offset) / pid.scale).roundToLong()
        val bitCount = pid.byteLength * 8
        if (pid.signed) {
            val minV = -(1L shl (bitCount - 1))
            val maxV = (1L shl (bitCount - 1)) - 1L
            raw = raw.coerceIn(minV, maxV)
        } else {
            val maxV = if (bitCount >= 63) Long.MAX_VALUE else (1L shl bitCount) - 1L
            raw = raw.coerceIn(0L, maxV)
        }
        if (pid.endianness == Endianness.BIG) {
            for (i in 0 until pid.byteLength) {
                val shift = (pid.byteLength - 1 - i) * 8
                buf[offset + i] = ((raw ushr shift) and 0xFFL).toByte()
            }
        } else {
            for (i in 0 until pid.byteLength) {
                buf[offset + i] = ((raw ushr (i * 8)) and 0xFFL).toByte()
            }
        }
    }

    private fun formatIsoTp(payload: ByteArray): String {
        if (payload.size <= 7) {
            return payload.toHex()
        }
        val sb = StringBuilder()
        sb.append(String.format("%03X", payload.size)).append('\r')
        var idx = 0
        var seq = 0
        while (idx < payload.size) {
            val end = min(idx + 7, payload.size)
            sb.append(String.format("%X:", seq and 0xF))
            sb.append(payload.copyOfRange(idx, end).toHex())
            idx = end
            seq++
            if (idx < payload.size) sb.append('\r')
        }
        return sb.toString()
    }

    private fun ByteArray.toHex(): String = joinToString(if (spaces) " " else "") { "%02X".format(it.toInt() and 0xFF) }

    private fun enqueue(text: String) {
        rxQueue.offer(text.toByteArray(Charsets.US_ASCII))
    }

    private data class DemoPhysics(
        val scalars: Map<String, Double>,
        val arrays: Map<String, List<Double>>
    )

    companion object {
        private val KNOWN_DEMO_ECUS = setOf("6B4", "6A2", "6A6", "590", "6AD", "6B5", "74A", "5B0")
    }
}
