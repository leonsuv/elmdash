package com.elmdash.core.telemetry

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.tripDataStore by preferencesDataStore(name = "elmdash_trips_v2")

/**
 * Persistent, continuously running EV trip tracker with automatic odometer data-gap detection.
 */
@Serializable
data class TrackedTrip(
    val id: String,
    val name: String,
    val startedAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val isRunning: Boolean = true,
    val isPrimary: Boolean = false,
    val recordedDistanceKm: Double = 0.0,
    val missedGapKm: Double = 0.0,
    val gapCount: Int = 0,
    val netEnergyKwh: Double = 0.0,
    val regenEnergyKwh: Double = 0.0,
    val movingSeconds: Double = 0.0,
    val startOdometerKm: Double? = null,
    val lastOdometerKm: Double? = null,
    val startSoc: Double? = null,
    val lastSoc: Double? = null,
    val distanceSinceLastOdoKm: Double = 0.0,
    val vehicleId: String = ""
) {
    val totalDistanceKm: Double
        get() = recordedDistanceKm + missedGapKm

    val consumptionKwhPer100Km: Double
        get() = if (recordedDistanceKm >= 0.05) {
            (netEnergyKwh / recordedDistanceKm * 100.0).coerceIn(-30.0, 99.9)
        } else {
            0.0
        }

    val avgSpeedKmh: Double
        get() = if (movingSeconds >= 5.0) {
            (recordedDistanceKm / (movingSeconds / 3600.0)).coerceIn(0.0, 220.0)
        } else {
            0.0
        }

    val regenRatioPct: Double
        get() {
            val gross = netEnergyKwh + regenEnergyKwh
            return if (gross > 0.01) (regenEnergyKwh / gross * 100.0).coerceIn(0.0, 100.0) else 0.0
        }
}

/**
 * Manages unlimited concurrent, auto-saving, auto-continuing trips.
 * Detects missed driving segments (data gaps) automatically by comparing vehicle odometer jumps
 * against recorded telemetry distance.
 */
