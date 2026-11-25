package com.elmdash.core.telemetry

import com.elmdash.core.protocol.EcuDiagnosticReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class HealthRating {
    GOOD,
    ATTENTION,
    CHECK,
    NA
}

data class HealthCheckItem(
    val id: String,
    val title: String,
    val valueText: String,
    val rating: HealthRating,
    val basis: String
)

data class CellUnderLoadEntry(
    val cellNumber: Int, // 1-based (1..108)
    val sagMvPer100A: Double,
    val cellSohPct: Double?
)

data class BmsMetadata(
    val manufactureDate: String? = null, // YYYY-MM-DD from 0xF18B
    val hardwareVersion: String? = null, // ASCII from 0xF193
    val softwareVersion: String? = null, // ASCII from 0xF195
    val serialNumber: String? = null,    // ASCII/Hex from 0xF18C (unique per physical car!)
    val vin: String? = null,             // ASCII from 0xF190 if exposed by ECU
    val readAtMs: Long = 0L
)

data class EcuLinkTestResult(
    val ecuId: String,
    val name: String,
    val txHeader: String,
    val rxHeader: String,
    val reachable: Boolean,
    val latencyMs: Long,
    val note: String = ""
)

data class ChargingCurvePoint(
    val socPct: Int,
    val powerKw: Double,
    val batteryTempC: Double?
)

data class BatteryHealthState(
    val checks: List<HealthCheckItem> = emptyList(),
    val restSpreadMv: Double? = null,
    val restSampleCount: Int = 0,
    val loadSampleCount: Int = 0,
    val maxObservedCurrentA: Double = 0.0,
    val worstLoadSagMvPer100A: Double? = null,
    val medianLoadSagMvPer100A: Double? = null,
    val cellsUnderLoad: List<CellUnderLoadEntry> = emptyList(),
    val cellSohAvgPct: Double? = null,
    val wearPer10kKmPct: Double? = null,
    val wearPerYearPct: Double? = null,
    val batteryAgeYears: Double? = null,
    val aux12vSocPct: Int? = null,
    val fullCycleEquivalents: Double? = null,
    val bmsMetadata: BmsMetadata = BmsMetadata(),
    val chargingCurve: List<ChargingCurvePoint> = emptyList()
)

/**
 * Continuous EV battery health analyzer implementing EscoEvObdScanner's 6-point BatteryChecks,
 * under-load per-cell internal resistance sag (mV / 100 A), wear-rate estimation, and evDash-style
 * charging curve tracking.
 */
class BatteryHealthAnalyzer {

    private val lock = Any()
    private val _state = MutableStateFlow(BatteryHealthState())
    val state: StateFlow<BatteryHealthState> = _state.asStateFlow()

    // Rest baseline offsets (cell_i - packAvgCell) accumulated when |I| <= 5 A
    private var restOffsetSumV: DoubleArray? = null
    private var restSamples: Int = 0
    private var lastRestSpreadMv: Double? = null

    // Under-load sag per cell normalized to 100 A accumulated when |I| >= 30 A
    private var loadSagSumMvPer100A: DoubleArray? = null
    private var loadSamples: Int = 0
    private var maxLoadCurrentA: Double = 0.0
    private var minTempDuringLoadC: Double? = null

    // Charging curve map: soc% (0..100) -> point
    private val chargeCurveMap = sortedMapOf<Int, ChargingCurvePoint>()

    private var bmsMetadata: BmsMetadata = BmsMetadata()
    private var latestDtcReports: List<EcuDiagnosticReport> = emptyList()

    fun updateBmsMetadata(meta: BmsMetadata) {
        synchronized(lock) {
            bmsMetadata = meta
            _state.value = _state.value.copy(bmsMetadata = meta)
        }
    }

    fun updateDtcReports(reports: List<EcuDiagnosticReport>, snapshot: TelemetrySnapshot) {
        synchronized(lock) {
            latestDtcReports = reports
            recomputeLocked(snapshot)
        }
    }

