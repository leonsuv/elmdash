package com.elmdash.ui.engine

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.elmdash.ui.widgets.ArcGaugeWidget
import com.elmdash.ui.widgets.BatteryBarWidget
import com.elmdash.ui.widgets.BigNumberWidget
import com.elmdash.ui.widgets.SparklineWidget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.layoutDataStore by preferencesDataStore(name = "elmdash_layout_store")

@Serializable
data class DashboardLayoutConfig(
    val schemaVersion: Int = 1,
    val columns: Int = 12,
    val rows: Int = 6,
    val endlessScrollMode: Boolean = false,
    val pages: List<DashboardPageConfig> = emptyList()
)

@Serializable
data class DashboardPageConfig(
    val id: String,
    val title: String,
    val enabled: Boolean = true,
    val widgets: List<WidgetSlotConfig>
)

@Serializable
data class WidgetSlotConfig(
    val id: String,
    val type: String,
    val label: String,
    val pid: String,
    val secondaryPid: String? = null,
    val unit: String = "",
    val decimals: Int = 1,
    val col: Int,
    val row: Int,
    val colSpan: Int,
    val rowSpan: Int,
    val min: Double = 0.0,
    val max: Double = 100.0,
    val autoScale: Boolean = false,
    val warnThreshold: Double? = null,
    val critThreshold: Double? = null,
    val showSign: Boolean = false,
    val arrayMode: Boolean = false
)

data class BoundWidget(
    val slot: WidgetSlotConfig,
    val widget: DashboardWidget
)

data class DashboardPage(
    val id: String,
    val title: String,
    val widgets: List<BoundWidget>
)

object LayoutLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun parseConfig(raw: String): DashboardLayoutConfig =
        json.decodeFromString(DashboardLayoutConfig.serializer(), raw)

    fun toJson(config: DashboardLayoutConfig): String =
        json.encodeToString(DashboardLayoutConfig.serializer(), config)

    fun buildPages(config: DashboardLayoutConfig, onlyEnabled: Boolean = false): List<DashboardPage> {
        val source = if (onlyEnabled) {
            config.pages.filter { it.enabled }.ifEmpty { config.pages.take(1) }
        } else {
            config.pages
        }
        return source.map { page ->
            DashboardPage(
                id = page.id,
                title = page.title,
                widgets = page.widgets.map { slot ->
                    BoundWidget(slot = slot, widget = instantiateWidget(slot))
                }
            )
        }
    }

    fun fromJson(raw: String): List<DashboardPage> = buildPages(parseConfig(raw))

    fun loadDefaultConfig(context: Context, assetName: String = "dashboard_layout.json"): DashboardLayoutConfig {
        val raw = context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parseConfig(raw)
    }

    fun fromAssets(context: Context, assetName: String = "dashboard_layout.json"): List<DashboardPage> =
        buildPages(loadDefaultConfig(context, assetName))

    private fun instantiateWidget(slot: WidgetSlotConfig): DashboardWidget {
        return when (slot.type.lowercase()) {
            "big_number" -> BigNumberWidget(
                id = slot.id,
                pid = slot.pid,
                label = slot.label,
                unit = slot.unit,
                decimals = slot.decimals,
                warnThreshold = slot.warnThreshold,
                critThreshold = slot.critThreshold,
                showSign = slot.showSign
            )

            "arc_gauge" -> ArcGaugeWidget(
                id = slot.id,
                pid = slot.pid,
                label = slot.label,
                unit = slot.unit,
                min = slot.min,
                max = slot.max,
                decimals = slot.decimals,
                autoScale = slot.autoScale
            )

            "sparkline" -> SparklineWidget(
                id = slot.id,
                pid = slot.pid,
                label = slot.label,
                unit = slot.unit,
                minHint = slot.min,
                maxHint = slot.max,
                arrayMode = slot.arrayMode,
                autoScale = slot.autoScale
            )

            "battery_bar" -> BatteryBarWidget(
                id = slot.id,
                socPid = slot.pid,
                tempPid = slot.secondaryPid ?: "battery_temp",
                label = slot.label
            )

            else -> BigNumberWidget(
                id = slot.id,
                pid = slot.pid,
                label = slot.label,
                unit = slot.unit,
                decimals = slot.decimals
            )
        }
    }
}

