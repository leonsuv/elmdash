package com.elmdash.core.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Central state holder for live & cached vehicle telemetry.
 * Maintains per-PID history ring buffers for SparklineWidget.
 */
class TelemetryRepository(private val maxHistorySamples: Int = 90) {

    private val lock = Any()
    private val historyRings = HashMap<String, ArrayDeque<Double>>()

    private val _snapshot = MutableStateFlow(TelemetrySnapshot())
    val snapshot: StateFlow<TelemetrySnapshot> = _snapshot.asStateFlow()

    fun loadCachedValues(cachedValues: Map<String, Double>) {
        if (cachedValues.isEmpty()) return
        _snapshot.update { current ->
            if (current.values.isEmpty()) {
                current.copy(
                    values = cachedValues,
                    isStale = true
                )
            } else {
                current
            }
        }
    }

    fun updateScalar(pidId: String, value: Double, timestampMs: Long = System.currentTimeMillis()) {
        val historyCopy = synchronized(lock) {
            val ring = historyRings.getOrPut(pidId) { ArrayDeque(maxHistorySamples) }
            if (ring.size >= maxHistorySamples) {
                ring.removeFirst()
            }
            ring.addLast(value)
            ring.toList()
        }

        _snapshot.update { current ->
            current.copy(
                values = current.values + (pidId to value),
                history = current.history + (pidId to historyCopy),
                isStale = false,
                lastUpdatedMs = timestampMs,
                errorMessage = null
            )
        }
    }

    fun updateArray(pidId: String, values: List<Double>, timestampMs: Long = System.currentTimeMillis()) {
        _snapshot.update { current ->
            current.copy(
                arrays = current.arrays + (pidId to values),
                isStale = false,
                lastUpdatedMs = timestampMs,
                errorMessage = null
            )
        }
    }

    fun mergeComputedValues(computed: Map<String, Double>) {
        if (computed.isEmpty()) return
        val updatedHistory = HashMap<String, List<Double>>()
        synchronized(lock) {
            // Track sparkline history for computed live gauges/graphs (HV power, charge curve, inst. consumption, DC-DC)
            val trackedKeys = listOf(
                TripTracker.PID_HV_POWER,
                TripTracker.PID_CHARGE_POWER,
                TripTracker.PID_INST_CONSUMPTION,
                TripTracker.PID_SESSION_CONSUMPTION,
                TripTracker.PID_DCDC_POWER
            )
            for (key in trackedKeys) {
                computed[key]?.let { value ->
                    val ring = historyRings.getOrPut(key) { ArrayDeque(maxHistorySamples) }
                    if (ring.size >= maxHistorySamples) ring.removeFirst()
                    ring.addLast(value)
                    updatedHistory[key] = ring.toList()
                }
            }
        }
        _snapshot.update { current ->
            current.copy(
                values = current.values + computed,
                history = if (updatedHistory.isEmpty()) current.history else current.history + updatedHistory
            )
        }
    }

    fun updateConnection(
        state: ConnectionState,
        transportName: String? = null,
        errorMessage: String? = null
    ) {
        _snapshot.update { current ->
            current.copy(
                connectionState = state,
                activeTransportName = transportName ?: current.activeTransportName,
                isStale = if (state == ConnectionState.LIVE) current.isStale else true,
                errorMessage = errorMessage
            )
        }
    }

    fun updatePollRate(hz: Double) {
        _snapshot.update { it.copy(pollRateHz = hz) }
    }
}
