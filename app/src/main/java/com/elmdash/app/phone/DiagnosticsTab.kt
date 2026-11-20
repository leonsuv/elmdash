package com.elmdash.app.phone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.elmdash.app.CorsaDashRuntime
import com.elmdash.core.protocol.EcuDiagnosticReport
import com.elmdash.core.telemetry.ObdSettings
import com.elmdash.core.telemetry.TripTracker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Carly-style Corsa-e Vehicle Health & Multi-ECU UDS Fault Code Scanner (Service 0x19 / 0x14).
 * Includes hidden Developer Tools (Raw-OBD-Log & AT/UDS Console) tucked cleanly away for experts.
 */
@Composable
fun DiagnosticsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by CorsaDashRuntime.repository.snapshot.collectAsState()
    val health by CorsaDashRuntime.batteryHealth.state.collectAsState()
    val settings by CorsaDashRuntime.preferences.settingsFlow.collectAsState(initial = ObdSettings())

    var subTab by remember { mutableIntStateOf(0) } // 0 = Übersicht & PDF, 1 = Akku & 108s, 2 = ECU-Test & CSV
    var vinNote by remember { mutableStateOf(settings.reportVinOrNote) }
    var pdfStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings.reportVinOrNote) {
        if (vinNote.isBlank() && settings.reportVinOrNote.isNotBlank()) {
            vinNote = settings.reportVinOrNote
        }
    }

    var reports by remember { mutableStateOf<List<EcuDiagnosticReport>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var scanningEcuName by remember { mutableStateOf("") }
    var battery12v by remember { mutableStateOf<String?>(null) }
    var adapterId by remember { mutableStateOf<String?>(null) }
    var lastScanTime by remember { mutableStateOf<Long?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }

    var showDevToolsDialog by remember { mutableStateOf(false) }

    val totalDtcs = reports.sumOf { it.dtcs.size }
    val activeDtcs = reports.sumOf { r -> r.dtcs.count { it.isActive || it.isMilOn } }
    val healthScore = when {
        reports.isEmpty() -> null
        activeDtcs > 0 -> (100 - activeDtcs * 22 - (totalDtcs - activeDtcs) * 8).coerceIn(25, 92)
        totalDtcs > 0 -> (100 - totalDtcs * 8).coerceIn(60, 96)
        else -> 100
    }

    fun runFullScan() {
        isScanning = true
        scanError = null
        scope.launch {
            try {
                scanningEcuName = "ELM327 Adapter & 12V..."
                adapterId = runCatching { CorsaDashRuntime.sendDiagnosticCommand("ATI") }.getOrNull()
                battery12v = runCatching { CorsaDashRuntime.sendDiagnosticCommand("ATRV") }.getOrNull()

                reports = CorsaDashRuntime.scanAllEcuDtcs { ecuName ->
                    scanningEcuName = "Scanne $ecuName..."
                }
                lastScanTime = System.currentTimeMillis()
            } catch (e: Exception) {
                scanError = e.message ?: "Scan fehlgeschlagen — Adapter verbunden?"
            } finally {
                isScanning = false
                scanningEcuName = ""
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 3-Tab Sub-Navigation (Übersicht & PDF | Akku & 108s | ECU-Test & CSV)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Übersicht & PDF", "Akku & 108s", "ECU-Test & CSV").forEachIndexed { idx, title ->
                FilterChip(
                    selected = subTab == idx,
                    onClick = { subTab = idx },
                    label = { Text(title, fontSize = 11.sp, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (subTab == 1) {
            BatteryDeepDiveTab(snapshot = snapshot, health = health)
        } else if (subTab == 2) {
            EcuLinkAndCsvTab()
        } else {
        // Carly-Style Hero Vehicle Health Card
        val heroColor = when {
            healthScore == null -> EvColors.Cyan
            healthScore >= 95 -> EvColors.Emerald
            healthScore >= 75 -> EvColors.Amber
            else -> EvColors.Red
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.5.dp, heroColor.copy(alpha = 0.5f)),
            colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Opel Corsa Electric Diagnose",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = EvColors.TextPrimary
                        )
                        Text(
                            text = when {
                                healthScore == null -> "Bereit für vollständigen Steuergeräte-Scan (BMS, VCU, OBC)"
                                totalDtcs == 0 -> "Alle Steuergeräte fehlerfrei • 100 % gesund"
                                activeDtcs > 0 -> "$activeDtcs aktive Fehler & ${totalDtcs - activeDtcs} Hinweise gefunden"
                                else -> "$totalDtcs gespeicherte Hinweise (keine aktiven Fehler)"
                            },
                            fontSize = 12.sp,
                            color = EvColors.TextSecondary
                        )
                        lastScanTime?.let { ts ->
                            val fmt = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)
                            Text(
                                text = "Letzter Scan: ${fmt.format(Date(ts))}" + (adapterId?.let { " • $it" } ?: ""),
                                fontSize = 11.sp,
                                color = EvColors.TextSecondary
                            )
                        }
                    }

                    // Health Score Badge
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(heroColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = healthScore?.let { "$it%" } ?: "—",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp,
                            color = heroColor
                        )
                    }
                }

                // Quick HV + 12V Health Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(EvColors.SurfaceElevated)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    HealthMiniStat("12V BORD", battery12v ?: "--")
                    HealthMiniStat("HV SOH", snapshot.getValue("soh")?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--")
                    HealthMiniStat(
                        "ZELL-DELTA",
                        snapshot.getValue(TripTracker.PID_CELL_DELTA_MV)?.let { String.format(Locale.US, "%.0f mV", it) } ?: "--"
                    )
                    HealthMiniStat("KANÄLE", "${snapshot.getArray("cell_voltages")?.size ?: 0}/108")
                }

                // Primary Scan Button
                Button(
                    enabled = !isScanning,
                    onClick = { runFullScan() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = EvColors.Background
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(scanningEcuName.ifEmpty { "Scanne Steuergeräte..." }, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Fahrzeug-Scan starten (UDS 0x19)", fontWeight = FontWeight.Bold)
                    }
                }

                if (isScanning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                scanError?.let {
                    Text(it, color = EvColors.Red, fontSize = 12.sp)
                }
            }
        }

        // Per-ECU Fault Code Results (Read-Only UDS 0x19)
        if (reports.isNotEmpty()) {
            Text(
                text = "Steuergeräte (${reports.size}) • Nur Lesen (Read-Only)",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = EvColors.TextPrimary
            )

            reports.forEach { report ->
                val ecuColor = when {
                    !report.reachable -> EvColors.TextSecondary
                    report.dtcs.any { it.isActive || it.isMilOn } -> EvColors.Red
                    report.dtcs.isNotEmpty() -> EvColors.Amber
                    else -> EvColors.Emerald
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, ecuColor.copy(alpha = 0.45f)),
                    colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = when {
                                        !report.reachable -> Icons.Rounded.Warning
                                        report.dtcs.isEmpty() -> Icons.Rounded.CheckCircle
                                        else -> Icons.Rounded.Warning
                                    },
                                    contentDescription = null,
                                    tint = ecuColor
                                )
                                Column {
                                    Text(report.ecuName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = EvColors.TextPrimary)
                                    Text(
                                        text = "${report.ecuKey} • CAN 0x${report.txHeader} • " + when {
                                            !report.reachable -> "Nicht erreichbar"
                                            report.dtcs.isEmpty() -> "0 Fehler"
                                            else -> "${report.dtcs.size} Fehlercode(s)"
                                        },
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = ecuColor
                                    )
                                }
                            }
                        }

                        report.dtcs.forEach { dtc ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(EvColors.SurfaceElevated)
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${dtc.code}-${dtc.failureTypeByte}",
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = if (dtc.isActive) EvColors.Red else EvColors.Amber
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (dtc.isActive) DtcBadge("AKTIV", EvColors.Red)
                                        if (dtc.isConfirmed) DtcBadge("BESTÄTIGT", EvColors.Amber)
                                        if (dtc.isPending) DtcBadge("SPORADISCH", EvColors.CyanLight)
                                        if (dtc.isMilOn) DtcBadge("MIL", EvColors.Red)
                                    }
                                }
                                Text(dtc.description, fontSize = 12.sp, color = EvColors.TextPrimary)
                            }
                        }
                    }
                }
            }
        }

        // 6-Point EscoEvObdScanner Battery Health Checks
        if (health.checks.isNotEmpty()) {
            BatteryChecksCard(checks = health.checks)
        }

        // PDF Battery Health Certificate Generator (EscoEvObdScanner Report)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, EvColors.Cyan.copy(alpha = 0.45f)),
            colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PDF Batterie-Bericht (Zertifikat)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = EvColors.TextPrimary)
                Text(
                    "Erstellt einen 2-seitigen A4-PDF-Bericht mit 6-Punkte-Bewertung, allen 108 Zellspannungen, 108 Zell-SoH, 54 Temperaturfühlern, Last-Sag, Total kWh IN/OUT, BMS-Info & DTCs.",
                    fontSize = 11.sp,
                    color = EvColors.TextSecondary
                )
                OutlinedTextField(
                    value = vinNote,
                    onValueChange = { vinNote = it },
                    label = { Text("VIN / Fahrzeug-Notiz (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        scope.launch {
                            CorsaDashRuntime.preferences.saveSettings(settings.copy(reportVinOrNote = vinNote))
                            runCatching {
                                val file = BatteryReportPdfGenerator.generate(
                                    context = context,
                                    snapshot = snapshot,
                                    health = health,
                                    dtcReports = reports,
                                    vinOrNote = vinNote
                                )
                                pdfStatus = "Gespeichert: ${file.name}"
                                BatteryReportPdfGenerator.shareFile(context, file, "application/pdf")
                            }.onFailure {
                                pdfStatus = "PDF-Fehler: ${it.message}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  PDF Batterie-Bericht erstellen & teilen", fontWeight = FontWeight.Bold)
                }
                if (pdfStatus != null) {
                    Text(pdfStatus!!, fontSize = 11.sp, color = EvColors.Emerald)
                }
            }
        }

        // Hidden Developer Tools Entry (Raw OBD Log + AT/UDS Console + PID Inspector)
        OutlinedButton(
            onClick = { showDevToolsDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(EvIcons.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(8.dp))
            Text("Entwickler-Tools (Raw-OBD-Log & AT/UDS-Konsole)", fontSize = 12.sp)
        }
        }
    }

    // Hidden Developer Tools Dialog (Raw Log + AT/UDS Console)
    if (showDevToolsDialog) {
        DeveloperToolsDialog(onDismiss = { showDevToolsDialog = false })
    }
}

