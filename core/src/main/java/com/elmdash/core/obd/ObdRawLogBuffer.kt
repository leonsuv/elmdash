package com.elmdash.core.obd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ObdRawLogEntry(
    val timestampMs: Long,
    val direction: Direction,
    val payload: String
) {
    enum class Direction { TX, RX, INFO, ERROR }

    fun formatLine(): String {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
        val clean = payload.replace("\r", "\\r").replace("\n", "\\n")
        return "$ts [${direction.name}] $clean"
    }
}

/**
 * Thread-safe ring buffer for raw OBD2 TX/RX traffic (used by Companion Raw-Log Viewer & Export).
 */
class ObdRawLogBuffer(private val maxEntries: Int = 500) {

    private val lock = Any()
    private val ring = ArrayDeque<ObdRawLogEntry>(maxEntries)
    private val _entries = MutableStateFlow<List<ObdRawLogEntry>>(emptyList())
    val entries: StateFlow<List<ObdRawLogEntry>> = _entries.asStateFlow()

    fun append(direction: ObdRawLogEntry.Direction, payload: String, timestampMs: Long = System.currentTimeMillis()) {
        val snapshot = synchronized(lock) {
            if (ring.size >= maxEntries) {
                ring.removeFirst()
            }
            ring.addLast(ObdRawLogEntry(timestampMs, direction, payload))
            ring.toList()
        }
        _entries.value = snapshot
    }

    fun clear() {
        synchronized(lock) {
            ring.clear()
        }
        _entries.value = emptyList()
    }

    fun exportText(): String = entries.value.joinToString("\n") { it.formatLine() }
}