    fun onSnapshot(snapshot: TelemetrySnapshot) {
        synchronized(lock) {
            val currentA = snapshot.getValue("hv_current")
            val cells = snapshot.getArray("cell_voltages")
            val minTempC = snapshot.getValue("battery_temp_min") ?: snapshot.getValue("battery_temp")
            val powerKw = snapshot.getValue("hv_power")
            val soc = snapshot.getValue("soc")
            val speed = snapshot.getValue("speed") ?: 0.0

            // Track charging curve when charging (speed < 5 km/h & power < -1.5 kW)
            if (soc != null && powerKw != null && speed < 5.0 && powerKw < -1.5) {
                val socBucket = soc.toInt().coerceIn(0, 100)
                val chargeKw = -powerKw
                val existing = chargeCurveMap[socBucket]
                if (existing == null || chargeKw > existing.powerKw) {
                    chargeCurveMap[socBucket] = ChargingCurvePoint(socBucket, chargeKw, minTempC)
                }
            }

            if (cells != null && cells.size >= 12 && currentA != null) {
                val n = cells.size
                val avgV = cells.sum() / n
                val absI = abs(currentA)
                if (absI > maxLoadCurrentA) maxLoadCurrentA = absI

                if (absI <= 5.0) {
                    // At rest -> update rest cell baseline & spread
                    val sumArr = restOffsetSumV?.takeIf { it.size == n } ?: DoubleArray(n).also {
                        restOffsetSumV = it
                        restSamples = 0
                    }
                    for (i in 0 until n) {
                        sumArr[i] += (cells[i] - avgV)
                    }
                    restSamples++
                    lastRestSpreadMv = ((cells.max() - cells.min()) * 1000.0).coerceAtLeast(0.0)
                } else if (absI >= 30.0 && restSamples >= 1 && restOffsetSumV?.size == n) {
                    // Under load -> measure excess sag beyond rest offset, normalized to 100 A
                    val baseSum = restOffsetSumV!!
                    val sagSum = loadSagSumMvPer100A?.takeIf { it.size == n } ?: DoubleArray(n).also {
                        loadSagSumMvPer100A = it
                        loadSamples = 0
                    }
                    // Positive sag = cell drops more than pack average under discharge (or rises more under regen)
                    val sign = if (currentA > 0) 1.0 else -1.0
                    for (i in 0 until n) {
                        val restOffset = baseSum[i] / restSamples
                        val loadOffset = cells[i] - avgV
                        val excessDropV = (restOffset - loadOffset) * sign
                        val sagMvPer100A = (excessDropV * 1000.0) * (100.0 / absI)
                        sagSum[i] += sagMvPer100A
                    }
                    loadSamples++
                    if (minTempC != null) {
                        minTempDuringLoadC = minTempDuringLoadC?.let { min(it, minTempC) } ?: minTempC
                    }
                }
            }

            recomputeLocked(snapshot)
        }
    }

