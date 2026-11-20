package com.elmdash.core.protocol

data class DecodedDtc(
    val ecuKey: String,
    val ecuName: String,
    val code: String,
    val failureTypeByte: String,
    val statusByte: Int,
    val isActive: Boolean,
    val isPending: Boolean,
    val isConfirmed: Boolean,
    val isMilOn: Boolean,
    val description: String
)

data class EcuDiagnosticReport(
    val ecuKey: String,
    val ecuName: String,
    val txHeader: String,
    val reachable: Boolean,
    val dtcs: List<DecodedDtc>,
    val error: String? = null
)

/**
 * Pure Kotlin UDS ISO 14229 Service 0x19 (ReadDTCInformation, sub-function 0x02) decoder.
 * Decodes 3-byte UDS DTCs + 1-byte status masks into SAE J2012 codes (e.g. P0A7F-00, U1113-87).
 */
object DtcDecoder {

    private val KNOWN_DESCRIPTIONS = mapOf(
        "P0A7F" to "HV-Batterie Alterung / Kapazitätsgrenze",
        "P0A80" to "HV-Batterieblock Spannungsabweichung",
        "P0A1F" to "BMS Steuergerät interner Status",
        "P0AA6" to "HV-Isolationswiderstand zu niedrig",
        "P0C73" to "HV-Kühlmittelpumpe / Thermomanagement",
        "U0100" to "Kommunikationsverlust zum Antriebssteuergerät (VCU)",
        "U0111" to "Kommunikationsverlust zum Batterie-Steuergerät (BMS)",
        "U1113" to "Kommunikationsfehler Ladegerät (OBC) / Wallbox-Handshake",
        "U1208" to "CAN-Botschaft VCU unplausibel (sporadisch)",
        "C1201" to "ESP / Rekuperations-Koordination Hinweis",
        "B1000" to "Steuergerät Konfiguration / Speicher-Hinweis"
    )

    fun decodeUds1902(payload: ByteArray, ecuKey: String, ecuName: String): List<DecodedDtc> {
        if (payload.size < 3) return emptyList()
        val sid = payload[0].toInt() and 0xFF
        if (sid != 0x59) return emptyList() // Positive response to 0x19 is 0x59

        // Format: 59 <subFunction:02> <availabilityMask> [DTC_HI DTC_MID DTC_LO STATUS] * N
        val dtcs = mutableListOf<DecodedDtc>()
        var idx = 3
        while (idx + 3 < payload.size) {
            val b0 = payload[idx].toInt() and 0xFF
            val b1 = payload[idx + 1].toInt() and 0xFF
            val ftb = payload[idx + 2].toInt() and 0xFF
            val status = payload[idx + 3].toInt() and 0xFF
            idx += 4

            if (b0 == 0 && b1 == 0 && ftb == 0) continue

            val code = formatSaeCode(b0, b1)
            val ftbHex = String.format("%02X", ftb)
            dtcs.add(
                DecodedDtc(
                    ecuKey = ecuKey,
                    ecuName = ecuName,
                    code = code,
                    failureTypeByte = ftbHex,
                    statusByte = status,
                    isActive = (status and 0x01) != 0,
                    isPending = (status and 0x04) != 0,
                    isConfirmed = (status and 0x08) != 0,
                    isMilOn = (status and 0x80) != 0,
                    description = describe(code)
                )
            )
        }
        return dtcs
    }

    private fun formatSaeCode(b0: Int, b1: Int): String {
        val prefix = when ((b0 shr 6) and 0x03) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val d1 = (b0 shr 4) and 0x03
        val d2 = b0 and 0x0F
        val d3 = (b1 shr 4) and 0x0F
        val d4 = b1 and 0x0F
        return "$prefix$d1${d2.toString(16).uppercase()}${d3.toString(16).uppercase()}${d4.toString(16).uppercase()}"
    }

    private fun describe(code: String): String {
        KNOWN_DESCRIPTIONS[code]?.let { return it }
        return when (code.firstOrNull()) {
            'P' -> "Antriebsstrang / HV-System Fehlercode ($code)"
            'U' -> "CAN-Bus / Steuergeräte-Kommunikation ($code)"
            'C' -> "Fahrwerk / ABS / Rekuperation ($code)"
            'B' -> "Karosserie / Komfort-Steuergerät ($code)"
            else -> "Diagnose-Fehlercode ($code)"
        }
    }
}
