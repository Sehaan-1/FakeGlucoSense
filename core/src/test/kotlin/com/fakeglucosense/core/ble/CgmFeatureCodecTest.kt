package com.fakeglucosense.core.ble

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CgmFeatureCodecTest {

    @Test
    fun `encodes the sensor default as four octets little-endian`() {
        // SENSOR_FEATURES = patient hi/lo(1) + hypo(2) + hyper(3) + rate(4) + CRC(12) + trend(15)
        //                 = 0x00901E. Type 9 (ISF) in the high nibble, location 5 (subcutaneous).
        assertContentEquals(
            byteArrayOf(0x1E, 0x90.toByte(), 0x00, 0x95.toByte()),
            CgmFeatureCodec.encode(),
        )
    }

    @Test
    fun `round-trips features, type, and location`() {
        val value = CgmFeatureCodec.decode(CgmFeatureCodec.encode(features = 0x012345, type = 0xA, sampleLocation = 0x2))
        assertEquals(0x012345, value.features)
        assertEquals(0xA, value.type)
        assertEquals(0x2, value.sampleLocation)
    }

    @Test
    fun `sensor default advertises the v2 capabilities and not the v1 gaps`() {
        val value = CgmFeatureCodec.decode(CgmFeatureCodec.encode())
        assertTrue(value.e2eCrcSupported)
        assertTrue(value.features and CgmFeatureCodec.FEAT_TREND_INFORMATION_SUPPORTED != 0)
        assertTrue(value.features and CgmFeatureCodec.FEAT_PATIENT_HIGH_LOW_ALERTS != 0)
        assertEquals(0, value.features and CgmFeatureCodec.FEAT_CALIBRATION_SUPPORTED)
        assertEquals(0, value.features and CgmFeatureCodec.FEAT_QUALITY_SUPPORTED)
    }

    @Test
    fun `rejects short values and out-of-range nibbles`() {
        assertFailsWith<CgmDecodeException> { CgmFeatureCodec.decode(byteArrayOf(1, 2, 3)) }
        assertFailsWith<IllegalArgumentException> { CgmFeatureCodec.encode(type = 0x10) }
        assertFailsWith<IllegalArgumentException> { CgmFeatureCodec.encode(features = 0x1000000) }
    }
}
