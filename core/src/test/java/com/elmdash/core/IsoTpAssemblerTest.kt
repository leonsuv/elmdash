package com.elmdash.core

import com.elmdash.core.protocol.IsoTpAssembler
import com.elmdash.core.protocol.IsoTpResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoTpAssemblerTest {

    @Test
    fun `assembles single frame ATCAF1 response`() {
        val result = IsoTpAssembler.assemble("62D8151E8C")
        assertTrue(result is IsoTpResult.Success)
        val bytes = (result as IsoTpResult.Success).payload
        assertArrayEquals(byteArrayOf(0x62, 0xD8.toByte(), 0x15, 0x1E, 0x8C.toByte()), bytes)
    }

    @Test
    fun `assembles single frame ATCAF0 raw PCI response`() {
        val result = IsoTpAssembler.assemble("0562D8151E8CAAAA")
        assertTrue(result is IsoTpResult.Success)
        val bytes = (result as IsoTpResult.Success).payload
        assertArrayEquals(byteArrayOf(0x62, 0xD8.toByte(), 0x15, 0x1E, 0x8C.toByte()), bytes)
    }

    @Test
    fun `strips optional 11-bit RX CAN header`() {
        val result = IsoTpAssembler.assemble("76C 62 D8 15 1E 8C", expectedRxHeader = "76C")
        assertTrue(result is IsoTpResult.Success)
        val bytes = (result as IsoTpResult.Success).payload
        assertArrayEquals(byteArrayOf(0x62, 0xD8.toByte(), 0x15, 0x1E, 0x8C.toByte()), bytes)
    }

    @Test
    fun `assembles ELM327 ATCAF1 numbered multi-frame response`() {
        val raw = """
            023
            0:62D8300DC80D
            1:D80DE20DE10DD5
            2:0DC40DB40DAC0D
            3:AF0DBC0DCE0DDC
            4:0DE30DDE0DD00D
            5:BE
        """.trimIndent()

        val result = IsoTpAssembler.assemble(raw)
        assertTrue(result is IsoTpResult.Success)
        val bytes = (result as IsoTpResult.Success).payload
        assertEquals(35, bytes.size)
        assertEquals(0x62, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        assertEquals(0x30, bytes[2].toInt() and 0xFF)
        assertEquals(0xBE, bytes[34].toInt() and 0xFF)
    }

    @Test
    fun `assembles raw ISO-TP PCI multi-frame response and trims padding`() {
        val raw = """
            102362D8300DC80D
            21D80DE20DE10DD5
            220DC40DB40DAC0D
            23AF0DBC0DCE0DDC
            240DE30DDE0DD00D
            25BEAAAAAAAAAAAA
        """.trimIndent()

        val result = IsoTpAssembler.assemble(raw)
        assertTrue(result is IsoTpResult.Success)
        val bytes = (result as IsoTpResult.Success).payload
        assertEquals(35, bytes.size)
        assertEquals(0x62, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        assertEquals(0x30, bytes[2].toInt() and 0xFF)
        assertEquals(0xBE, bytes[34].toInt() and 0xFF)
    }

    @Test
    fun `returns error on NO DATA and sequence mismatch`() {
        assertTrue(IsoTpAssembler.assemble("NO DATA") is IsoTpResult.Error)
        val badSeq = """
            101062D830010203
            220405060708090A
        """.trimIndent()
        assertTrue(IsoTpAssembler.assemble(badSeq) is IsoTpResult.Error)
    }
}