    private fun recomputeLocked(snapshot: TelemetrySnapshot) {
        val soh = snapshot.getValue("soh")
        val packV = snapshot.getValue("hv_voltage")
        val isoKohm = listOfNotNull(
            snapshot.getValue("hv_isolation_kohm"),
            snapshot.getValue("isolation_pos_kohm"),
            snapshot.getValue("isolation_neg_kohm")
        ).filter { it > 0.0 }.minOrNull()
        val tempMin = snapshot.getValue("battery_temp_min") ?: snapshot.getArray("temp_probes")?.minOrNull()
        val tempMax = snapshot.getValue("battery_temp_max") ?: snapshot.getArray("temp_probes")?.maxOrNull()
        val tempSpread = snapshot.getValue("battery_temp_delta")
            ?: if (tempMin != null && tempMax != null) max(0.0, tempMax - tempMin) else null
        val odoKm = snapshot.getValue("odometer")
        val aux12v = snapshot.getValue("aux_12v_voltage")
        val cellSohArr = snapshot.getArray("cell_soh")
        val cellSohAvg = cellSohArr?.takeIf { it.isNotEmpty() }?.average()
        val totalDischarged = snapshot.getValue("total_energy_discharged")

        // Under-load cell sag ranking
        val underLoadList = if (loadSamples > 0 && loadSagSumMvPer100A != null) {
            val arr = loadSagSumMvPer100A!!
            List(arr.size) { idx ->
                CellUnderLoadEntry(
                    cellNumber = idx + 1,
                    sagMvPer100A = (arr[idx] / loadSamples),
                    cellSohPct = cellSohArr?.getOrNull(idx)
                )
            }.sortedByDescending { it.sagMvPer100A }
        } else {
            emptyList()
        }

        val worstSag = underLoadList.firstOrNull()?.sagMvPer100A?.coerceAtLeast(0.0)
        val medianSag = if (underLoadList.isNotEmpty()) {
            val sortedAbs = underLoadList.map { abs(it.sagMvPer100A) }.sorted()
            sortedAbs[sortedAbs.size / 2]
        } else null

        // Build 6-Point EscoEvObdScanner BatteryChecks
        val checks = mutableListOf<HealthCheckItem>()

        // 1. SOH
        checks += HealthCheckItem(
            id = "soh",
            title = "Akku-Gesundheit (SoH)",
            valueText = soh?.let { String.format("%.1f %%", it) } ?: "--",
            rating = when {
                soh == null -> HealthRating.NA
                soh >= 90.0 -> HealthRating.GOOD
                soh >= 80.0 -> HealthRating.ATTENTION
                else -> HealthRating.CHECK
            },
            basis = "Gut ≥ 90 % · Achtung 80–90 % · Prüfen < 80 % (Stellantis Garantie: 70 % / 8 J. / 160.000 km)"
        )

        // 2. Cell Spread at Rest (|I| <= 5 A)
        checks += HealthCheckItem(
            id = "rest_spread",
            title = "Zell-Drift in Ruhe (≤ 5 A)",
            valueText = lastRestSpreadMv?.let { String.format("%.0f mV", it) } ?: "Warte auf Ruhe (≤ 5 A)",
            rating = when {
                lastRestSpreadMv == null -> HealthRating.NA
                lastRestSpreadMv!! < 30.0 -> HealthRating.GOOD
                lastRestSpreadMv!! < 80.0 -> HealthRating.ATTENTION
                else -> HealthRating.CHECK
            },
            basis = "Gut < 30 mV · Achtung 30–80 mV · Prüfen ≥ 80 mV (Midtronics 2025; nur bei ≤ 5 A)"
        )

        // 3. Cells Under Load (mV / 100 A)
        val coldLoadCap = (minTempDuringLoadC ?: 20.0) < 10.0
        val loadRating = when {
            worstSag == null -> HealthRating.NA
            worstSag < 5.0 -> HealthRating.GOOD
            worstSag < 15.0 -> HealthRating.ATTENTION
            else -> if (coldLoadCap) HealthRating.ATTENTION else HealthRating.CHECK
        }
        checks += HealthCheckItem(
            id = "load_sag",
            title = "Zellen unter Last (mV / 100 A)",
            valueText = worstSag?.let {
                val cellNo = underLoadList.firstOrNull()?.cellNumber ?: 0
                String.format("Max %.1f mV (#%d)", it, cellNo)
            } ?: "Warte auf Fahrt (≥ 30 A)",
            rating = loadRating,
            basis = "Gut < 5 · Achtung 5–15 · Prüfen ≥ 15 mV/100 A (Innenwiderstand; unter 10 °C max. Achtung)"
        )

        // 4. Temperature Spread
        checks += HealthCheckItem(
            id = "temp_spread",
            title = "Temperatur-Spreizung",
            valueText = tempSpread?.let { String.format("%.1f °C", it) } ?: "--",
            rating = when {
                tempSpread == null -> HealthRating.NA
                tempSpread < 5.0 -> HealthRating.GOOD
                tempSpread < 10.0 -> HealthRating.ATTENTION
                else -> HealthRating.CHECK
            },
            basis = "Gut < 5 °C · Achtung 5–10 °C · Prüfen ≥ 10 °C (Thermomanagement-Ziel < 5 °C)"
        )

        // 5. HV Insulation (UNECE R100: 500 Ohm/V)
        val legalMinKohm = ((packV ?: 400.0) * 0.5).coerceAtLeast(100.0) // 500 Ohm/V in kOhm
        val goodMinKohm = max(500.0, legalMinKohm)
        checks += HealthCheckItem(
            id = "insulation",
            title = "HV-Isolation (UNECE R100)",
            valueText = isoKohm?.let { String.format("%.0f kΩ", it) } ?: "--",
            rating = when {
                isoKohm == null -> HealthRating.NA
                isoKohm >= goodMinKohm -> HealthRating.GOOD
                isoKohm >= legalMinKohm -> HealthRating.ATTENTION
                else -> HealthRating.CHECK
            },
            basis = "Gut ≥ ${goodMinKohm.toInt()} kΩ · UNECE R100 Grenzwert: ≥ ${legalMinKohm.toInt()} kΩ (500 Ω/V)"
        )

        // 6. Battery ECU Faults
        val bmsReport = latestDtcReports.firstOrNull { it.ecuKey.equals("BMS", ignoreCase = true) }
        val bmsActive = bmsReport?.dtcs?.count { it.isActive } ?: 0
        val bmsPast = bmsReport?.dtcs?.count { !it.isActive } ?: 0
        checks += HealthCheckItem(
            id = "bms_faults",
            title = "BMS-Steuergerätefehler",
            valueText = when {
                bmsReport == null -> "Noch nicht gescannt"
                bmsActive > 0 -> "$bmsActive aktiv"
                bmsPast > 0 -> "$bmsPast gespeichert"
                else -> "Keine Fehler"
            },
            rating = when {
                bmsReport == null -> HealthRating.NA
                bmsActive > 0 -> HealthRating.CHECK
                bmsPast > 0 -> HealthRating.ATTENTION
                else -> HealthRating.GOOD
            },
            basis = "Gut: keine · Achtung: vergangen/gespeichert · Prüfen: aktiver BMS-Fehler"
        )

        // Wear rate per 10,000 km & per year
        val wearPer10k = if (soh != null && odoKm != null && odoKm >= 500.0) {
            ((100.0 - soh).coerceAtLeast(0.0) / (odoKm / 10_000.0))
        } else null

        val ageYears = parseAgeYears(bmsMetadata.manufactureDate)
        val wearPerYear = if (soh != null && ageYears != null && ageYears >= 0.2) {
            ((100.0 - soh).coerceAtLeast(0.0) / ageYears)
        } else null

        // 12V Aux Battery SoC % (evDash / BU-903)
        val aux12vSoc = aux12v?.let { v ->
            val minV = if ((snapshot.getValue("speed") ?: 0.0) > 1.0 || v > 13.1) 12.8 else 11.6
            val maxV = if ((snapshot.getValue("speed") ?: 0.0) > 1.0 || v > 13.1) 14.8 else 12.8
            (((v - minV) / (maxV - minV)) * 100.0).toInt().coerceIn(0, 100)
        }

        val fullCycles = totalDischarged?.let { it / 46.3 }

        _state.value = BatteryHealthState(
            checks = checks,
            restSpreadMv = lastRestSpreadMv,
            restSampleCount = restSamples,
            loadSampleCount = loadSamples,
            maxObservedCurrentA = maxLoadCurrentA,
            worstLoadSagMvPer100A = worstSag,
            medianLoadSagMvPer100A = medianSag,
            cellsUnderLoad = underLoadList,
            cellSohAvgPct = cellSohAvg,
            wearPer10kKmPct = wearPer10k,
            wearPerYearPct = wearPerYear,
            batteryAgeYears = ageYears,
            aux12vSocPct = aux12vSoc,
            fullCycleEquivalents = fullCycles,
            bmsMetadata = bmsMetadata,
            chargingCurve = chargeCurveMap.values.toList()
        )
    }

    private fun parseAgeYears(isoDate: String?): Double? {
        if (isoDate.isNullOrBlank()) return null
        val parts = isoDate.split("-")
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        if (y !in 2018..2035 || m !in 1..12 || d !in 1..31) return null
        val cal = java.util.Calendar.getInstance()
        cal.set(y, m - 1, d, 0, 0, 0)
        val diffMs = System.currentTimeMillis() - cal.timeInMillis
        if (diffMs <= 0L) return null
        return diffMs / (365.25 * 24.0 * 3600.0 * 1000.0)
    }
}
