package com.fakeglucosense.core.ble

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CgmOpsCodecTest {

    @Test
    fun `decodes start and stop session from one byte`() {
        assertEquals(CgmOpsCodec.OP_START_SESSION, CgmOpsCodec.decodeRequest(byteArrayOf(0x1A)).opCode)
        assertEquals(CgmOpsCodec.OP_STOP_SESSION, CgmOpsCodec.decodeRequest(byteArrayOf(0x1B)).opCode)
        assertNull(CgmOpsCodec.decodeRequest(byteArrayOf(0x1A)).intervalMinutes)
    }

    @Test
    fun `decodes set interval with its operand`() {
        val request = CgmOpsCodec.decodeRequest(byteArrayOf(0x01, 0x1E))
        assertEquals(CgmOpsCodec.OP_SET_COMMUNICATION_INTERVAL, request.opCode)
        assertEquals(30, request.intervalMinutes)
    }

    @Test
    fun `rejects empty and operand-less writes`() {
        assertFailsWith<CgmDecodeException> { CgmOpsCodec.decodeRequest(ByteArray(0)) }
        assertFailsWith<CgmDecodeException> { CgmOpsCodec.decodeRequest(byteArrayOf(0x01)) }
    }

    @Test
    fun `response frames match the CGMS layout`() {
        // 0x1C, request op 0x1B, success
        assertContentEquals(
            byteArrayOf(0x1C, 0x1B, 0x01),
            CgmOpsCodec.encodeResponseCode(CgmOpsCodec.OP_STOP_SESSION, CgmOpsCodec.RESPONSE_SUCCESS),
        )
        // 0x03, interval 10
        assertContentEquals(byteArrayOf(0x03, 0x0A), CgmOpsCodec.encodeCommunicationInterval(10))
        assertFailsWith<IllegalArgumentException> { CgmOpsCodec.encodeCommunicationInterval(300) }
    }
}
