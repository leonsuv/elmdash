package com.elmdash.core.telemetry

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CsvRecorderState(
    val isRecording: Boolean = false,
    val sampleCount: Int = 0,
    val activeFilePath: String? = null,
    val lastSavedFilePath: String? = null
)

/**
 * Lightweight live CSV telemetry recorder writing timestamped EV drive/charge logs
 * into app-external Documents/CorsaDash/recordings for export & offline analysis.
 */
class TelemetryCsvRecorder(private val context: Context) {

    private val lock = Any()
    private val _state = MutableStateFlow(CsvRecorderState())
    val state: StateFlow<CsvRecorderState> = _state.asStateFlow()

    private var writer: BufferedWriter? = null
    private var activeFile: File? = null
    private var lastWriteMs: Long = 0L

    fun startRecording(): File {
        synchronized(lock) {
            stopRecordingLocked()
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings")
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "elmdash_live_$stamp.csv")
            val bw = BufferedWriter(FileWriter(file, false))
            bw.write(CSV_HEADER)
            bw.newLine()
            bw.flush()

            writer = bw
            activeFile = file
            lastWriteMs = 0L
            _state.value = CsvRecorderState(
                isRecording = true,
                sampleCount = 0,
                activeFilePath = file.absolutePath,
                lastSavedFilePath = _state.value.lastSavedFilePath
            )
            return file
        }
    }

    fun stopRecording(): File? {
        synchronized(lock) {
            return stopRecordingLocked()
        }
    }

    fun onSnapshot(snapshot: TelemetrySnapshot) {
        synchronized(lock) {
            val bw = writer ?: return
            val now = System.currentTimeMillis()
            if (now - lastWriteMs < 500L) return // cap at 2 Hz for clean compact CSVs
            lastWriteMs = now

            val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date(now))
            val row = listOf(
                ts,
                fmt(snapshot.getValue("speed"), 1),
                fmt(snapshot.getValue("hv_power"), 2),
                fmt(snapshot.getValue("hv_voltage"), 2),
                fmt(snapshot.getValue("hv_current"), 2),
                fmt(snapshot.getValue("soc"), 2),
                fmt(snapshot.getValue("soh"), 2),
                fmt(snapshot.getValue("available_energy"), 2),
                fmt(snapshot.getValue("battery_temp"), 1),
                fmt(snapshot.getValue("battery_temp_min"), 1),
                fmt(snapshot.getValue("battery_temp_max"), 1),
                fmt(snapshot.getValue("cell_voltage_min"), 3),
                fmt(snapshot.getValue("cell_voltage_max"), 3),
                fmt(snapshot.getValue(TripTracker.PID_CELL_DELTA_MV), 0),
                fmt(snapshot.getValue("motor_power"), 2),
                fmt(snapshot.getValue("motor_rpm"), 0),
                fmt(snapshot.getValue("inverter_temp"), 1),
                fmt(snapshot.getValue("aux_12v_voltage"), 2),
                fmt(snapshot.getValue("odometer"), 1),
                fmt(snapshot.getValue("total_energy_charged"), 2),
                fmt(snapshot.getValue("total_energy_discharged"), 2)
            ).joinToString(",")

            runCatching {
                bw.write(row)
                bw.newLine()
                val nextCount = _state.value.sampleCount + 1
                if (nextCount % 10 == 0) bw.flush()
                _state.value = _state.value.copy(sampleCount = nextCount)
            }
        }
    }

    private fun stopRecordingLocked(): File? {
        val file = activeFile
        runCatching {
            writer?.flush()
            writer?.close()
        }
        writer = null
        activeFile = null
        if (file != null) {
            _state.value = _state.value.copy(
                isRecording = false,
                activeFilePath = null,
                lastSavedFilePath = file.absolutePath
            )
        }
        return file
    }

    private fun fmt(v: Double?, dec: Int): String =
        if (v == null) "" else String.format(Locale.US, "%.${dec}f", v)

    companion object {
        private const val CSV_HEADER =
            "timestamp,speed_kmh,hv_power_kw,hv_voltage_v,hv_current_a,soc_pct,soh_pct," +
                "available_energy_kwh,batt_temp_c,batt_temp_min_c,batt_temp_max_c," +
                "cell_min_v,cell_max_v,cell_delta_mv,motor_power_kw,motor_rpm," +
                "inverter_temp_c,aux_12v_v,odometer_km,total_charged_kwh,total_discharged_kwh"
    }
}