class TripTracker(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val _trips = MutableStateFlow(
        listOf(
            TrackedTrip(
                id = "trip_default_a",
                name = "Trip A (Aktuell)",
                isRunning = true,
                isPrimary = true
            )
        )
    )
    val trips: StateFlow<List<TrackedTrip>> = _trips.asStateFlow()

    @Volatile
    private var lastSampleMs: Long = 0L

    @Volatile
    private var lastPersistMs: Long = 0L

    suspend fun loadInitial() {
        val raw = context.tripDataStore.data.first()[KEY_TRIPS_JSON]
        if (!raw.isNullOrBlank()) {
            val loaded = runCatching { json.decodeFromString<List<TrackedTrip>>(raw) }.getOrNull()
            if (!loaded.isNullOrEmpty()) {
                synchronized(lock) {
                    _trips.value = loaded
                }
            }
        }
    }

    suspend fun persistNow() {
        val snapshot = synchronized(lock) { _trips.value }
        context.tripDataStore.edit { prefs ->
            prefs[KEY_TRIPS_JSON] = json.encodeToString(snapshot)
        }
        lastPersistMs = System.currentTimeMillis()
    }

    fun shouldAutoPersist(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - lastPersistMs >= 10_000L

    fun resetSampleClock() {
        synchronized(lock) {
            lastSampleMs = 0L
        }
    }

    /**
     * Integrates live telemetry into ALL running trips concurrently (ONLY for real-vehicle sessions
     * matching [vehicleId]), detects odometer data gaps, and returns virtual PID values for the
     * primary trip + instant EV metrics.
     *
     * When [allowTripIntegration] is false (Demo Car / TCP Simulator), real trips are never touched.
     */
    fun onTelemetryTick(
        values: Map<String, Double>,
        nowMs: Long = System.currentTimeMillis(),
        allowTripIntegration: Boolean = true,
        vehicleId: String? = null
    ): Map<String, Double> {
        val hvVoltage = values["hv_voltage"]
        val hvCurrent = values["hv_current"]
        val powerKw = if (hvVoltage != null && hvCurrent != null) {
            (hvVoltage * hvCurrent) / 1000.0
        } else {
            values["hv_power"]
        }
        val speedKmh = values["speed"]
        val soc = values["soc"]
        val soh = values["soh"] ?: 95.0
        val availableEnergyKwh = values["available_energy"]
        val odometerKm = values["odometer"]
        val cellMin = values["cell_voltage_min"]
        val cellMax = values["cell_voltage_max"]
        val cleanVehicleId = vehicleId?.trim()?.uppercase().orEmpty()

        val updatedList = synchronized(lock) {
            if (!allowTripIntegration) {
                // Demo / Simulator mode: NEVER modify or contaminate real-car trips!
                _trips.value
            } else {
                val dtSec = if (lastSampleMs > 0L) {
                    ((nowMs - lastSampleMs) / 1000.0).coerceIn(0.0, 5.0)
                } else {
                    0.0
                }
                lastSampleMs = nowMs

                val dKm = if (speedKmh != null && speedKmh > 0.5) speedKmh * (dtSec / 3600.0) else 0.0
                val dMoving = if (speedKmh != null && speedKmh > 1.0) dtSec else 0.0
                val isCharging = (speedKmh ?: 0.0) < 0.5 && (powerKw ?: 0.0) < -3.0
                val dNetKwh = if (powerKw != null && !isCharging) powerKw * (dtSec / 3600.0) else 0.0
                val dRegenKwh = if (powerKw != null && !isCharging && powerKw < 0.0) (-powerKw) * (dtSec / 3600.0) else 0.0

                val next = _trips.value.map { trip ->
                    val boundVehicleId = trip.vehicleId.ifBlank { cleanVehicleId }
                    val matchesVehicle = boundVehicleId.isEmpty() || cleanVehicleId.isEmpty() ||
                        boundVehicleId.equals(cleanVehicleId, ignoreCase = true)

                    if (!trip.isRunning || !matchesVehicle) {
                        trip
                    } else {
                        var missedKm = trip.missedGapKm
                        var gaps = trip.gapCount
                        var distSinceOdo = trip.distanceSinceLastOdoKm + dKm
                        var newLastOdo = trip.lastOdometerKm

                        // Odometer data-gap detection: if car odometer jumped > 1.2 km ahead of recorded distance
                        if (odometerKm != null && odometerKm > 0.0) {
                            val prevOdo = trip.lastOdometerKm
                            if (prevOdo != null) {
                                val odoDelta = odometerKm - prevOdo
                                val unrecorded = odoDelta - distSinceOdo
                                if (odoDelta > 0.0 && unrecorded > 1.2) {
                                    missedKm += unrecorded
                                    gaps += 1
                                    distSinceOdo = 0.0
                                    newLastOdo = odometerKm
                                } else if (odoDelta >= 1.0) {
                                    distSinceOdo = (distSinceOdo - odoDelta).coerceAtLeast(0.0)
                                    newLastOdo = odometerKm
                                }
                            } else {
                                newLastOdo = odometerKm
                                distSinceOdo = 0.0
                            }
                        }

                        trip.copy(
                            updatedAtMs = nowMs,
                            recordedDistanceKm = trip.recordedDistanceKm + dKm,
                            missedGapKm = missedKm,
                            gapCount = gaps,
                            netEnergyKwh = trip.netEnergyKwh + dNetKwh,
                            regenEnergyKwh = trip.regenEnergyKwh + dRegenKwh,
                            movingSeconds = trip.movingSeconds + dMoving,
                            startOdometerKm = trip.startOdometerKm ?: odometerKm,
                            lastOdometerKm = newLastOdo,
                            startSoc = trip.startSoc ?: soc,
                            lastSoc = soc ?: trip.lastSoc,
                            distanceSinceLastOdoKm = distSinceOdo,
                            vehicleId = boundVehicleId
                        )
                    }
                }
                _trips.value = next
                next
            }
        }

        val primary = updatedList.firstOrNull { it.isPrimary } ?: updatedList.firstOrNull()
        val secondary = updatedList.firstOrNull { it.id != primary?.id }

        val computed = HashMap<String, Double>(16)

        if (powerKw != null) {
            computed[PID_HV_POWER] = powerKw
            computed[PID_CHARGE_POWER] = if (powerKw < -0.2) -powerKw else 0.0
        }

        val auxV = values["aux_12v_voltage"]
        val auxA = values["aux_12v_current"]
        if (auxV != null && auxA != null) {
            computed[PID_DCDC_POWER] = (auxV * auxA) / 1000.0
        }

        if (powerKw != null && speedKmh != null) {
            computed[PID_INST_CONSUMPTION] = if (speedKmh >= 3.0) {
                (powerKw / speedKmh * 100.0).coerceIn(-50.0, 80.0)
            } else {
                0.0
            }
        }

        if (primary != null) {
            computed[PID_TRIP_DISTANCE] = primary.recordedDistanceKm
            computed[PID_TRIP_ENERGY] = primary.netEnergyKwh
            computed[PID_TRIP_REGEN] = primary.regenEnergyKwh
            computed[PID_TRIP_CONSUMPTION] = primary.consumptionKwhPer100Km
            computed[PID_TRIP_AVG_SPEED] = primary.avgSpeedKmh
        }

        if (secondary != null) {
            computed[PID_LAST_TRIP_CONSUMPTION] = secondary.consumptionKwhPer100Km
            computed[PID_LAST_TRIP_KWH] = secondary.netEnergyKwh
            computed[PID_LAST_TRIP_KM] = secondary.recordedDistanceKm
        }

        val remainingKwh = availableEnergyKwh ?: soc?.let { 46.3 * (soh / 100.0) * (it / 100.0) }
        if (remainingKwh != null) {
            val effCons = if (primary != null && primary.recordedDistanceKm >= 0.5 && primary.consumptionKwhPer100Km in 8.0..38.0) {
                primary.consumptionKwhPer100Km
            } else {
                16.2
            }
            computed[PID_EST_RANGE] = (remainingKwh / effCons * 100.0).coerceIn(0.0, 500.0)
        }

        if (cellMin != null && cellMax != null) {
            computed[PID_CELL_DELTA_MV] = ((cellMax - cellMin) * 1000.0).coerceAtLeast(0.0)
        }

        val refInputKw = values["inverter_power"] ?: powerKw
        val motKw = values["motor_power"]
        if (powerKw != null && motKw != null) {
            computed[PID_POWER_LOSSES] = (powerKw - motKw).coerceIn(-20.0, 25.0)
        }
        if (refInputKw != null && motKw != null && kotlin.math.abs(refInputKw) >= 1.5) {
            val eff = if (refInputKw > 0) (motKw / refInputKw * 100.0) else (refInputKw / motKw * 100.0)
            computed[PID_DRIVETRAIN_EFF] = eff.coerceIn(0.0, 100.0)
        }

        return computed
    }

    suspend fun createTrip(name: String, makePrimary: Boolean = false, vehicleId: String = "") {
        synchronized(lock) {
            val clean = name.trim().ifEmpty { "Trip #${_trips.value.size + 1}" }
            val newTrip = TrackedTrip(
                id = "trip_${System.currentTimeMillis()}",
                name = clean,
                isRunning = true,
                isPrimary = makePrimary,
                vehicleId = vehicleId.trim().uppercase()
            )
            val base = if (makePrimary) _trips.value.map { it.copy(isPrimary = false) } else _trips.value
            _trips.value = listOf(newTrip) + base
        }
        persistNow()
    }

    suspend fun toggleRunning(tripId: String) {
        synchronized(lock) {
            _trips.value = _trips.value.map {
                if (it.id == tripId) it.copy(isRunning = !it.isRunning) else it
            }
        }
        persistNow()
    }

    suspend fun setPrimary(tripId: String) {
        synchronized(lock) {
            _trips.value = _trips.value.map { it.copy(isPrimary = it.id == tripId) }
        }
        persistNow()
    }

    suspend fun resetTrip(tripId: String) {
        synchronized(lock) {
            _trips.value = _trips.value.map {
                if (it.id == tripId) {
                    TrackedTrip(
                        id = it.id,
                        name = it.name,
                        isRunning = true,
                        isPrimary = it.isPrimary,
                        vehicleId = it.vehicleId
                    )
                } else {
                    it
                }
            }
        }
        persistNow()
    }

    suspend fun deleteTrip(tripId: String) {
        synchronized(lock) {
            val remaining = _trips.value.filterNot { it.id == tripId }
            _trips.value = if (remaining.isEmpty()) {
                listOf(TrackedTrip(id = "trip_default_a", name = "Trip A (Aktuell)", isRunning = true, isPrimary = true))
            } else if (remaining.none { it.isPrimary }) {
                remaining.mapIndexed { idx, t -> if (idx == 0) t.copy(isPrimary = true) else t }
            } else {
                remaining
            }
        }
        persistNow()
    }

    companion object {
        private val KEY_TRIPS_JSON = stringPreferencesKey("tracked_trips_v2_json")

        const val PID_HV_POWER = "hv_power"
        const val PID_CHARGE_POWER = "charge_power"
        const val PID_DCDC_POWER = "dcdc_power"
        const val PID_INST_CONSUMPTION = "inst_consumption"
        const val PID_TRIP_CONSUMPTION = "trip_consumption"
        const val PID_TRIP_DISTANCE = "trip_distance"
        const val PID_TRIP_ENERGY = "trip_energy"
        const val PID_TRIP_REGEN = "trip_regen"
        const val PID_TRIP_AVG_SPEED = "trip_avg_speed"
        const val PID_EST_RANGE = "est_range"
        const val PID_CELL_DELTA_MV = "cell_delta_mv"
        const val PID_LAST_TRIP_CONSUMPTION = "last_trip_consumption"
        const val PID_LAST_TRIP_KWH = "last_trip_kwh"
        const val PID_LAST_TRIP_KM = "last_trip_km"
        const val PID_DRIVETRAIN_EFF = "drivetrain_efficiency"
        const val PID_POWER_LOSSES = "power_losses"

        val VIRTUAL_PID_META: List<Triple<String, String, String>> = listOf(
            Triple(PID_HV_POWER, "Leistung BMS (V × A)", "kW"),
            Triple(PID_CHARGE_POWER, "Ladeleistung (BMS)", "kW"),
            Triple(PID_DCDC_POWER, "DC-DC 12V Leistung", "kW"),
            Triple(PID_INST_CONSUMPTION, "Momentanverbrauch", "kWh/100km"),
            Triple(PID_TRIP_CONSUMPTION, "Trip Ø Verbrauch", "kWh/100km"),
            Triple(PID_TRIP_DISTANCE, "Trip Distanz", "km"),
            Triple(PID_TRIP_ENERGY, "Trip Energie", "kWh"),
            Triple(PID_TRIP_REGEN, "Trip Rekuperation", "kWh"),
            Triple(PID_TRIP_AVG_SPEED, "Trip Ø Tempo", "km/h"),
            Triple(PID_EST_RANGE, "Restreichweite", "km"),
            Triple(PID_CELL_DELTA_MV, "Zell-Delta", "mV"),
            Triple(PID_DRIVETRAIN_EFF, "Antriebs-Wirkungsgrad", "%"),
            Triple(PID_POWER_LOSSES, "Verluste + Nebenverbraucher", "kW"),
            Triple(PID_LAST_TRIP_CONSUMPTION, "Trip 2 Ø Verbrauch", "kWh/100km"),
            Triple(PID_LAST_TRIP_KWH, "Trip 2 Energie", "kWh"),
            Triple(PID_LAST_TRIP_KM, "Trip 2 Distanz", "km")
        )
    }
}
