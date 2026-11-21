package com.elmdash.core

import com.elmdash.core.protocol.DtcDecoder
import com.elmdash.core.protocol.IsoTpAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcDecoderTest {

    @Test
    fun `filters out ISO 14229-1 ghost DTCs with only testNotCompleted bits`() {
        // 59 02 FF +
        //   P1A50-13 status 0x50 (bit4+bit6 only -> GHOST, must be filtered)
        //   P0605-04 status 0x40 (bit6 only -> GHOST, must be filtered)
        //   P1657-13 status 0x0D (active+pending+confirmed -> REAL, keep)
        //   P0C46-25 status 0x08 (confirmed -> REAL, keep)
        val payload = IsoTpAssembler.hexToBytes(
            "5902FF" +
                "1A501350" +
                "06050440" +
                "1657130D" +
                "0C462508"
        )
        val dtcs = DtcDecoder.decodeUds1902(payload, "VCU", "Vehicle Control Unit")
        assertEquals(2, dtcs.size)
        assertEquals("P1657", dtcs[0].code)
        assertEquals("13", dtcs[0].failureTypeByte)
        assertTrue(dtcs[0].isActive)
        assertEquals("P0C46", dtcs[1].code)
        assertEquals("25", dtcs[1].failureTypeByte)
        assertTrue(dtcs[1].isConfirmed)
    }
}
