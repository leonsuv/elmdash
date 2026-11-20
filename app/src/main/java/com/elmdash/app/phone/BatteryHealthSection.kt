package com.elmdash.app.phone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elmdash.app.CorsaDashRuntime
import com.elmdash.core.telemetry.BatteryHealthState
import com.elmdash.core.telemetry.EcuLinkTestResult
import com.elmdash.core.telemetry.HealthCheckItem
import com.elmdash.core.telemetry.HealthRating
import com.elmdash.core.telemetry.TelemetrySnapshot
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun BatteryChecksCard(checks: List<HealthCheckItem>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, EvColors.Border),
        colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "6-Punkte Batterie-Bewertung (Esco / UNECE R100)",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = EvColors.TextPrimary
            )
            checks.forEach { chk ->
                val (badgeColor, badgeText) = when (chk.rating) {
                    HealthRating.GOOD -> EvColors.Emerald to "GUT"
                    HealthRating.ATTENTION -> EvColors.Amber to "ACHTUNG"
                    HealthRating.CHECK -> EvColors.Red to "PRÜFEN"
                    HealthRating.NA -> EvColors.TextSecondary to "N/A"
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(EvColors.SurfaceElevated)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(chk.title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = EvColors.TextPrimary, modifier = Modifier.weight(1f))
                        Text(
                            text = chk.valueText,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = EvColors.CyanLight,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = badgeText,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(badgeColor.copy(alpha = 0.16f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(chk.basis, fontSize = 10.sp, color = EvColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
fun BatteryDeepDiveTab(
    snapshot: TelemetrySnapshot,
    health: BatteryHealthState
) {
    val scope = rememberCoroutineScope()
    val cells = snapshot.getArray("cell_voltages").orEmpty()
    val cellSoh = snapshot.getArray("cell_soh").orEmpty()
    val probes = snapshot.getArray("temp_probes").orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        // 1. Lifetime Counters & BMS Metadata Card (Total kWh IN/OUT, Starts, Time >55°C, Wear Rate, HW/SW)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, EvColors.Border),
            colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Lebensdauer & BMS-Metadaten", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = EvColors.TextPrimary)
                    OutlinedButton(onClick = { scope.launch { runCatching { CorsaDashRuntime.readBmsMetadata() } } }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text(" BMS-Info", fontSize = 11.sp)
                    }
                }

                val rows = listOf(
                    "Total kWh IN (geladen + Reku)" to (snapshot.getValue("total_energy_charged")?.let { String.format(Locale.GERMANY, "%,.1f kWh", it) } ?: "--"),
                    "Total kWh OUT (entladen)" to (snapshot.getValue("total_energy_discharged")?.let { String.format(Locale.GERMANY, "%,.1f kWh", it) } ?: "--"),
                    "Vollzyklen-Äquivalente" to (health.fullCycleEquivalents?.let { String.format(Locale.US, "%.1f Zyklen", it) } ?: "--"),
                    "HV-Schütz Starts (0xD416)" to (snapshot.getValue("contactor_closes")?.let { String.format(Locale.GERMANY, "%,.0f ×", it) } ?: "--"),
                    "Zeit über 55 °C (0xE082)" to (snapshot.getValue("time_above_55c")?.let { String.format("%.0f s (%.1f min)", it, it / 60.0) } ?: "--"),
                    "Verfügbare Energie (0xD865)" to (snapshot.getValue("available_energy")?.let { String.format("%.2f kWh", it) } ?: "--"),
                    "Pack SoH vs. Zell-Ø SoH" to "${snapshot.getValue("soh")?.let { String.format("%.1f%%", it) } ?: "--"} / ${health.cellSohAvgPct?.let { String.format("%.1f%%", it) } ?: "--"}",
                    "Verschleiß pro 10.000 km" to (health.wearPer10kKmPct?.let { String.format("%.2f %%", it) } ?: "--"),
                    "Verschleiß pro Jahr" to (health.wearPerYearPct?.let { String.format("%.2f %% (%.1f J.)", it, health.batteryAgeYears ?: 0.0) } ?: "--"),
                    "HV-Isolation (+ / −)" to "${snapshot.getValue("isolation_pos_kohm")?.let { String.format("%.0f", it) } ?: "--"} / ${snapshot.getValue("isolation_neg_kohm")?.let { String.format("%.0f kΩ", it) } ?: "--"}",
                    "12V Bordnetz (BU-903)" to (snapshot.getValue("aux_12v_voltage")?.let { String.format("%.2f V (~%d%%)", it, health.aux12vSocPct ?: 0) } ?: "--"),
                    "BMS Baujahr / HW / SW" to "${health.bmsMetadata.manufactureDate ?: "--"} • ${health.bmsMetadata.hardwareVersion ?: "--"} • ${health.bmsMetadata.softwareVersion ?: "--"}"
                )
                rows.forEach { (k, v) ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, fontSize = 11.sp, color = EvColors.TextSecondary, modifier = Modifier.weight(1f))
                        Text(v, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = EvColors.TextPrimary)
                    }
                }
            }
        }

        // 2. 108-Cell Voltage Heatmap (12x9)
        HeatmapGridCard(
            title = "108 Zellspannungen (22 D440 • 108s2p)",
            values = cells,
            columns = 12,
            unit = "V",
            decimals = 3,
            mode = HeatmapMode.VOLTAGE
        )

        // 3. 108-Cell Per-Cell SoH (%) Heatmap (12x9)
        HeatmapGridCard(
            title = "108 Zell-Gesundheit SoH % (22 D4B5)",
            values = cellSoh,
            columns = 12,
            unit = "%",
            decimals = 1,
            mode = HeatmapMode.SOH
        )

        // 4. Cells Under Load (mV / 100 A) — Internal Resistance Sag Analysis
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, EvColors.Border),
            colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Zellen unter Last (Innenwiderstand • mV / 100 A)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = EvColors.TextPrimary)
                if (health.cellsUnderLoad.isEmpty()) {
                    Text(
                        "Wartet auf Ruhe-Referenz (≤ 5 A: ${health.restSampleCount} Samples) und Last-Phase (≥ 30 A: ${health.loadSampleCount} Samples). Im Demo-Auto oder während der Fahrt füllt sich diese Analyse automatisch.",
                        fontSize = 11.sp,
                        color = EvColors.TextSecondary
                    )
                } else {
                    Text(
                        "Basis: ${health.restSampleCount} Ruhe- & ${health.loadSampleCount} Last-Samples (max. ${String.format("%.0f A", health.maxObservedCurrentA)}) • Median: ${String.format("%.1f mV/100 A", health.medianLoadSagMvPer100A ?: 0.0)}",
                        fontSize = 11.sp,
                        color = EvColors.TextSecondary
                    )
                    val maxSag = (health.cellsUnderLoad.firstOrNull()?.sagMvPer100A ?: 10.0).coerceAtLeast(5.0)
                    health.cellsUnderLoad.take(10).forEach { entry ->
                        val frac = (entry.sagMvPer100A / maxSag).toFloat().coerceIn(0.05f, 1f)
                        val barColor = when {
                            entry.sagMvPer100A >= 15.0 -> EvColors.Red
                            entry.sagMvPer100A >= 5.0 -> EvColors.Amber
                            else -> EvColors.Emerald
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "#${entry.cellNumber.toString().padStart(3, '0')}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = EvColors.TextPrimary
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(EvColors.SurfaceElevated)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(frac)
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(barColor)
                                )
                            }
                            Text(
                                text = String.format("%.1f mV", entry.sagMvPer100A),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = barColor
                            )
                        }
                    }
                }
            }
        }

        // 5. 54 Temperature Probes Heatmap (9x6)
        HeatmapGridCard(
            title = "54 Akku-Temperaturfühler (22 D442)",
            values = probes,
            columns = 9,
            unit = "°C",
            decimals = 0,
            mode = HeatmapMode.TEMPERATURE
        )

        // 6. Live Charging Curve (evDash-style kW vs SoC%)
        if (health.chargingCurve.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, EvColors.Border),
                colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Live-Ladekurve (kW über SoC %)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = EvColors.TextPrimary)
                    val maxKw = (health.chargingCurve.maxOfOrNull { it.powerKw } ?: 100.0).coerceAtLeast(11.0)
                    Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
                        val pts = health.chargingCurve
                        pts.forEach { pt ->
                            val x = (pt.socPct / 100f) * size.width
                            val h = ((pt.powerKw / maxKw).toFloat().coerceIn(0.05f, 1f)) * size.height
                            drawRect(
                                color = EvColors.Emerald,
                                topLeft = Offset(x, size.height - h),
                                size = Size((size.width / 100f).coerceAtLeast(3f), h)
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class HeatmapMode { VOLTAGE, SOH, TEMPERATURE }

@Composable
private fun HeatmapGridCard(
    title: String,
    values: List<Double>,
    columns: Int,
    unit: String,
    decimals: Int,
    mode: HeatmapMode
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, EvColors.Border),
        colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = EvColors.TextPrimary)
            if (values.isEmpty()) {
                Text("Wartet auf ISO-TP Multi-Frame Antwort...", fontSize = 11.sp, color = EvColors.TextSecondary)
            } else {
                val minV = values.min()
                val maxV = values.max()
                val avgV = values.average()
                val minIdx = values.indexOf(minV) + 1
                val maxIdx = values.indexOf(maxV) + 1
                Text(
                    text = "Min ${String.format("%.${decimals}f", minV)}$unit (#$minIdx) • Ø ${String.format("%.${decimals}f", avgV)}$unit • Max ${String.format("%.${decimals}f", maxV)}$unit (#$maxIdx) • Δ ${String.format("%.${decimals}f", maxV - minV)}$unit",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = EvColors.CyanLight
                )
                val rows = (values.size + columns - 1) / columns
                val cellHeight = 22.dp
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cellHeight * rows)
                ) {
                    val w = size.width / columns
                    val h = size.height / rows
                    val span = (maxV - minV).coerceAtLeast(0.001)
                    values.forEachIndexed { idx, v ->
                        val r = idx / columns
                        val c = idx % columns
                        val t = ((v - minV) / span).toFloat().coerceIn(0f, 1f)
                        val color = when (mode) {
                            HeatmapMode.VOLTAGE -> when {
                                idx + 1 == minIdx -> EvColors.Amber
                                idx + 1 == maxIdx -> EvColors.Emerald
                                else -> Color(
                                    red = 0.08f + (1f - t) * 0.25f,
                                    green = 0.45f + t * 0.35f,
                                    blue = 0.65f + t * 0.25f
                                )
                            }
                            HeatmapMode.SOH -> when {
                                v < 85.0 -> EvColors.Red
                                v < 92.0 -> EvColors.Amber
                                else -> EvColors.Emerald.copy(alpha = 0.45f + t * 0.55f)
                            }
                            HeatmapMode.TEMPERATURE -> when {
                                v >= 45.0 -> EvColors.Red
                                v >= 35.0 -> EvColors.Amber
                                else -> Color(
                                    red = 0.08f + t * 0.45f,
                                    green = 0.55f + (1f - t) * 0.25f,
                                    blue = 0.85f - t * 0.45f
                                )
                            }
                        }
                        drawRect(
                            color = color,
                            topLeft = Offset(c * w + 1.5f, r * h + 1.5f),
                            size = Size((w - 3f).coerceAtLeast(2f), (h - 3f).coerceAtLeast(2f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EcuLinkAndCsvTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val csvState by CorsaDashRuntime.csvRecorder.state.collectAsState()

    var isTestingEcus by remember { mutableStateOf(false) }
    var ecuResults by remember { mutableStateOf<List<EcuLinkTestResult>>(emptyList()) }
    var testError by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        // 1. Live CSV Recording Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, if (csvState.isRecording) EvColors.Emerald.copy(alpha = 0.6f) else EvColors.Border),
            colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Live CSV Telemetrie-Aufnahme", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = EvColors.TextPrimary)
                Text(
                    text = if (csvState.isRecording) {
                        "Aufnahme läuft: ${csvState.sampleCount} Messpunkte (2 Hz)"
                    } else {
                        "Zeichnet Geschwindigkeit, Leistung, Spannung, Strom, SoC, Temperaturen, Zell-Min/Max & Lifetime-kWh als CSV auf."
                    },
                    fontSize = 12.sp,
                    color = if (csvState.isRecording) EvColors.Emerald else EvColors.TextSecondary
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (csvState.isRecording) {
                                CorsaDashRuntime.csvRecorder.stopRecording()
                            } else {
                                CorsaDashRuntime.csvRecorder.startRecording()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (csvState.isRecording) EvColors.Red else EvColors.Cyan
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (csvState.isRecording) "Aufnahme stoppen" else "CSV-Aufnahme starten", fontWeight = FontWeight.Bold)
                    }
                    val lastPath = csvState.lastSavedFilePath
                    if (!csvState.isRecording && lastPath != null) {
                        OutlinedButton(onClick = {
                            val f = File(lastPath)
                            if (f.exists()) BatteryReportPdfGenerator.shareFile(context, f, "text/csv")
                        }) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(" Teilen", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 2. Safe Read-Only ECU Link Test (EscoEvObdScanner ECUs tab)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, EvColors.Border),
            colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Steuergeräte-Link-Test (8 e-CMP ECUs)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = EvColors.TextPrimary)
                Text(
                    "Prüft gezielt alle 8 bekannten Stellantis e-CMP Steuergeräte rein lesend (0x3E TesterPresent / 0x22 F186) — kein riskantes Bus-Sweeping.",
                    fontSize = 11.sp,
                    color = EvColors.TextSecondary
                )
                Button(
                    onClick = {
                        if (!isTestingEcus) {
                            scope.launch {
                                isTestingEcus = true
                                testError = null
                                runCatching { CorsaDashRuntime.runEcuLinkTest() }
                                    .onSuccess { ecuResults = it }
                                    .onFailure { testError = it.message }
                                isTestingEcus = false
                            }
                        }
                    },
                    enabled = !isTestingEcus,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTestingEcus) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = EvColors.Background)
                        Text("  Prüfe Steuergeräte...", fontWeight = FontWeight.Bold)
                    } else {
                        Text("ECU-Verbindungstest starten", fontWeight = FontWeight.Bold)
                    }
                }
                if (testError != null) {
                    Text(testError!!, fontSize = 11.sp, color = EvColors.Red)
                }
                ecuResults.forEach { res ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(EvColors.SurfaceElevated)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = if (res.reachable) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = if (res.reachable) EvColors.Emerald else EvColors.TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Column {
                                Text("${res.ecuId} — ${res.name}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = EvColors.TextPrimary)
                                Text("TX 0x${res.txHeader} → RX 0x${res.rxHeader} • ${res.note}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = EvColors.TextSecondary)
                            }
                        }
                        Text(
                            text = if (res.reachable) "${res.latencyMs} ms" else "--",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (res.reachable) EvColors.Emerald else EvColors.TextSecondary
                        )
                    }
                }
            }
        }
    }
}
