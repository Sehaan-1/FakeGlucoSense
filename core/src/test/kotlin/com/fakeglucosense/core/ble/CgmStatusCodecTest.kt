package com.fakeglucosense.core.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CgmStatusCodecTest {

    @Test
    fun `round-trips time offset and all three annunciation octets`() {
        val annunciation = StatusAnnunciation(status = 0x01, calTemp = 0x22, warning = 0x03)
        val value = CgmStatusCodec.decode(CgmStatusCodec.encode(300, annunciation))
        assertEquals(300, value.timeOffsetMinutes)
        assertEquals(annunciation, value.annunciation)
    }

    @Test
    fun `lays out offset, status, cal-temp, warning, then the CRC`() {
        // offset 5 -> 05 00; status 0x01; calTemp 0; warning 0x04; CRC of those five bytes.
        val bytes = CgmStatusCodec.encode(5, StatusAnnunciation(status = 0x01, warning = 0x04))
        assertEquals(CgmStatusCodec.SIZE, bytes.size)
        assertEquals(5, bytes[0].toInt())
        assertEquals(0x01, bytes[2].toInt())
        assertEquals(0x04, bytes[4].toInt())
        assertEquals(CgmCrc16.compute(bytes, 0, 5), CgmMeasurementCodec.readU16(bytes, 5))
    }

    @Test
    fun `rejects a corrupted CRC and short values`() {
        val bytes = CgmStatusCodec.encode(5, StatusAnnunciation())
        bytes[0] = 6 // flip one bit of the covered range
        assertFailsWith<CgmDecodeException> { CgmStatusCodec.decode(bytes) }
        assertFailsWith<CgmDecodeException> { CgmStatusCodec.decode(bytes.copyOf(6)) }
    }

    @Test
    fun `exposes session stopped from the status octet`() {
        val stopped = StatusAnnunciation(status = SensorStatusAnnunciation.STATUS_SESSION_STOPPED)
        assertTrue(CgmStatusCodec.decode(CgmStatusCodec.encode(0, stopped)).sessionStopped)
        assertFalse(CgmStatusCodec.decode(CgmStatusCodec.encode(0, StatusAnnunciation())).sessionStopped)
    }
}
