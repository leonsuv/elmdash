package com.elmdash.core.protocol

/**
 * Result of assembling an ELM327 CAN/ISO-TP response into a flat UDS payload.
 */
sealed class IsoTpResult {
    data class Success(val payload: ByteArray) : IsoTpResult() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Success && payload.contentEquals(other.payload))

        override fun hashCode(): Int = payload.contentHashCode()
    }

    data class Error(val reason: String) : IsoTpResult()
}

/**
 * Reassembles ELM327 CAN responses into raw UDS payloads.
 * Supports:
 * 1. Single-frame ATCAF1 (e.g. "62D8151E8C" or "76C 62 D8 15 1E 8C")
 * 2. Single-frame raw PCI ATCAF0 (e.g. "0562D8151E8CAAAAAA")
 * 3. Multi-frame ELM327 auto-formatted ATCAF1 ("023\n0:62D830...\n1:...\n2:...")
 * 4. Multi-frame raw ISO-TP PCI ("102362D830...\n21...\n22..." with optional 3-hex CAN RX header)
 */
object IsoTpAssembler {

    private val ERROR_TOKENS = listOf(
        "NO DATA",
        "STOPPED",
        "UNABLE TO CONNECT",
        "BUS INIT",
        "CAN ERROR",
        "BUFFER FULL",
        "DATA ERROR",
        "ERROR",
        "?"
    )

    fun assemble(rawResponse: String, expectedRxHeader: String? = null): IsoTpResult {
        val lines = rawResponse
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING", ignoreCase = true) }

        if (lines.isEmpty()) {
            return IsoTpResult.Error("Empty response")
        }

        val upperCombined = lines.joinToString(" ").uppercase()
        for (token in ERROR_TOKENS) {
            if (upperCombined.contains(token)) {
                return IsoTpResult.Error("Adapter error: $token")
            }
        }

        // Case A: ELM327 ATCAF1 multi-frame (lines containing ':' like "0:62D830...")
        if (lines.any { it.contains(':') }) {
            return assembleElmNumberedMultiFrame(lines, expectedRxHeader)
        }

        // Clean whitespace and optional 3-char 11-bit CAN header prefix
        val cleanHexLines = lines.map { stripOptionalHeader(it.replace(" ", "").uppercase(), expectedRxHeader) }

        if (cleanHexLines.any { !isValidHex(it) }) {
            return IsoTpResult.Error("Non-hex data in response: ${lines.joinToString(" | ")}")
        }

        // Case B: Raw ISO-TP multi-frame (First Frame starts with nibble '1', followed by '2x' consecutive frames)
        if (cleanHexLines.size > 1 && cleanHexLines.first().length >= 4 && cleanHexLines.first()[0] == '1') {
            return assembleRawPciMultiFrame(cleanHexLines)
        }

        // Case C: Single-frame
        val single = cleanHexLines.first()
        if (single.length < 4 || single.length % 2 != 0) {
            return IsoTpResult.Error("Malformed single frame: $single")
        }

        val bytes = hexToBytes(single)
        // Check if ATCAF0 raw PCI single-frame: 8-byte frame where byte0 is 0x01..0x07 and byte1 is UDS response (0x40..0x7F)
        if (bytes.size == 8 && (bytes[0].toInt() and 0xF0) == 0x00) {
            val sfLen = bytes[0].toInt() and 0x0F
            val possibleSid = bytes[1].toInt() and 0xFF
            if (sfLen in 1..7 && (possibleSid == 0x62 || possibleSid == 0x7F)) {
                return IsoTpResult.Success(bytes.copyOfRange(1, 1 + sfLen))
            }
        }

        return IsoTpResult.Success(bytes)
    }

    private fun assembleElmNumberedMultiFrame(lines: List<String>, expectedRxHeader: String?): IsoTpResult {
        var declaredLength: Int? = null
        val payloadBuilder = StringBuilder()

        for (rawLine in lines) {
            val line = stripOptionalHeader(rawLine.replace(" ", "").uppercase(), expectedRxHeader)
            val colonIdx = line.indexOf(':')
            if (colonIdx == -1) {
                // Length header line (e.g. "023")
                if (line.length in 2..4 && isValidHex(line)) {
                    declaredLength = line.toInt(16)
                }
                continue
            }
            val dataHex = line.substring(colonIdx + 1)
            if (!isValidHex(dataHex) || dataHex.length % 2 != 0) {
                return IsoTpResult.Error("Invalid hex in multi-frame line: $rawLine")
            }
            payloadBuilder.append(dataHex)
        }

        val fullHex = payloadBuilder.toString()
        if (fullHex.isEmpty()) {
            return IsoTpResult.Error("No payload bytes in multi-frame response")
        }

        val allBytes = hexToBytes(fullHex)
        val finalBytes = if (declaredLength != null && declaredLength in 1..allBytes.size) {
            allBytes.copyOfRange(0, declaredLength)
        } else {
            allBytes
        }
        return IsoTpResult.Success(finalBytes)
    }

    private fun assembleRawPciMultiFrame(hexLines: List<String>): IsoTpResult {
        val first = hexLines.first()
        if (first.length < 4) {
            return IsoTpResult.Error("Truncated First Frame: $first")
        }

        val totalLen = first.substring(1, 4).toInt(16)
        if (totalLen <= 0 || totalLen > 4095) {
            return IsoTpResult.Error("Invalid ISO-TP First Frame length: $totalLen")
        }

        val acc = ArrayList<Byte>(totalLen + 8)
        // First Frame data starts at byte 2 (hex index 4)
        for (b in hexToBytes(first.substring(4))) {
            acc.add(b)
        }

        var expectedSeq = 1
        for (i in 1 until hexLines.size) {
            val cf = hexLines[i]
            if (cf.length < 2 || cf[0] != '2') {
                return IsoTpResult.Error("Expected Consecutive Frame (2x), got: $cf")
            }
            val seq = cf[1].digitToInt(16)
            if (seq != expectedSeq) {
                return IsoTpResult.Error("ISO-TP sequence mismatch: expected $expectedSeq, got $seq")
            }
            expectedSeq = (expectedSeq + 1) and 0x0F

            for (b in hexToBytes(cf.substring(2))) {
                acc.add(b)
            }
        }

        if (acc.size < totalLen) {
            return IsoTpResult.Error("Incomplete ISO-TP payload: expected $totalLen bytes, got ${acc.size}")
        }

        val result = ByteArray(totalLen) { idx -> acc[idx] }
        return IsoTpResult.Success(result)
    }

    private fun stripOptionalHeader(compactHex: String, expectedRxHeader: String?): String {
        val hdr = expectedRxHeader?.uppercase()?.takeIf { it.length == 3 }
        if (hdr != null && compactHex.startsWith(hdr) && compactHex.length > 3) {
            val rest = compactHex.substring(3)
            if (rest.contains(':') || rest.length % 2 == 0) {
                return rest
            }
        }
        // Generic 11-bit 3-hex-char CAN header detection when total length is odd (3 + even data)
        if (!compactHex.contains(':') && compactHex.length > 5 && compactHex.length % 2 == 1) {
            return compactHex.substring(3)
        }
        return compactHex
    }

    private fun isValidHex(s: String): Boolean =
        s.isNotEmpty() && s.all { it in '0'..'9' || it in 'A'..'F' }

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length / 2
        return ByteArray(len) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
