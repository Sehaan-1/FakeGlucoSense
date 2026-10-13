package com.fakeglucosense.core.ble

import kotlin.test.Test
import kotlin.test.assertEquals

class CgmCrc16Test {

    @Test
    fun `matches the CRC-16-CCITT check value for the standard test string`() {
        // The canonical CRC-16/CCITT-FALSE check: "123456789" -> 0x29B1.
        assertEquals(0x29B1, CgmCrc16.compute("123456789".encodeToByteArray()))
    }

    @Test
    fun `empty input returns the initial value`() {
        assertEquals(0xFFFF, CgmCrc16.compute(ByteArray(0)))
    }

    @Test
    fun `range parameter restricts the covered bytes`() {
        val bytes = "991234567899".encodeToByteArray()
        assertEquals(0x29B1, CgmCrc16.compute(bytes, start = 2, end = 11))
    }

    @Test
    fun `is sensitive to single-bit changes`() {
        val a = CgmCrc16.compute(byteArrayOf(0x10, 0x20, 0x30))
        val b = CgmCrc16.compute(byteArrayOf(0x10, 0x21, 0x30))
        kotlin.test.assertNotEquals(a, b)
    }
}
