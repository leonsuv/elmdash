package com.elmdash.core.protocol

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class PollingTier {
    FAST,
    MEDIUM,
    SLOW
}

@Serializable
enum class ValueType {
    @SerialName("scalar")
    SCALAR,

    @SerialName("array")
    ARRAY
}

@Serializable
enum class Endianness {
    BIG,
    LITTLE
}

@Serializable
data class ProtocolInfo(
    val elmProtocol: String = "ATSP6",
    val bus: String = "ISO 15765-4 CAN (11-bit, 500 kbaud)",
    val udsService: String = "22"
)

@Serializable
data class EcuDefinition(
    val name: String,
    val txHeader: String,
    val rxHeader: String? = null,
    val verified: Boolean = false
)

@Serializable
data class PidDefinition(
    val id: String,
    val name: String,
    val ecu: String,
    val service: String = "22",
    val did: String,
    val tier: PollingTier = PollingTier.MEDIUM,
    val expectedFrames: Int = 1,
    val valueType: ValueType = ValueType.SCALAR,
    val elementCount: Int = 1,
    val byteOffset: Int = 0,
    val byteLength: Int = 2,
    val endianness: Endianness = Endianness.BIG,
    val signed: Boolean = false,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val unit: String = "",
    val min: Double = -1e9,
    val max: Double = 1e9,
    val verified: Boolean = false
) {
    /**
     * Builds the ELM327 UDS request command, appending expectedFrames hex digit when > 0
     * so the adapter returns immediately instead of waiting for ATST timeout.
     */
    fun buildRequestCommand(): String {
        val base = "${service.uppercase()}${did.uppercase()}"
        return if (expectedFrames in 1..15) {
            "$base${expectedFrames.toString(16).uppercase()}"
        } else {
            base
        }
    }
}

@Serializable
data class PidCatalog(
    val schemaVersion: Int = 1,
    val vehicle: String = "",
    val protocol: ProtocolInfo = ProtocolInfo(),
    val notes: String = "",
    val ecus: Map<String, EcuDefinition> = emptyMap(),
    val pids: List<PidDefinition> = emptyList()
)

/**
 * Single Source of Truth registry loaded exclusively from corsa_e_pids.json.
 */
class PidRegistry(val catalog: PidCatalog) {

    private val pidById: Map<String, PidDefinition> = catalog.pids.associateBy { it.id }

    val allPids: List<PidDefinition>
        get() = catalog.pids

    fun getPid(id: String): PidDefinition? = pidById[id]

    fun getEcu(key: String): EcuDefinition? = catalog.ecus[key]

    fun pidsForTier(tier: PollingTier): List<PidDefinition> =
        catalog.pids.filter { it.tier == tier }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun fromJson(rawJson: String): PidRegistry {
            val catalog = json.decodeFromString(PidCatalog.serializer(), rawJson)
            return PidRegistry(catalog)
        }

        fun fromAssets(context: Context, assetName: String = "corsa_e_pids.json"): PidRegistry {
            val raw = context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return fromJson(raw)
        }
    }
}
