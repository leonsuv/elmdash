package com.elmdash.app

import android.content.Context
import com.elmdash.core.obd.BleObdTransport
import com.elmdash.core.obd.DemoObdTransport
import com.elmdash.core.obd.ObdRawLogBuffer
import com.elmdash.core.obd.ObdRawLogEntry
import com.elmdash.core.obd.ObdTransport
import com.elmdash.core.obd.SppObdTransport
import com.elmdash.core.obd.TcpObdTransport
import com.elmdash.core.protocol.DtcDecoder
import com.elmdash.core.protocol.EcuDiagnosticReport
import com.elmdash.core.protocol.PidRegistry
import com.elmdash.core.telemetry.BatteryHealthAnalyzer
import com.elmdash.core.telemetry.BmsMetadata
import com.elmdash.core.telemetry.EcuLinkTestResult
import com.elmdash.core.telemetry.ObdPreferences
import com.elmdash.core.telemetry.ObdSettings
import com.elmdash.core.telemetry.TelemetryCache
import com.elmdash.core.telemetry.TelemetryCsvRecorder
import com.elmdash.core.telemetry.TelemetryRepository
import com.elmdash.core.telemetry.TieredScheduler
import com.elmdash.core.telemetry.TransportMode
import com.elmdash.core.telemetry.TripTracker
import com.elmdash.ui.engine.DashboardLayoutConfig
import com.elmdash.ui.engine.DashboardLayoutStore
import com.elmdash.ui.engine.LayoutLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide singleton runtime shared between CorsaCarAppService, ObdForegroundService,
 * and the Phone Companion UI.
 */
object CorsaDashRuntime {

    private val lock = Any()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    lateinit var registry: PidRegistry
        private set
    lateinit var repository: TelemetryRepository
        private set
    lateinit var rawLog: ObdRawLogBuffer
        private set
    lateinit var preferences: ObdPreferences
        private set
    lateinit var cache: TelemetryCache
        private set
    lateinit var layoutStore: DashboardLayoutStore
        private set
    lateinit var tripTracker: TripTracker
        private set
    lateinit var batteryHealth: BatteryHealthAnalyzer
        private set
    lateinit var csvRecorder: TelemetryCsvRecorder
        private set
    lateinit var scheduler: TieredScheduler
        private set

    private val _layoutConfig = MutableStateFlow(DashboardLayoutConfig())
    val layoutConfig: StateFlow<DashboardLayoutConfig> = _layoutConfig.asStateFlow()

    private val _lastDtcReports = MutableStateFlow<List<EcuDiagnosticReport>>(emptyList())
    val lastDtcReports: StateFlow<List<EcuDiagnosticReport>> = _lastDtcReports.asStateFlow()

    private var bmsMetaRequested = false
    private lateinit var appContext: Context

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            registry = PidRegistry.fromAssets(appContext)
            runCatching {
                val dtcJson = appContext.assets.open("stellantis_ecmp_dtc.json").bufferedReader().use { it.readText() }
                DtcDecoder.loadExternalDictionary(dtcJson)
            }
            repository = TelemetryRepository()
            rawLog = ObdRawLogBuffer(maxEntries = 250)
            preferences = ObdPreferences(appContext)
            cache = TelemetryCache(appContext)
            layoutStore = DashboardLayoutStore(appContext)
            tripTracker = TripTracker(appContext)
            batteryHealth = BatteryHealthAnalyzer()
            csvRecorder = TelemetryCsvRecorder(appContext)

            // Initialize default layout synchronously so Android Auto has pages on frame 0
            _layoutConfig.value = LayoutLoader.loadDefaultConfig(appContext)

