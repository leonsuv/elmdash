package com.elmdash.app.phone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.elmdash.app.CorsaDashRuntime
import com.elmdash.app.service.ObdForegroundService
import com.elmdash.core.obd.ObdRawLogEntry
import com.elmdash.core.obd.TcpObdTransport
import com.elmdash.core.telemetry.ConnectionState
import com.elmdash.core.telemetry.ObdSettings
import com.elmdash.core.telemetry.TransportMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private data class DiscoveredBtDevice(
    val name: String,
    val mac: String,
    val rssi: Int? = null,
    val isPaired: Boolean = false
) {
    val isLikelyObd: Boolean
        get() {
            val n = name.uppercase(Locale.US)
            return n.contains("VGATE") || n.contains("ICAR") || n.contains("OBD") || n.contains("ELM") || n.contains("IOS-VLINK")
        }
}

@Composable
fun ConnectionTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val savedSettings by CorsaDashRuntime.preferences.settingsFlow.collectAsState(initial = ObdSettings())
    val snapshot by CorsaDashRuntime.repository.snapshot.collectAsState()

    var mode by remember { mutableStateOf(savedSettings.transportMode) }
    var tcpHost by remember { mutableStateOf(savedSettings.tcpHost) }
    var tcpPort by remember { mutableStateOf(savedSettings.tcpPort.toString()) }
    var adapterMac by remember { mutableStateOf(savedSettings.adapterMac) }
    var carBtMac by remember { mutableStateOf(savedSettings.carBluetoothMac) }

    var isScanning by remember { mutableStateOf(false) }
    var scanStatus by remember { mutableStateOf<String?>(null) }
    var showAdvancedMac by remember { mutableStateOf(false) }
    var showCarBtPicker by remember { mutableStateOf(false) }

    val discoveredDevices = remember { mutableStateListOf<DiscoveredBtDevice>() }
    var activeScanCallback by remember { mutableStateOf<ScanCallback?>(null) }

    LaunchedEffect(savedSettings) {
        mode = savedSettings.transportMode
        tcpHost = savedSettings.tcpHost
        tcpPort = savedSettings.tcpPort.toString()
        adapterMac = savedSettings.adapterMac
        carBtMac = savedSettings.carBluetoothMac
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan() {
        val cb = activeScanCallback ?: return
        activeScanCallback = null
        isScanning = false
        runCatching {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.bluetoothLeScanner?.stopScan(cb)
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopBleScan() }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        stopBleScan()
        discoveredDevices.clear()

        // 1. Load already paired devices immediately
        val paired = loadBondedDevices(context)
        paired.forEach { (name, mac) ->
            discoveredDevices.add(DiscoveredBtDevice(name = name, mac = mac, isPaired = true))
        }

        // 2. Start live BLE scan for Vgate iCar Pro 2S & other adapters
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            scanStatus = "Kein Bluetooth-Adapter auf diesem Gerät (im Emulator nutze TCP Sim)."
            return
        }
        if (!adapter.isEnabled) {
            scanStatus = "Bluetooth ist ausgeschaltet — bitte einschalten."
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            scanStatus = if (paired.isEmpty()) "Keine gekoppelten Geräte gefunden." else "${paired.size} gekoppelte Geräte geladen."
            return
        }

        isScanning = true
        scanStatus = "Suche nach Vgate iCar Pro 2S & OBD2-Adaptern..."

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val mac = dev.address ?: return
                val devName = dev.name ?: result.scanRecord?.deviceName ?: return
                if (devName.isBlank()) return

                val idx = discoveredDevices.indexOfFirst { it.mac.equals(mac, ignoreCase = true) }
                val entry = DiscoveredBtDevice(
                    name = devName,
                    mac = mac,
                    rssi = result.rssi,
                    isPaired = idx >= 0 && discoveredDevices[idx].isPaired
                )
                if (idx >= 0) {
                    discoveredDevices[idx] = entry
                } else {
                    discoveredDevices.add(entry)
                }
            }
        }
        activeScanCallback = callback
        runCatching { scanner.startScan(callback) }.onFailure {
            isScanning = false
            scanStatus = "Scan fehlgeschlagen: ${it.message}"
        }

        scope.launch {
            delay(8000L)
            if (isScanning) {
                stopBleScan()
                scanStatus = if (discoveredDevices.isEmpty()) {
                    "Keine Bluetooth-Geräte gefunden."
                } else {
                    "${discoveredDevices.size} Geräte gefunden — einfach antippen zum Verbinden!"
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startScan()
    }

    fun requestPermissionsAndScan() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        if (perms.isNotEmpty() && perms.any {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
        ) {
            permissionLauncher.launch(perms)
        } else {
            startScan()
        }
    }

    fun connectWith(
        targetMode: TransportMode,
        targetMac: String = adapterMac,
        targetCarMac: String = carBtMac,
        targetHost: String = tcpHost,
        targetPort: Int = tcpPort.toIntOrNull() ?: TcpObdTransport.DEFAULT_PORT
    ) {
        stopBleScan()
        mode = targetMode
        adapterMac = targetMac
        carBtMac = targetCarMac
        tcpHost = targetHost
        tcpPort = targetPort.toString()
        scope.launch {
            CorsaDashRuntime.preferences.saveSettings(
                savedSettings.copy(
                    transportMode = targetMode,
                    adapterMac = targetMac,
                    carBluetoothMac = targetCarMac,
                    tcpHost = targetHost,
                    tcpPort = targetPort
                )
            )
            ObdForegroundService.start(context)
            CorsaDashRuntime.restartPolling(context)
        }
    }

    val isLive = snapshot.connectionState == ConnectionState.LIVE
    val statusColor = when (snapshot.connectionState) {
        ConnectionState.LIVE -> EvColors.Emerald
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> EvColors.Amber
        ConnectionState.ERROR -> EvColors.Red
        ConnectionState.DISCONNECTED -> EvColors.TextSecondary
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Connection Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
            colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (mode == TransportMode.TCP) EvIcons.Lan else EvIcons.Bluetooth,
                            contentDescription = null,
                            tint = statusColor
                        )
                    }
                    Column {
                        Text(
                            text = when (snapshot.connectionState) {
                                ConnectionState.LIVE -> "Verbunden (${String.format(Locale.US, "%.0f Hz", snapshot.pollRateHz)})"
                                ConnectionState.CONNECTING -> "Verbinde..."
                                ConnectionState.RECONNECTING -> "Verbinde neu..."
                                ConnectionState.ERROR -> "Verbindungsfehler"
                                ConnectionState.DISCONNECTED -> "Nicht verbunden"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = EvColors.TextPrimary
                        )
                        Text(
                            text = snapshot.activeTransportName.ifBlank { "Kein aktiver Adapter" },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = EvColors.TextSecondary
                        )
                    }
                }
                if (isLive || snapshot.connectionState == ConnectionState.CONNECTING || snapshot.connectionState == ConnectionState.RECONNECTING) {
                    OutlinedButton(onClick = { ObdForegroundService.stop(context) }) {
                        Icon(EvIcons.Power, contentDescription = null, tint = EvColors.Red, modifier = Modifier.size(16.dp))
                        Text(" Stop", color = EvColors.Red)
                    }
                }
            }
        }

        // Transport Mode Selector (2x2 clean grid)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                listOf(TransportMode.BLE to "BLE Vgate 2S", TransportMode.SPP to "SPP Classic"),
                listOf(TransportMode.TCP to "TCP Sim", TransportMode.DEMO to "Demo-Auto")
            ).forEach { pair ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (candidate, title) ->
                        FilterChip(
                            selected = mode == candidate,
                            onClick = {
                                mode = candidate
                                stopBleScan()
                            },
                            label = { Text(title, fontSize = 12.sp, maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (mode == TransportMode.DEMO) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, EvColors.Emerald.copy(alpha = 0.45f)),
                colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Integriertes Demo-Auto (Stellantis e-CMP)", fontWeight = FontWeight.Bold, color = EvColors.TextPrimary)
                    Text(
                        "Kein OBD2-Adapter nötig! Simuliert direkt in der App einen echten Corsa-e / Mokka-e inkl. 108 Zellspannungen, 108 Zell-SoH, 54 Temperaturfühlern, Last-Sag-Analyse, Lifetime-kWh, ECU-Test und PDF-Bericht.",
                        fontSize = 12.sp,
                        color = EvColors.TextSecondary
                    )
                    Button(
                        onClick = { connectWith(TransportMode.DEMO) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Demo-Auto starten", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (mode == TransportMode.TCP) {
            // 1-Tap TCP Simulator Quick Connect
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, EvColors.Border),
                colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Schnellauswahl Simulator", fontWeight = FontWeight.Bold, color = EvColors.TextPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { connectWith(TransportMode.TCP, targetHost = TcpObdTransport.DEFAULT_EMULATOR_HOST, targetPort = 35000) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("AVD (10.0.2.2)", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { connectWith(TransportMode.TCP, targetHost = TcpObdTransport.DEFAULT_LOCALHOST, targetPort = 35000) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("USB (127.0.0.1)", fontSize = 12.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = tcpHost,
                            onValueChange = { tcpHost = it },
                            label = { Text("Host") },
                            singleLine = true,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = tcpPort,
                            onValueChange = { tcpPort = it.filter(Char::isDigit) },
                            label = { Text("Port") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = { connectWith(TransportMode.TCP) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Mit Simulator verbinden")
                    }
                }
            }
        } else {
            // AAA Scan -> Click -> Connect Flow for BLE / SPP
            Button(
                onClick = {
                    if (isScanning) stopBleScan() else requestPermissionsAndScan()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = EvColors.Background
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text("Suche läuft... (Tippen zum Stoppen)", fontWeight = FontWeight.Bold)
                } else {
                    Icon(EvIcons.Bluetooth, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("OBD2-Adapter scannen", fontWeight = FontWeight.Bold)
                }
            }

            scanStatus?.let { status ->
                Text(status, fontSize = 12.sp, color = EvColors.TextSecondary)
            }

            // Discovered & Paired Devices (sorted: Vgate/OBD first!)
            val sortedDevices = discoveredDevices.sortedWith(
                compareByDescending<DiscoveredBtDevice> { it.isLikelyObd }
                    .thenByDescending { it.mac.equals(adapterMac, ignoreCase = true) }
                    .thenByDescending { it.rssi ?: -999 }
            )

            sortedDevices.forEach { dev ->
                val isSelected = dev.mac.equals(adapterMac, ignoreCase = true)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { connectWith(mode, targetMac = dev.mac) },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        width = if (isSelected || dev.isLikelyObd) 1.5.dp else 1.dp,
                        color = when {
                            isSelected -> EvColors.Emerald
                            dev.isLikelyObd -> EvColors.Cyan
                            else -> EvColors.Border
                        }
                    ),
                    colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(dev.name, fontWeight = FontWeight.Bold, color = EvColors.TextPrimary)
                                if (dev.isLikelyObd) {
                                    Text(
                                        text = "OBD2",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EvColors.CyanLight,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(EvColors.Cyan.copy(alpha = 0.18f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = buildString {
                                    append(dev.mac)
                                    dev.rssi?.let { append(" • $it dBm") }
                                    if (dev.isPaired) append(" • Gekoppelt")
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = EvColors.TextSecondary
                            )
                        }
                        if (isSelected) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = "Aktiv", tint = EvColors.Emerald)
                        } else {
                            Text("Verbinden ▸", color = EvColors.CyanLight, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Auto-Connect Corsa-e Infotainment Bluetooth Card (12V Battery Protection)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, EvColors.Border),
                colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(EvIcons.Car, contentDescription = null, tint = EvColors.CyanLight)
                            Column {
                                Text("Auto-Start & 12V-Schutz", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(
                                    text = if (carBtMac.isBlank()) "Corsa-Infotainment-BT wählen (optional)" else "Aktiv: $carBtMac",
                                    fontSize = 11.sp,
                                    color = EvColors.TextSecondary
                                )
                            }
                        }
                        TextButton(onClick = { showCarBtPicker = !showCarBtPicker }) {
                            Text(if (showCarBtPicker) "Fertig" else "Wählen")
                        }
                    }
                    if (showCarBtPicker) {
                        val bonded = remember(showCarBtPicker) { loadBondedDevices(context) }
                        if (bonded.isEmpty()) {
                            Text("Keine gekoppelten Bluetooth-Geräte gefunden.", fontSize = 12.sp, color = EvColors.TextSecondary)
                        } else {
                            bonded.forEach { (name, mac) ->
                                OutlinedButton(
                                    onClick = {
                                        carBtMac = mac
                                        showCarBtPicker = false
                                        scope.launch {
                                            CorsaDashRuntime.preferences.saveSettings(
                                                savedSettings.copy(carBluetoothMac = mac)
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("$name ($mac)", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Collapsible manual MAC fallback (hidden by default!)
            TextButton(onClick = { showAdvancedMac = !showAdvancedMac }) {
                Text(
                    text = if (showAdvancedMac) "▲ Manuelle MAC ausblenden" else "Erweitert: MAC manuell eingeben",
                    fontSize = 12.sp,
                    color = EvColors.TextSecondary
                )
            }
            if (showAdvancedMac) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = adapterMac,
                        onValueChange = { adapterMac = it },
                        label = { Text("Adapter MAC") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { connectWith(mode, targetMac = adapterMac) }) {
                        Text("OK")
                    }
                }
            }
        }

        snapshot.errorMessage?.let { err ->
            Text(err, color = EvColors.Red, fontSize = 12.sp)
        }
    }
}

@Composable
fun RawLogTab() {
    val context = LocalContext.current
    val rawLogs by CorsaDashRuntime.rawLog.entries.collectAsState()
    var filter by remember { mutableStateOf<ObdRawLogEntry.Direction?>(null) }

    val visibleLogs = remember(rawLogs, filter) {
        val filtered = if (filter == null) rawLogs else rawLogs.filter { it.direction == filter }
        filtered.takeLast(200).reversed()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "OBD2 Traffic (${rawLogs.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = EvColors.TextPrimary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { CorsaDashRuntime.rawLog.clear() }) { Text("Clear") }
                Button(onClick = { shareRawLog(context) }) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" Export")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("ALL", fontSize = 11.sp) })
            ObdRawLogEntry.Direction.entries.forEach { dir ->
                FilterChip(
                    selected = filter == dir,
                    onClick = { filter = dir },
                    label = { Text(dir.name, fontSize = 11.sp) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(EvColors.Surface)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(visibleLogs) { entry ->
                val color = when (entry.direction) {
                    ObdRawLogEntry.Direction.TX -> EvColors.CyanLight
                    ObdRawLogEntry.Direction.RX -> EvColors.Emerald
                    ObdRawLogEntry.Direction.ERROR -> EvColors.Red
                    ObdRawLogEntry.Direction.INFO -> EvColors.TextSecondary
                }
                Text(
                    text = entry.formatLine(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = color
                )
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun loadBondedDevices(context: Context): List<Pair<String, String>> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) {
        return emptyList()
    }
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return manager?.adapter?.bondedDevices
        ?.map { (it.name ?: "Unnamed") to it.address }
        ?.sortedBy { it.first }
        .orEmpty()
}

private fun shareRawLog(context: Context) {
    val logText = CorsaDashRuntime.rawLog.exportText().ifBlank { "(No OBD log entries yet)" }
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Corsa-e Raw OBD2 Log")
        putExtra(Intent.EXTRA_TEXT, logText)
    }
    context.startActivity(Intent.createChooser(sendIntent, "OBD2-Log exportieren"))
}
