package com.elmdash.core

import com.elmdash.core.protocol.IsoTpAssembler
import com.elmdash.core.protocol.IsoTpResult
import com.elmdash.core.protocol.PidRegistry
import com.elmdash.core.protocol.UdsParseResult
import com.elmdash.core.protocol.UdsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UdsParserTest {

    private fun loadStarterRegistry(): PidRegistry {
        val candidates = listOf(
            File("../docs/corsa_e_pids.json"),
            File("docs/corsa_e_pids.json")
        )
        val file = candidates.first { it.exists() }
        return PidRegistry.fromJson(file.readText(Charsets.UTF_8))
    }

    @Test
    fun `parses unsigned SoC, offset battery temperature, and byte-offset SoH`() {
        val registry = loadStarterRegistry()
        val socPid = registry.getPid("soc")!!
        val tempPid = registry.getPid("battery_temp")!!
        val sohPid = registry.getPid("soh")!!

        // 0x9C40 = 40000 / 512 = 78.125 %
        val socBytes = IsoTpAssembler.hexToBytes("62D4109C40")
        val socRes = UdsParser.parse(socBytes, socPid)
        assertTrue(socRes is UdsParseResult.Scalar)
        assertEquals(78.125, (socRes as UdsParseResult.Scalar).value, 0.001)

        // 0x41 = 65 + (-40) = 25.0 °C
        val tempBytes = IsoTpAssembler.hexToBytes("62D87741")
        val tempRes = UdsParser.parse(tempBytes, tempPid)
        assertTrue(tempRes is UdsParseResult.Scalar)
        assertEquals(25.0, (tempRes as UdsParseResult.Scalar).value, 0.001)

        // 3-byte SoH payload: byte[0]=status, bytes[1..2]=0x060A (1546 / 16 = 96.625 %)
        val sohBytes = IsoTpAssembler.hexToBytes("62D86000060A")
        val sohRes = UdsParser.parse(sohBytes, sohPid)
        assertTrue(sohRes is UdsParseResult.Scalar)
        assertEquals(96.625, (sohRes as UdsParseResult.Scalar).value, 0.001)
    }

    @Test
    fun `parses signed negative motor power for regen`() {
        val registry = loadStarterRegistry()
        val powerPid = registry.getPid("motor_power")!!

        // -355 (0xFE9D) * 0.1 = -35.5 kW
        val bytes = IsoTpAssembler.hexToBytes("62D8D9FE9D")
        val res = UdsParser.parse(bytes, powerPid)
        assertTrue(res is UdsParseResult.Scalar)
        assertEquals(-35.5, (res as UdsParseResult.Scalar).value, 0.001)
    }

    @Test
    fun `parses 108-cell multi-frame ISO-TP array`() {
        val registry = loadStarterRegistry()
        val cellsPid = registry.getPid("cell_voltages")
        assertNotNull(cellsPid)
        assertEquals(108, cellsPid!!.elementCount)

        // Build synthetic 108-cell UDS payload: 62 D4 40 + 108 * 2 bytes (3500..3607 mV)
        val payload = ByteArray(3 + 108 * 2)
        payload[0] = 0x62
        payload[1] = 0xD4.toByte()
        payload[2] = 0x40
        for (i in 0 until 108) {
            val mv = 3500 + i
            payload[3 + i * 2] = ((mv shr 8) and 0xFF).toByte()
            payload[3 + i * 2 + 1] = (mv and 0xFF).toByte()
        }

        val parsed = UdsParser.parse(payload, cellsPid)
        assertTrue(parsed is UdsParseResult.ArrayValue)
        val values = (parsed as UdsParseResult.ArrayValue).values
        assertEquals(108, values.size)
        assertEquals(3.500, values[0], 0.0005)
        assertEquals(3.607, values[107], 0.0005)
    }

    @Test
    fun `handles UDS negative response 0x7F`() {
        val registry = loadStarterRegistry()
        val socPid = registry.getPid("soc")!!
        val res = UdsParser.parse(IsoTpAssembler.hexToBytes("7F2231"), socPid)
        assertTrue(res is UdsParseResult.NegativeResponse)
        val nr = res as UdsParseResult.NegativeResponse
        assertEquals(0x22, nr.service)
        assertEquals(0x31, nr.nrc)
    }
}