@Serializable
data class SavedDashboardProfile(
    val id: String,
    val name: String,
    val savedAtMs: Long = System.currentTimeMillis(),
    val config: DashboardLayoutConfig
)

/**
 * DataStore-backed persistence for custom user dashboards, tabs, widget assignments,
 * plus reusable "My Dashboards" and "My Cards" libraries.
 */
class DashboardLayoutStore(private val context: Context) {

    private val libraryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val defaultConfig: DashboardLayoutConfig by lazy {
        LayoutLoader.loadDefaultConfig(context)
    }

    val configFlow: Flow<DashboardLayoutConfig> = context.layoutDataStore.data.map { prefs ->
        val saved = prefs[KEY_LAYOUT_JSON]
        if (saved.isNullOrBlank()) {
            defaultConfig
        } else {
            runCatching { LayoutLoader.parseConfig(saved) }.getOrDefault(defaultConfig)
        }
    }

    val savedDashboardsFlow: Flow<List<SavedDashboardProfile>> = context.layoutDataStore.data.map { prefs ->
        val raw = prefs[KEY_SAVED_DASHBOARDS_JSON] ?: return@map emptyList()
        runCatching { libraryJson.decodeFromString<List<SavedDashboardProfile>>(raw) }.getOrDefault(emptyList())
    }

    val savedCardsFlow: Flow<List<WidgetSlotConfig>> = context.layoutDataStore.data.map { prefs ->
        val raw = prefs[KEY_SAVED_CARDS_JSON] ?: return@map emptyList()
        runCatching { libraryJson.decodeFromString<List<WidgetSlotConfig>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun getConfig(): DashboardLayoutConfig = configFlow.first()

    suspend fun saveConfig(config: DashboardLayoutConfig) {
        val serialized = LayoutLoader.toJson(config)
        context.layoutDataStore.edit { prefs ->
            prefs[KEY_LAYOUT_JSON] = serialized
        }
    }

    suspend fun resetToDefault() {
        context.layoutDataStore.edit { prefs ->
            prefs.remove(KEY_LAYOUT_JSON)
        }
    }

    suspend fun saveDashboardProfile(name: String, config: DashboardLayoutConfig) {
        val existing = savedDashboardsFlow.first()
        val profile = SavedDashboardProfile(
            id = "dash_${System.currentTimeMillis()}",
            name = name.trim().ifEmpty { "Dashboard ${existing.size + 1}" },
            savedAtMs = System.currentTimeMillis(),
            config = config
        )
        val updated = listOf(profile) + existing
        context.layoutDataStore.edit { prefs ->
            prefs[KEY_SAVED_DASHBOARDS_JSON] = libraryJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(SavedDashboardProfile.serializer()),
                updated
            )
        }
    }

    suspend fun deleteDashboardProfile(id: String) {
        val updated = savedDashboardsFlow.first().filterNot { it.id == id }
        context.layoutDataStore.edit { prefs ->
            prefs[KEY_SAVED_DASHBOARDS_JSON] = libraryJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(SavedDashboardProfile.serializer()),
                updated
            )
        }
    }

    suspend fun saveCardTemplate(slot: WidgetSlotConfig) {
        val existing = savedCardsFlow.first()
        val template = slot.copy(id = "card_${System.currentTimeMillis()}")
        val updated = listOf(template) + existing
        context.layoutDataStore.edit { prefs ->
            prefs[KEY_SAVED_CARDS_JSON] = libraryJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(WidgetSlotConfig.serializer()),
                updated
            )
        }
    }

    suspend fun deleteCardTemplate(id: String) {
        val updated = savedCardsFlow.first().filterNot { it.id == id }
        context.layoutDataStore.edit { prefs ->
            prefs[KEY_SAVED_CARDS_JSON] = libraryJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(WidgetSlotConfig.serializer()),
                updated
            )
        }
    }

    companion object {
        private val KEY_LAYOUT_JSON = stringPreferencesKey("custom_dashboard_layout_json")
        private val KEY_SAVED_DASHBOARDS_JSON = stringPreferencesKey("saved_dashboards_library_json")
        private val KEY_SAVED_CARDS_JSON = stringPreferencesKey("saved_cards_library_json")
    }
}
