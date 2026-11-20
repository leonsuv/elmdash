package com.elmdash.core.protocol

/**
 * Decoded UDS Service 0x22 value.
 */
sealed class UdsParseResult {
    data class Scalar(val pidId: String, val value: Double) : UdsParseResult()
    data class ArrayValue(val pidId: String, val values: List<Double>) : UdsParseResult()
    data class NegativeResponse(val service: Int, val nrc: Int) : UdsParseResult()
    data class Error(val reason: String) : UdsParseResult()
}

/**
 * Pure Kotlin UDS (ISO 14229) Service 0x22 ReadDataByIdentifier decoder.
 * Uses exclusively the metadata from [PidDefinition] (loaded from corsa_e_pids.json).
 */
object UdsParser {

    fun parse(payload: ByteArray, pid: PidDefinition): UdsParseResult {
        if (payload.size < 3) {
            return UdsParseResult.Error("UDS payload too short (${payload.size} bytes)")
        }

        val sid = payload[0].toInt() and 0xFF
        if (sid == 0x7F) {
            val reqSid = payload[1].toInt() and 0xFF
            val nrc = payload[2].toInt() and 0xFF
            return UdsParseResult.NegativeResponse(reqSid, nrc)
        }

        val expectedPositiveSid = (pid.service.toInt(16) + 0x40) and 0xFF
        if (sid != expectedPositiveSid) {
            return UdsParseResult.Error(
                "Unexpected UDS SID: 0x${sid.toString(16).uppercase()}, expected 0x${expectedPositiveSid.toString(16).uppercase()}"
            )
        }

        val expectedDid = pid.did.toInt(16)
        val actualDid = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        if (actualDid != expectedDid) {
            return UdsParseResult.Error(
                "DID mismatch: got 0x${actualDid.toString(16).uppercase()}, expected 0x${pid.did.uppercase()}"
            )
        }

        val dataStart = 3 + pid.byteOffset
        return when (pid.valueType) {
            ValueType.SCALAR -> {
                if (dataStart + pid.byteLength > payload.size) {
                    return UdsParseResult.Error(
                        "Not enough bytes for ${pid.id}: need ${dataStart + pid.byteLength}, have ${payload.size}"
                    )
                }
                val value = decodeSingle(payload, dataStart, pid)
                UdsParseResult.Scalar(pid.id, value)
            }

            ValueType.ARRAY -> {
                val totalNeeded = dataStart + pid.elementCount * pid.byteLength
                if (totalNeeded > payload.size) {
                    return UdsParseResult.Error(
                        "Not enough bytes for array ${pid.id}: need $totalNeeded, have ${payload.size}"
                    )
                }
                val items = List(pid.elementCount) { idx ->
                    decodeSingle(payload, dataStart + idx * pid.byteLength, pid)
                }
                UdsParseResult.ArrayValue(pid.id, items)
            }
        }
    }

    private fun decodeSingle(payload: ByteArray, offset: Int, pid: PidDefinition): Double {
        var raw = 0L
        if (pid.endianness == Endianness.BIG) {
            for (i in 0 until pid.byteLength) {
                raw = (raw shl 8) or (payload[offset + i].toLong() and 0xFFL)
            }
        } else {
            for (i in (pid.byteLength - 1) downTo 0) {
                raw = (raw shl 8) or (payload[offset + i].toLong() and 0xFFL)
            }
        }

        val numeric: Long = if (pid.signed) {
            val bitCount = pid.byteLength * 8
            val signBit = 1L shl (bitCount - 1)
            if ((raw and signBit) != 0L) {
                raw - (1L shl bitCount)
            } else {
                raw
            }
        } else {
            raw
        }

        return numeric.toDouble() * pid.scale + pid.offset
    }
}