            scheduler = TieredScheduler(
                registry = registry,
                repository = repository,
                rawLog = rawLog,
                onValuesUpdated = { values ->
                    appScope.launch { cache.save(values) }
                },
                onPollCycle = { values ->
                    val computed = tripTracker.onTelemetryTick(values)
                    repository.mergeComputedValues(computed)
                    val snap = repository.snapshot.value
                    batteryHealth.onSnapshot(snap)
                    csvRecorder.onSnapshot(snap)
                    if (!bmsMetaRequested) {
                        bmsMetaRequested = true
                        appScope.launch {
                            runCatching {
                                val meta = scheduler.readBmsMetadata()
                                batteryHealth.updateBmsMetadata(meta)
                            }
                        }
                    }
                    if (tripTracker.shouldAutoPersist()) {
                        appScope.launch { tripTracker.persistNow() }
                    }
                }
            )

            // Collect custom layout changes from DataStore for real-time sync to Android Auto
            appScope.launch {
                layoutStore.configFlow.collect { cfg ->
                    _layoutConfig.value = cfg
                }
            }

            // Load trip history & cached telemetry for Zero-Blank-UI
            appScope.launch {
                tripTracker.loadInitial()
                val cached = cache.load()
                if (cached.isNotEmpty()) {
                    repository.loadCachedValues(cached)
                    rawLog.append(ObdRawLogEntry.Direction.INFO, "Loaded ${cached.size} cached PID values")
                }
            }

            initialized = true
        }
    }

    fun startPolling(context: Context) {
        ensureInitialized(context)
        if (scheduler.isRunning) return
        appScope.launch {
            val settings = preferences.getSettings()
            scheduler.start(appScope) { createTransport(settings) }
        }
    }

    fun restartPolling(context: Context) {
        ensureInitialized(context)
        bmsMetaRequested = false
        scheduler.stop()
        startPolling(context)
    }

    fun stopPolling() {
        if (!initialized) return
        bmsMetaRequested = false
        scheduler.stop()
        rawLog.append(ObdRawLogEntry.Direction.INFO, "Polling stopped (12V battery protection)")
    }

    suspend fun saveLayout(config: DashboardLayoutConfig) {
        _layoutConfig.value = config
        layoutStore.saveConfig(config)
    }

    suspend fun resetLayout() {
        layoutStore.resetToDefault()
        _layoutConfig.value = LayoutLoader.loadDefaultConfig(appContext)
    }

    suspend fun sendDiagnosticCommand(command: String): String {
        return scheduler.sendDiagnosticCommand(command)
    }

    suspend fun scanAllEcuDtcs(onEcuProgress: ((String) -> Unit)? = null): List<EcuDiagnosticReport> {
        val reports = scheduler.scanAllEcuDtcs(onEcuProgress)
        _lastDtcReports.value = reports
        batteryHealth.updateDtcReports(reports, repository.snapshot.value)
        return reports
    }

    suspend fun clearDtcs(ecuKey: String? = null): Boolean {
        return scheduler.clearDtcs(ecuKey)
    }

    suspend fun readBmsMetadata(): BmsMetadata {
        val meta = scheduler.readBmsMetadata()
        batteryHealth.updateBmsMetadata(meta)
        return meta
    }

    suspend fun runEcuLinkTest(): List<EcuLinkTestResult> {
        return scheduler.runEcuLinkTest()
    }

    private fun createTransport(settings: ObdSettings): ObdTransport {
        return when (settings.transportMode) {
            TransportMode.DEMO -> DemoObdTransport(registry)

            TransportMode.TCP -> TcpObdTransport(
                host = settings.tcpHost,
                port = settings.tcpPort
            )

            TransportMode.BLE -> {
                if (settings.adapterMac.isBlank()) {
                    TcpObdTransport(settings.tcpHost, settings.tcpPort)
                } else {
                    BleObdTransport(appContext, settings.adapterMac)
                }
            }

            TransportMode.SPP -> {
                if (settings.adapterMac.isBlank()) {
                    TcpObdTransport(settings.tcpHost, settings.tcpPort)
                } else {
                    SppObdTransport(appContext, settings.adapterMac)
                }
            }
        }
    }
}
