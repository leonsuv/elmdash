package com.elmdash.app.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.elmdash.app.CorsaDashRuntime
import com.elmdash.core.telemetry.TelemetrySnapshot
import com.elmdash.core.telemetry.TripTracker
import com.elmdash.ui.engine.DashboardLayoutConfig
import com.elmdash.ui.engine.DashboardPageConfig
import com.elmdash.ui.engine.LayoutLoader
import com.elmdash.ui.engine.SurfaceRenderer
import com.elmdash.ui.engine.WidgetSlotConfig
import kotlinx.coroutines.launch
import kotlin.math.abs

private data class PidChoice(
    val id: String,
    val label: String,
    val unit: String,
    val group: String,
    val minDefault: Double = 0.0,
    val maxDefault: Double = 100.0,
    val isArray: Boolean = false
)

@Composable
fun DashboardBuilderTab(
    isFullscreen: Boolean = false,
    onFullscreenChange: (Boolean) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val layoutConfig by CorsaDashRuntime.layoutConfig.collectAsState()
    val savedDashboards by CorsaDashRuntime.layoutStore.savedDashboardsFlow.collectAsState(initial = emptyList())
    val savedCards by CorsaDashRuntime.layoutStore.savedCardsFlow.collectAsState(initial = emptyList())
    val snapshot by CorsaDashRuntime.repository.snapshot.collectAsState()

    var selectedPageIdx by remember { mutableIntStateOf(0) }
    var previewDark by remember { mutableStateOf(true) }
    var editingSlot by remember { mutableStateOf<WidgetSlotConfig?>(null) }
    var isCreatingNewWidget by remember { mutableStateOf(false) }
    var showAddTabDialog by remember { mutableStateOf(false) }
    var showLibraryDialog by remember { mutableStateOf(false) }
    var pendingDeleteSlot by remember { mutableStateOf<WidgetSlotConfig?>(null) }

    val pages = layoutConfig.pages
    val safePageIdx = selectedPageIdx.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    val currentPage = pages.getOrNull(safePageIdx)

    val allPidChoices = remember {
        val obd = CorsaDashRuntime.registry.allPids.map {
            PidChoice(
                id = it.id,
                label = it.name,
                unit = it.unit,
                group = "OBD2 Live-Sensoren",
                minDefault = it.min,
                maxDefault = it.max,
                isArray = it.valueType.name == "ARRAY"
            )
        }
        val virtual = TripTracker.VIRTUAL_PID_META.map { (id, label, unit) ->
            PidChoice(
                id = id,
                label = label,
                unit = unit,
                group = "Berechnete EV & Trip-Daten",
                minDefault = if (id == TripTracker.PID_INST_CONSUMPTION) -20.0 else 0.0,
                maxDefault = when (id) {
                    TripTracker.PID_INST_CONSUMPTION, TripTracker.PID_TRIP_CONSUMPTION, TripTracker.PID_LAST_TRIP_CONSUMPTION -> 45.0
                    TripTracker.PID_EST_RANGE -> 400.0
                    TripTracker.PID_CELL_DELTA_MV -> 100.0
                    else -> 100.0
                }
            )
        }
        obd + virtual
    }

    fun openNewWidgetEditor() {
        isCreatingNewWidget = true
        editingSlot = WidgetSlotConfig(
            id = "widget_${System.currentTimeMillis()}",
            type = "big_number",
            label = "Trip Ø Verbrauch",
            pid = TripTracker.PID_TRIP_CONSUMPTION,
            unit = "kWh/100",
            decimals = 1,
            col = 0,
            row = 4,
            colSpan = 4,
            rowSpan = 2,
            autoScale = true
        )
    }

    fun moveWidget(movedSlot: WidgetSlotConfig, newCol: Int, newRow: Int) {
        if (currentPage == null) return
        val updatedWidgets = currentPage.widgets.map {
            if (it.id == movedSlot.id) it.copy(col = newCol, row = newRow) else it
        }
        val updatedPages = pages.mapIndexed { idx, p ->
            if (idx == safePageIdx) p.copy(widgets = updatedWidgets) else p
        }
        scope.launch {
            CorsaDashRuntime.saveLayout(layoutConfig.copy(pages = updatedPages))
        }
    }

    if (isFullscreen) {
        val config = LocalConfiguration.current
        val isLandscape = config.screenWidthDp > config.screenHeightDp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(EvColors.Background)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Editor",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = EvColors.TextPrimary,
                    maxLines = 1
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { openNewWidgetEditor() }) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(" Widget", fontSize = 12.sp)
                    }
                    FilterChip(
                        selected = previewDark,
                        onClick = { previewDark = !previewDark },
                        label = { Text(if (previewDark) "Nacht" else "Tag", fontSize = 12.sp) }
                    )
                    IconButton(onClick = { onFullscreenChange(false) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Schließen", tint = EvColors.TextPrimary)
                    }
                }
            }

            if (isLandscape) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    val targetRatio = 16f / 9f
                    val fitByHeightWidth = maxHeight * targetRatio
                    val cardWidth = if (fitByHeightWidth <= maxWidth) fitByHeightWidth else maxWidth
                    val cardHeight = if (fitByHeightWidth <= maxWidth) maxHeight else maxWidth / targetRatio

                    Card(
                        modifier = Modifier.size(width = cardWidth, height = cardHeight),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, EvColors.Cyan.copy(alpha = 0.5f)),
                        colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
                    ) {
                        InteractiveDashboardPreview(
                            layoutConfig = layoutConfig,
                            pageIndex = safePageIdx,
                            snapshot = snapshot,
                            isDark = previewDark,
                            onTabTapped = { selectedPageIdx = it },
                            onWidgetTapped = {
                                isCreatingNewWidget = false
                                editingSlot = it
                            },
                            onWidgetMoved = { s, c, r -> moveWidget(s, c, r) },
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, EvColors.Cyan.copy(alpha = 0.5f)),
                    colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
                ) {
                    InteractiveDashboardPreview(
                        layoutConfig = layoutConfig,
                        pageIndex = safePageIdx,
                        snapshot = snapshot,
                        isDark = previewDark,
                        onTabTapped = { selectedPageIdx = it },
                        onWidgetTapped = {
                            isCreatingNewWidget = false
                            editingSlot = it
                        },
                        onWidgetMoved = { s, c, r -> moveWidget(s, c, r) },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    currentPage?.widgets.orEmpty().forEach { slot ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    isCreatingNewWidget = false
                                    editingSlot = slot
                                },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, EvColors.Border),
                            colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(slot.label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = EvColors.TextPrimary)
                                    Text(
                                        text = "${slot.type.uppercase()} • ${slot.pid} • [${slot.col},${slot.row}] ${slot.colSpan}×${slot.rowSpan}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = EvColors.TextSecondary
                                    )
                                }
                                Icon(Icons.Rounded.Edit, contentDescription = "Bearbeiten", tint = EvColors.CyanLight, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Interaktiver Dashboard-Editor",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = EvColors.TextPrimary
                )
                Text(
                    text = "Tippen = Bearbeiten • Ziehen = Verschieben",
                    fontSize = 11.sp,
                    color = EvColors.TextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = layoutConfig.endlessScrollMode,
                        onClick = {
                            scope.launch {
                                CorsaDashRuntime.saveLayout(
                                    layoutConfig.copy(endlessScrollMode = !layoutConfig.endlessScrollMode)
                                )
                            }
                        },
                        label = { Text("Endlos", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = previewDark,
                        onClick = { previewDark = !previewDark },
                        label = { Text(if (previewDark) "Nacht" else "Tag", fontSize = 12.sp) }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = { onFullscreenChange(true) }) {
                        Icon(EvIcons.Fullscreen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" Vollbild", fontSize = 12.sp)
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.5.dp, EvColors.Cyan.copy(alpha = 0.45f)),
                colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
            ) {
                InteractiveDashboardPreview(
                    layoutConfig = layoutConfig,
                    pageIndex = safePageIdx,
                    snapshot = snapshot,
                    isDark = previewDark,
                    onTabTapped = { selectedPageIdx = it },
                    onWidgetTapped = {
                        isCreatingNewWidget = false
                        editingSlot = it
                    },
                    onWidgetMoved = { s, c, r -> moveWidget(s, c, r) },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(14.dp))
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pages.forEachIndexed { idx, page ->
                    FilterChip(
                        selected = idx == safePageIdx,
                        onClick = { selectedPageIdx = idx },
                        label = {
                            Text(
                                text = if (page.enabled) page.title else "${page.title} (Aus)",
                                color = if (page.enabled) EvColors.TextPrimary else EvColors.TextSecondary
                            )
                        }
                    )
                }
                OutlinedButton(onClick = { showAddTabDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" Tab")
                }
            }

            // Clean 2-row action bar: Primary actions on Row 1, Tab controls on Row 2 (zero squishing!)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { openNewWidgetEditor() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Neue Karte", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { showLibraryDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(EvIcons.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" Vorlagen")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentPage != null) {
                    OutlinedButton(
                        onClick = {
                            val updatedPages = pages.mapIndexed { idx, p ->
                                if (idx == safePageIdx) p.copy(enabled = !p.enabled) else p
                            }
                            scope.launch {
                                CorsaDashRuntime.saveLayout(layoutConfig.copy(pages = updatedPages))
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (currentPage.enabled) EvIcons.Visibility else EvIcons.VisibilityOff,
                            contentDescription = null,
                            tint = if (currentPage.enabled) EvColors.Emerald else EvColors.Amber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(if (currentPage.enabled) " Sichtbar" else " Ausgeblendet", fontSize = 12.sp)
                    }
                }
                if (pages.size > 1 && currentPage != null) {
                    OutlinedButton(
                        onClick = {
                            val updatedPages = pages.filterIndexed { i, _ -> i != safePageIdx }
                            selectedPageIdx = (safePageIdx - 1).coerceAtLeast(0)
                            scope.launch {
                                CorsaDashRuntime.saveLayout(layoutConfig.copy(pages = updatedPages))
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = EvColors.Red, modifier = Modifier.size(16.dp))
                        Text(" Tab löschen", color = EvColors.Red, fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            CorsaDashRuntime.resetLayout()
                            selectedPageIdx = 0
                        }
                    }
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" Reset", fontSize = 12.sp)
                }
            }

            if (currentPage != null) {
                Text(
                    text = "Widgets auf „${currentPage.title}“ (${currentPage.widgets.size})",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = EvColors.TextSecondary
                )

                currentPage.widgets.forEach { slot ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                isCreatingNewWidget = false
                                editingSlot = slot
                            },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, EvColors.Border),
                        colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(slot.label, fontWeight = FontWeight.Bold, color = EvColors.TextPrimary)
                                Text(
                                    text = "${slot.type.uppercase()} • ${slot.pid} • [${slot.col},${slot.row}] ${slot.colSpan}×${slot.rowSpan}" +
                                        if (slot.autoScale) " • AUTO" else "",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = EvColors.TextSecondary
                                )
                            }
                            Row {
                                IconButton(onClick = {
                                    scope.launch { CorsaDashRuntime.layoutStore.saveCardTemplate(slot) }
                                }) {
                                    Icon(EvIcons.Bookmark, contentDescription = "Als Karte speichern", tint = EvColors.Emerald)
                                }
                                IconButton(onClick = {
                                    isCreatingNewWidget = false
                                    editingSlot = slot
                                }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Bearbeiten", tint = EvColors.CyanLight)
                                }
                                IconButton(onClick = { pendingDeleteSlot = slot }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Löschen", tint = EvColors.Red)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // My Dashboards & My Cards Library Dialog
    if (showLibraryDialog) {
        var profileName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showLibraryDialog = false },
            title = { Text("Meine Dashboards & Karten", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Aktuelles Dashboard sichern", fontSize = 12.sp, color = EvColors.CyanLight, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = profileName,
                            onValueChange = { profileName = it },
                            label = { Text("Profil-Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            val name = profileName
                            profileName = ""
                            scope.launch { CorsaDashRuntime.layoutStore.saveDashboardProfile(name, layoutConfig) }
                        }) {
                            Text("Sichern")
                        }
                    }

                    Text("Meine Dashboards (${savedDashboards.size})", fontSize = 12.sp, color = EvColors.TextSecondary, fontWeight = FontWeight.Bold)
                    if (savedDashboards.isEmpty()) {
                        Text("Noch keine Dashboards gespeichert.", fontSize = 12.sp, color = EvColors.TextSecondary)
                    } else {
                        savedDashboards.forEach { profile ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(EvColors.SurfaceElevated)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(profile.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = EvColors.TextPrimary)
                                    Text("${profile.config.pages.size} Tabs", fontSize = 11.sp, color = EvColors.TextSecondary)
                                }
                                Row {
                                    TextButton(onClick = {
                                        showLibraryDialog = false
                                        selectedPageIdx = 0
                                        scope.launch { CorsaDashRuntime.saveLayout(profile.config) }
                                    }) { Text("Laden") }
                                    TextButton(onClick = {
                                        scope.launch { CorsaDashRuntime.layoutStore.deleteDashboardProfile(profile.id) }
                                    }) { Text("✕", color = EvColors.Red) }
                                }
                            }
                        }
                    }

                    Text("Meine Karten (${savedCards.size})", fontSize = 12.sp, color = EvColors.TextSecondary, fontWeight = FontWeight.Bold)
                    if (savedCards.isEmpty()) {
                        Text("Tippe auf das Lesezeichen-Icon einer Karte, um sie hier zu sichern.", fontSize = 12.sp, color = EvColors.TextSecondary)
                    } else {
                        savedCards.forEach { card ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(EvColors.SurfaceElevated)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(card.label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = EvColors.TextPrimary)
                                    Text("${card.type.uppercase()} • ${card.pid} • ${card.colSpan}×${card.rowSpan}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = EvColors.TextSecondary)
                                }
                                Row {
                                    TextButton(onClick = {
                                        if (currentPage != null) {
                                            val clone = card.copy(id = "widget_${System.currentTimeMillis()}")
                                            val updatedPages = pages.mapIndexed { idx, p ->
                                                if (idx == safePageIdx) p.copy(widgets = p.widgets + clone) else p
                                            }
                                            showLibraryDialog = false
                                            scope.launch { CorsaDashRuntime.saveLayout(layoutConfig.copy(pages = updatedPages)) }
                                        }
                                    }) { Text("+ Einfügen") }
                                    TextButton(onClick = {
                                        scope.launch { CorsaDashRuntime.layoutStore.deleteCardTemplate(card.id) }
                                    }) { Text("✕", color = EvColors.Red) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLibraryDialog = false }) { Text("Schließen") }
            }
        )
    }

    // Add Tab Dialog
    if (showAddTabDialog) {
        var newTabTitle by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddTabDialog = false },
            title = { Text("Neuer Dashboard-Tab") },
            text = {
                OutlinedTextField(
                    value = newTabTitle,
                    onValueChange = { newTabTitle = it },
                    label = { Text("Tab-Name (z. B. Trip, Sport, Autobahn)") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val clean = newTabTitle.trim().ifEmpty { "Tab ${pages.size + 1}" }
                    val newPage = DashboardPageConfig(
                        id = "custom_${System.currentTimeMillis()}",
                        title = clean,
                        widgets = listOf(
                            WidgetSlotConfig(
                                id = "w_${System.currentTimeMillis()}_1",
                                type = "arc_gauge",
                                label = "Leistung",
                                pid = "hv_power",
                                unit = "kW",
                                col = 0,
                                row = 0,
                                colSpan = 5,
                                rowSpan = 4,
                                min = -45.0,
                                max = 105.0
                            ),
                            WidgetSlotConfig(
                                id = "w_${System.currentTimeMillis()}_2",
                                type = "big_number",
                                label = "Trip Ø",
                                pid = TripTracker.PID_TRIP_CONSUMPTION,
                                unit = "kWh/100",
                                col = 5,
                                row = 0,
                                colSpan = 7,
                                rowSpan = 2
                            ),
                            WidgetSlotConfig(
                                id = "w_${System.currentTimeMillis()}_3",
                                type = "sparkline",
                                label = "Momentanverbrauch",
                                pid = TripTracker.PID_INST_CONSUMPTION,
                                unit = "kWh/100",
                                col = 5,
                                row = 2,
                                colSpan = 7,
                                rowSpan = 2,
                                autoScale = true
                            )
                        )
                    )
                    val updated = layoutConfig.copy(pages = pages + newPage)
                    selectedPageIdx = pages.size
                    showAddTabDialog = false
                    scope.launch { CorsaDashRuntime.saveLayout(updated) }
                }) {
                    Text("Erstellen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTabDialog = false }) { Text("Abbrechen") }
            }
        )
    }

    // Confirm Card Deletion Dialog (with Save-to-Templates safety net!)
    pendingDeleteSlot?.let { slotToDelete ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSlot = null },
            title = { Text("Karte wirklich löschen?", fontWeight = FontWeight.Bold) },
            text = {
                Text("„${slotToDelete.label}“ wird von diesem Tab entfernt. Du kannst sie vor dem Löschen auch in „Meine Karten“ sichern.")
            },
            confirmButton = {
                Button(onClick = {
                    if (currentPage != null) {
                        val updatedWidgets = currentPage.widgets.filterNot { it.id == slotToDelete.id }
                        val updatedPages = pages.mapIndexed { idx, p ->
                            if (idx == safePageIdx) p.copy(widgets = updatedWidgets) else p
                        }
                        scope.launch { CorsaDashRuntime.saveLayout(layoutConfig.copy(pages = updatedPages)) }
                    }
                    pendingDeleteSlot = null
                }) {
                    Text("Löschen")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        if (currentPage != null) {
                            val updatedWidgets = currentPage.widgets.filterNot { it.id == slotToDelete.id }
                            val updatedPages = pages.mapIndexed { idx, p ->
                                if (idx == safePageIdx) p.copy(widgets = updatedWidgets) else p
                            }
                            scope.launch {
                                CorsaDashRuntime.layoutStore.saveCardTemplate(slotToDelete)
                                CorsaDashRuntime.saveLayout(layoutConfig.copy(pages = updatedPages))
                            }
                        }
                        pendingDeleteSlot = null
                    }) {
                        Text("Sichern & Löschen", color = EvColors.Emerald)
                    }
                    TextButton(onClick = { pendingDeleteSlot = null }) {
                        Text("Abbrechen")
                    }
                }
            }
        )
    }

    // Full Widget Editor Bottom Sheet (wide, unified EV dark theme)
    editingSlot?.let { initialSlot ->
        WidgetEditorSheet(
            initial = initialSlot,
            pidChoices = allPidChoices,
            onDismiss = { editingSlot = null },
            onSaveTemplate = { templateSlot ->
                scope.launch { CorsaDashRuntime.layoutStore.saveCardTemplate(templateSlot) }
            },
            onDelete = if (!isCreatingNewWidget && currentPage != null) {
                {
                    editingSlot = null
                    pendingDeleteSlot = initialSlot
                }
            } else null,
            onSave = { savedSlot ->
                if (currentPage != null) {
                    val updatedWidgets = if (isCreatingNewWidget) {
                        currentPage.widgets + savedSlot
                    } else {
                        currentPage.widgets.map { if (it.id == savedSlot.id) savedSlot else it }
                    }
                    val updatedPages = pages.mapIndexed { idx, p ->
                        if (idx == safePageIdx) p.copy(widgets = updatedWidgets) else p
                    }
                    scope.launch {
                        CorsaDashRuntime.saveLayout(layoutConfig.copy(pages = updatedPages))
                    }
                }
                editingSlot = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun WidgetEditorSheet(
    initial: WidgetSlotConfig,
    pidChoices: List<PidChoice>,
    onDismiss: () -> Unit,
    onSaveTemplate: (WidgetSlotConfig) -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (WidgetSlotConfig) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var type by remember { mutableStateOf(initial.type) }
    var pid by remember { mutableStateOf(initial.pid) }
    var label by remember { mutableStateOf(initial.label) }
    var unit by remember { mutableStateOf(initial.unit) }
    var col by remember { mutableIntStateOf(initial.col) }
    var row by remember { mutableIntStateOf(initial.row) }
    var colSpan by remember { mutableIntStateOf(initial.colSpan) }
    var rowSpan by remember { mutableIntStateOf(initial.rowSpan) }
    var autoScale by remember { mutableStateOf(initial.autoScale) }
    var minVal by remember { mutableStateOf(initial.min.toString()) }
    var maxVal by remember { mutableStateOf(initial.max.toString()) }
    var arrayMode by remember { mutableStateOf(initial.arrayMode) }
    var showPidPicker by remember { mutableStateOf(false) }
    var pidSearch by remember { mutableStateOf("") }

    val selectedPidChoice = pidChoices.firstOrNull { it.id == pid }

    val widgetTypes = listOf(
        "big_number" to "Zahl",
        "arc_gauge" to "Tacho",
        "sparkline" to "Graph",
        "battery_bar" to "Akku-Bar"
    )

    fun buildSlot(): WidgetSlotConfig = initial.copy(
        type = type,
        pid = pid,
        label = label.trim().ifEmpty { pid },
        unit = unit.trim(),
        col = col.coerceIn(0, 11),
        row = row.coerceIn(0, 5),
        colSpan = colSpan.coerceIn(1, 12 - col),
        rowSpan = rowSpan.coerceIn(1, 6 - row),
        autoScale = autoScale,
        min = minVal.toDoubleOrNull() ?: initial.min,
        max = maxVal.toDoubleOrNull() ?: initial.max,
        arrayMode = arrayMode
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = EvColors.Background,
        contentColor = EvColors.TextPrimary,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Unified Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Widget konfigurieren",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = EvColors.TextPrimary
                    )
                    Text(
                        text = "${type.uppercase()} • $pid • Grid [$col,$row] ${colSpan}×${rowSpan}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = EvColors.CyanLight
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onSaveTemplate(buildSlot()) }) {
                        Icon(EvIcons.Bookmark, contentDescription = "Als Vorlage sichern", tint = EvColors.Emerald)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Schließen", tint = EvColors.TextSecondary)
                    }
                }
            }

            HorizontalDivider(color = EvColors.Border)

            // Scrollable Body
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Widget Type
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Widget-Typ", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = EvColors.TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        widgetTypes.forEach { (key, title) ->
                            FilterChip(
                                selected = type == key,
                                onClick = { type = key },
                                label = { Text(title, fontSize = 12.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 2. Data Source (52 PIDs with live search, unified card design)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Datenquelle (${pidChoices.size} Sensoren)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = EvColors.TextSecondary
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPidPicker = !showPidPicker },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, EvColors.Cyan.copy(alpha = 0.55f)),
                        colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedPidChoice?.label ?: pid,
                                    fontWeight = FontWeight.Bold,
                                    color = EvColors.TextPrimary,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "$pid • ${selectedPidChoice?.unit ?: unit}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = EvColors.CyanLight
                                )
                            }
                            Text(
                                text = if (showPidPicker) "Schließen ▲" else "Auswählen ▼",
                                fontSize = 12.sp,
                                color = EvColors.CyanLight,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (showPidPicker) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, EvColors.Border),
                            colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = pidSearch,
                                    onValueChange = { pidSearch = it },
                                    label = { Text("Sensor suchen", maxLines = 1) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                val q = pidSearch.trim().lowercase()
                                val filtered = if (q.isEmpty()) pidChoices else pidChoices.filter {
                                    it.label.lowercase().contains(q) || it.id.lowercase().contains(q) || it.unit.lowercase().contains(q)
                                }
                                val grouped = filtered.groupBy { it.group }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    grouped.forEach { (groupTitle, items) ->
                                        Text(
                                            text = groupTitle.uppercase(),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = EvColors.CyanLight,
                                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                                        )
                                        items.forEach { choice ->
                                            val isSelected = choice.id == pid
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        if (isSelected) EvColors.Cyan.copy(alpha = 0.2f)
                                                        else EvColors.SurfaceElevated
                                                    )
                                                    .clickable {
                                                        pid = choice.id
                                                        label = choice.label
                                                        unit = choice.unit
                                                        minVal = choice.minDefault.toString()
                                                        maxVal = choice.maxDefault.toString()
                                                        arrayMode = choice.isArray
                                                        if (choice.isArray) type = "sparkline"
                                                        showPidPicker = false
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = choice.label,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) EvColors.CyanLight else EvColors.TextPrimary,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = choice.unit,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    color = EvColors.TextSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Title & Unit
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Titel") },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("Einheit") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 4. Grid Size & Position
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Größe & Position (12×6 Grid)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = EvColors.TextSecondary)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            "3×2" to (3 to 2),
                            "4×2" to (4 to 2),
                            "5×4 Tacho" to (5 to 4),
                            "6×2" to (6 to 2),
                            "7×2 Graph" to (7 to 2),
                            "12×2 Breit" to (12 to 2)
                        ).forEach { (name, dims) ->
                            FilterChip(
                                selected = colSpan == dims.first && rowSpan == dims.second,
                                onClick = {
                                    colSpan = dims.first.coerceAtMost(12 - col)
                                    rowSpan = dims.second.coerceAtMost(6 - row)
                                },
                                label = { Text(name, fontSize = 11.sp) }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        GridStepper("Spalte", col, 0, (12 - colSpan).coerceAtLeast(0), { col = it }, Modifier.weight(1f))
                        GridStepper("Zeile", row, 0, (6 - rowSpan).coerceAtLeast(0), { row = it }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        GridStepper("Breite", colSpan, 1, 12 - col, { colSpan = it }, Modifier.weight(1f))
                        GridStepper("Höhe", rowSpan, 1, 6 - row, { rowSpan = it }, Modifier.weight(1f))
                    }
                }

                // 5. Auto Min/Max & Scale
                if (type == "arc_gauge" || type == "sparkline") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, EvColors.Border),
                        colors = CardDefaults.cardColors(containerColor = EvColors.Surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Auto Min / Max", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = EvColors.TextPrimary)
                                    Text("Skala passt sich automatisch an Live-Werte an", fontSize = 11.sp, color = EvColors.TextSecondary)
                                }
                                Switch(checked = autoScale, onCheckedChange = { autoScale = it })
                            }

                            if (!autoScale) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = minVal,
                                        onValueChange = { minVal = it },
                                        label = { Text("Min") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = maxVal,
                                        onValueChange = { maxVal = it },
                                        label = { Text("Max") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = EvColors.Border)

            // Unified Sticky Bottom Action Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDelete != null) {
                    OutlinedButton(
                        onClick = onDelete,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = EvColors.Red, modifier = Modifier.size(18.dp))
                        Text(" Löschen", color = EvColors.Red)
                    }
                }
                Button(
                    onClick = { onSave(buildSlot()) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Übernehmen", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun GridStepper(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(EvColors.SurfaceElevated)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 11.sp, color = EvColors.TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "−",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (value > min) EvColors.CyanLight else EvColors.Border,
                modifier = Modifier
                    .clickable(enabled = value > min) { onChange((value - 1).coerceAtLeast(min)) }
                    .padding(horizontal = 6.dp)
            )
            Text(
                text = value.toString(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = EvColors.TextPrimary
            )
            Text(
                text = "+",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (value < max) EvColors.CyanLight else EvColors.Border,
                modifier = Modifier
                    .clickable(enabled = value < max) { onChange((value + 1).coerceAtMost(max)) }
                    .padding(horizontal = 6.dp)
            )
        }
    }
}

