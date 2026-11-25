package com.elmdash.app.phone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elmdash.app.CorsaDashRuntime
import com.elmdash.core.telemetry.ObdSettings
import com.elmdash.core.telemetry.TrackedTrip
import com.elmdash.core.telemetry.TripTracker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripsTab() {
    val scope = rememberCoroutineScope()
    val trips by CorsaDashRuntime.tripTracker.trips.collectAsState()
    val snapshot by CorsaDashRuntime.repository.snapshot.collectAsState()
    val settings by CorsaDashRuntime.preferences.settingsFlow.collectAsState(initial = ObdSettings())
    val health by CorsaDashRuntime.batteryHealth.state.collectAsState()
    val isSimulated by CorsaDashRuntime.isSimulatedSession.collectAsState()
    val activeVehicleId by CorsaDashRuntime.activeVehicleId.collectAsState()
    val priceEur = settings.electricityPriceEurPerKwh

    var showCreateDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Dauer-Trips (${trips.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = EvColors.TextPrimary
                )
                Text(
                    text = "Laufen parallel weiter, speichern automatisch & berechnen Stromkosten",
                    fontSize = 11.sp,
                    color = EvColors.TextSecondary
                )
            }
            Button(onClick = { showCreateDialog = true }) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Trip")
            }
        }

        if (isSimulated) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(EvColors.Amber.copy(alpha = 0.16f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = EvColors.Amber, modifier = Modifier.size(18.dp))
                Text(
                    text = "Demo-/Simulator-Modus aktiv — echte Fahrzeug-Trips werden nicht verändert.",
                    fontSize = 11.sp,
                    color = EvColors.Amber
                )
            }
        } else if (!activeVehicleId.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(EvColors.Emerald.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Aktives Fahrzeug (VIN / BMS-ID)", fontSize = 11.sp, color = EvColors.TextSecondary)
                Text(
                    text = activeVehicleId!!,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = EvColors.Emerald
                )
            }
        }

        // Electricity price quick selector (EscoEvObdScanner cost estimate)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, EvColors.Border),
            colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Strompreis-Schätzung", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = EvColors.TextPrimary)
                    Text(
                        String.format(Locale.GERMANY, "%.2f €/kWh", priceEur),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = EvColors.CyanLight
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.25, 0.35, 0.45, 0.59).forEach { preset ->
                        FilterChip(
                            selected = kotlin.math.abs(priceEur - preset) < 0.005,
                            onClick = {
                                scope.launch {
                                    CorsaDashRuntime.preferences.saveSettings(settings.copy(electricityPriceEurPerKwh = preset))
                                }
                            },
                            label = { Text(String.format(Locale.GERMANY, "%.2f€", preset), fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        val estRange = snapshot.getValue(TripTracker.PID_EST_RANGE)
        if (estRange != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(EvColors.Surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Geschätzte Restreichweite (Realverbrauch)", fontSize = 12.sp, color = EvColors.TextSecondary)
                Text(
                    text = String.format(Locale.US, "%.0f km", estRange),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = EvColors.CyanLight
                )
            }
        }

        val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY) }

        trips.forEach { trip ->
            TripCard(
                trip = trip,
                startedLabel = dateFmt.format(Date(trip.startedAtMs)),
                priceEurPerKwh = priceEur,
                onSetPrimary = { scope.launch { CorsaDashRuntime.tripTracker.setPrimary(trip.id) } },
                onToggleRun = { scope.launch { CorsaDashRuntime.tripTracker.toggleRunning(trip.id) } },
                onReset = { scope.launch { CorsaDashRuntime.tripTracker.resetTrip(trip.id) } },
                onDelete = { scope.launch { CorsaDashRuntime.tripTracker.deleteTrip(trip.id) } }
            )
        }

        // Lifetime Vehicle Counters (EscoEvObdScanner BMS 0xD42E / 0xD416 / 0xE082)
        val totalIn = snapshot.getValue("total_energy_charged")
        val totalOut = snapshot.getValue("total_energy_discharged")
        val starts = snapshot.getValue("contactor_closes")
        val time55 = snapshot.getValue("time_above_55c")
        val odo = snapshot.getValue("odometer")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, EvColors.Border),
            colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Fahrzeug-Lebensdauer-Zähler (BMS)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = EvColors.TextPrimary)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TripMetricColumn(
                        label = "TOTAL kWh IN",
                        value = totalIn?.let { String.format(Locale.GERMANY, "%,.0f", it) } ?: "--",
                        unit = "kWh geladen",
                        emerald = true
                    )
                    TripMetricColumn(
                        label = "TOTAL kWh OUT",
                        value = totalOut?.let { String.format(Locale.GERMANY, "%,.0f", it) } ?: "--",
                        unit = "kWh entladen",
                        accent = true
                    )
                    val fullCycles = health.fullCycleEquivalents ?: totalOut?.let { it / 46.3 }
                    TripMetricColumn(
                        label = "VOLLZYKLEN",
                        value = fullCycles?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
                        unit = "Äquiv."
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TripMetricColumn(
                        label = "HV-STARTS",
                        value = starts?.let { String.format(Locale.GERMANY, "%,.0f", it) } ?: "--",
                        unit = "Schütz-Zyklen"
                    )
                    TripMetricColumn(
                        label = "ZEIT > 55 °C",
                        value = time55?.let { String.format(Locale.US, "%.0f", it) } ?: "--",
                        unit = "Sekunden"
                    )
                    TripMetricColumn(
                        label = "KILOMETER",
                        value = odo?.let { String.format(Locale.GERMANY, "%,.0f", it) } ?: "--",
                        unit = "km (VCU)"
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Neuen Dauer-Trip starten") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (z. B. Urlaub, Winter, Seit Laden)") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val clean = name
                    showCreateDialog = false
                    scope.launch {
                        CorsaDashRuntime.tripTracker.createTrip(
                            name = clean,
                            makePrimary = false,
                            vehicleId = activeVehicleId.orEmpty()
                        )
                    }
                }) {
                    Text("Starten")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun TripCard(
    trip: TrackedTrip,
    startedLabel: String,
    priceEurPerKwh: Double,
    onSetPrimary: () -> Unit,
    onToggleRun: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor = when {
        trip.isPrimary -> EvColors.Cyan.copy(alpha = 0.55f)
        trip.isRunning -> EvColors.Emerald.copy(alpha = 0.3f)
        else -> EvColors.Border
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (trip.isPrimary) 1.5.dp else 1.dp, borderColor),
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
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(trip.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = EvColors.TextPrimary)
                        Text(
                            text = if (trip.isRunning) "LÄUFT" else "PAUSIERT",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (trip.isRunning) EvColors.Emerald else EvColors.Amber,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background((if (trip.isRunning) EvColors.Emerald else EvColors.Amber).copy(alpha = 0.16f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = buildString {
                            append("Seit $startedLabel")
                            if (trip.isPrimary) append(" • Dashboard-Trip")
                            if (trip.vehicleId.isNotBlank()) append(" • ${trip.vehicleId}")
                        },
                        fontSize = 11.sp,
                        color = EvColors.TextSecondary
                    )
                }

                Row {
                    IconButton(onClick = onSetPrimary) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = "Als Dashboard-Trip",
                            tint = if (trip.isPrimary) EvColors.Amber else EvColors.Border
                        )
                    }
                    IconButton(onClick = onToggleRun) {
                        Icon(
                            imageVector = if (trip.isRunning) EvIcons.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Pause/Fortsetzen",
                            tint = EvColors.CyanLight
                        )
                    }
                    IconButton(onClick = onReset) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Reset", tint = EvColors.TextSecondary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Löschen", tint = EvColors.Red)
                    }
                }
            }

            // Metrics Grid
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TripMetricColumn(
                    label = "Ø VERBRAUCH",
                    value = String.format(Locale.US, "%.1f", trip.consumptionKwhPer100Km),
                    unit = "kWh/100km",
                    accent = true
                )
                TripMetricColumn(
                    label = "ERFASST",
                    value = String.format(Locale.US, "%.2f", trip.recordedDistanceKm),
                    unit = "km"
                )
                TripMetricColumn(
                    label = "NETTO",
                    value = String.format(Locale.US, "%.2f", trip.netEnergyKwh),
                    unit = "kWh"
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TripMetricColumn(
                    label = "REKUPERIERT",
                    value = String.format(Locale.US, "+%.2f", trip.regenEnergyKwh),
                    unit = "kWh (${String.format(Locale.US, "%.0f%%", trip.regenRatioPct)})",
                    emerald = true
                )
                TripMetricColumn(
                    label = "Ø TEMPO",
                    value = String.format(Locale.US, "%.0f", trip.avgSpeedKmh),
                    unit = "km/h"
                )
                TripMetricColumn(
                    label = "GESAMT KM",
                    value = String.format(Locale.US, "%.1f", trip.totalDistanceKm),
                    unit = "inkl. Odo"
                )
            }

            // Electricity Cost Estimate (EscoEvObdScanner)
            val tripCostEur = (trip.netEnergyKwh * priceEurPerKwh).coerceAtLeast(0.0)
            val costPer100Eur = (trip.consumptionKwhPer100Km * priceEurPerKwh).coerceAtLeast(0.0)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(EvColors.SurfaceElevated)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Stromkosten (geschätzt)", fontSize = 11.sp, color = EvColors.TextSecondary)
                Text(
                    text = String.format(Locale.GERMANY, "%.2f €  •  %.2f €/100 km", tripCostEur, costPer100Eur),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    color = EvColors.Emerald
                )
            }

            // Automatic Odometer Data-Gap Banner (shows when user drove without OBD/AA connected!)
            if (trip.missedGapKm > 0.1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(EvColors.Amber.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = EvColors.Amber,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "Datenlücke erkannt: %.1f km nicht per OBD erfasst (%d× ohne Verbindung gefahren)",
                            trip.missedGapKm,
                            trip.gapCount
                        ),
                        fontSize = 11.sp,
                        color = EvColors.Amber,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun TripMetricColumn(
    label: String,
    value: String,
    unit: String,
    accent: Boolean = false,
    emerald: Boolean = false
) {
    Column {
        Text(label, fontSize = 10.sp, color = EvColors.TextSecondary, fontWeight = FontWeight.SemiBold)
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = when {
                emerald -> EvColors.Emerald
                accent -> EvColors.CyanLight
                else -> EvColors.TextPrimary
            }
        )
        Text(unit, fontSize = 11.sp, color = EvColors.TextSecondary)
    }
}
