package com.elmdash.core.telemetry

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    LIVE,
    RECONNECTING,
    ERROR
}

/**
 * Immutable telemetry snapshot consumed by SurfaceRenderer & DashboardWidgets.
 */
data class TelemetrySnapshot(
    val values: Map<String, Double> = emptyMap(),
    val arrays: Map<String, List<Double>> = emptyMap(),
    val history: Map<String, List<Double>> = emptyMap(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isStale: Boolean = true,
    val lastUpdatedMs: Long = 0L,
    val activeTransportName: String = "",
    val pollRateHz: Double = 0.0,
    val errorMessage: String? = null
) {
    fun getValue(pidId: String): Double? = values[pidId]
    fun getArray(pidId: String): List<Double>? = arrays[pidId]
    fun getHistory(pidId: String): List<Double> = history[pidId].orEmpty()
}