@Composable
private fun InteractiveDashboardPreview(
    layoutConfig: DashboardLayoutConfig,
    pageIndex: Int,
    snapshot: TelemetrySnapshot,
    isDark: Boolean,
    onTabTapped: (Int) -> Unit,
    onWidgetTapped: (WidgetSlotConfig) -> Unit,
    onWidgetMoved: (WidgetSlotConfig, Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val pages = remember(layoutConfig) { LayoutLoader.buildPages(layoutConfig) }
    val renderer = remember {
        SurfaceRenderer(
            initialPages = pages,
            telemetryFlow = CorsaDashRuntime.repository.snapshot,
            isDarkModeProvider = { isDark }
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            InteractivePreviewView(ctx, renderer, onTabTapped, onWidgetTapped, onWidgetMoved)
        },
        update = { view ->
            renderer.updatePages(pages)
            renderer.setPage(pageIndex)
            view.updateCallbacks(onTabTapped, onWidgetTapped, onWidgetMoved)
            view.updateState(snapshot, isDark)
        }
    )
}

@SuppressLint("ViewConstructor")
private class InteractivePreviewView(
    context: Context,
    private val renderer: SurfaceRenderer,
    private var onTabTapped: (Int) -> Unit,
    private var onWidgetTapped: (WidgetSlotConfig) -> Unit,
    private var onWidgetMoved: (WidgetSlotConfig, Int, Int) -> Unit
) : View(context) {

    private var snapshot: TelemetrySnapshot = TelemetrySnapshot()
    private var isDark: Boolean = true

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var activeSlot: WidgetSlotConfig? = null
    private var isDragging = false
    private var dragTargetCol = 0
    private var dragTargetRow = 0

    fun updateCallbacks(
        tabCb: (Int) -> Unit,
        tapCb: (WidgetSlotConfig) -> Unit,
        moveCb: (WidgetSlotConfig, Int, Int) -> Unit
    ) {
        onTabTapped = tabCb
        onWidgetTapped = tapCb
        onWidgetMoved = moveCb
    }

    fun updateState(newSnapshot: TelemetrySnapshot, dark: Boolean) {
        snapshot = newSnapshot
        isDark = dark
        invalidate()
    }

    private fun virtualDpi(): Int = ((width / 1280f) * 160f).toInt().coerceAtLeast(60)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isDragging = false
                activeSlot = renderer.hitTestWidget(event.x, event.y)
                if (activeSlot != null) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val slot = activeSlot ?: return false
                if (!isDragging && (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)) {
                    isDragging = true
                }
                if (isDragging) {
                    val cell = renderer.pixelToGridCell(event.x, event.y)
                    if (cell != null) {
                        val (rawCol, rawRow) = cell
                        dragTargetCol = (rawCol - slot.colSpan / 2).coerceIn(0, 12 - slot.colSpan)
                        dragTargetRow = (rawRow - slot.rowSpan / 2).coerceIn(0, 6 - slot.rowSpan)
                        renderer.setDragGhost(dragTargetCol, dragTargetRow, slot.colSpan, slot.rowSpan, virtualDpi())
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val slot = activeSlot
                if (isDragging && slot != null) {
                    renderer.clearDragGhost()
                    onWidgetMoved(slot, dragTargetCol, dragTargetRow)
                    invalidate()
                } else {
                    val tabHit = renderer.handleTabTap(event.x, event.y)
                    if (tabHit != null) {
                        onTabTapped(tabHit)
                    } else if (slot != null) {
                        onWidgetTapped(slot)
                    }
                }
                activeSlot = null
                isDragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                renderer.clearDragGhost()
                activeSlot = null
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        renderer.renderPreview(canvas, width, height, virtualDpi(), snapshot, isDark)
    }
}