@Composable
private fun HealthMiniStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, color = EvColors.TextSecondary, fontWeight = FontWeight.Bold)
        Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = EvColors.TextPrimary)
    }
}

@Composable
private fun DtcBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun DeveloperToolsDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var subTab by remember { mutableStateOf(0) }
    var consoleCmd by remember { mutableStateOf("ATRV") }
    var consoleOut by remember { mutableStateOf("Bereit (z. B. ATI, ATRV, ATDP, 22D8151)") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Entwickler-Tools", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.height(420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = subTab == 0, onClick = { subTab = 0 }, label = { Text("Raw-OBD-Log") })
                    FilterChip(selected = subTab == 1, onClick = { subTab = 1 }, label = { Text("AT/UDS Konsole") })
                }
                if (subTab == 0) {
                    RawLogTab()
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("ATI", "ATRV", "ATDP", "1902FF", "22D8151").forEach { q ->
                                FilterChip(
                                    selected = consoleCmd.equals(q, ignoreCase = true),
                                    onClick = { consoleCmd = q },
                                    label = { Text(q, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = consoleCmd,
                                onValueChange = { consoleCmd = it },
                                label = { Text("Befehl") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(onClick = {
                                val cmd = consoleCmd.trim()
                                if (cmd.isNotEmpty()) {
                                    scope.launch {
                                        consoleOut = "TX > $cmd\n" + runCatching {
                                            "RX < " + CorsaDashRuntime.sendDiagnosticCommand(cmd)
                                        }.getOrElse { "ERR: ${it.message}" }
                                    }
                                }
                            }) { Text("Send") }
                        }
                        Text(
                            text = consoleOut,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(EvColors.Background)
                                .padding(10.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = EvColors.Emerald
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        }
    )
}
