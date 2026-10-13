package com.fakeglucosense.core.ble

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CgmMeasurementCodecTest {

    @Test
    fun `encodes a record without trend as six bytes`() {
        val bytes = CgmMeasurementCodec.encode(CgmRecord(timeOffsetMinutes = 5, mgdl = 120.0))
        // size=6, flags=0, glucose 0x0078 LE, offset 5 LE
        assertContentEquals(byteArrayOf(6, 0, 0x78, 0x00, 5, 0), bytes)
    }

    @Test
    fun `encodes a record with trend as eight bytes with flag bit zero set`() {
        val bytes = CgmMeasurementCodec.encode(CgmRecord(timeOffsetMinutes = 5, mgdl = 120.0, trendMgdlPerMin = 1.0))
        assertEquals(8, bytes.size)
        assertEquals(8, bytes[0].toInt())
        assertEquals(CgmMeasurementCodec.FLAG_TREND_PRESENT, bytes[1].toInt())
    }

    @Test
    fun `decodes a hand-built spec-shaped record`() {
        // size 8, flags 0x01 (trend), glucose 120, offset 5, trend 1.0 (0xF00A)
        val payload = byteArrayOf(8, 1, 0x78, 0x00, 5, 0, 0x0A, 0xF0.toByte())
        val result = CgmMeasurementCodec.decodeAll(payload)
        assertEquals(0, result.skipped)
        val record = result.records.single()
        assertEquals(5, record.timeOffsetMinutes)
        assertEquals(120.0, record.mgdl, 1e-9)
        assertEquals(1.0, record.trendMgdlPerMin!!, 1e-9)
    }

    @Test
    fun `round-trips a batch of mixed records`() {
        val records = listOf(
            CgmRecord(0, 110.0),
            CgmRecord(5, 118.0, trendMgdlPerMin = 1.5),
            CgmRecord(10, 131.0, trendMgdlPerMin = -2.25),
        )
        val decoded = CgmMeasurementCodec.decodeAll(CgmMeasurementCodec.encodeAll(records))
        assertEquals(0, decoded.skipped)
        assertEquals(records.size, decoded.records.size)
        for ((expected, actual) in records.zip(decoded.records)) {
            assertEquals(expected.timeOffsetMinutes, actual.timeOffsetMinutes)
            assertEquals(expected.mgdl, actual.mgdl, 0.001)
            assertEquals(expected.trendMgdlPerMin == null, actual.trendMgdlPerMin == null)
            val expectedTrend = expected.trendMgdlPerMin
            if (expectedTrend != null) {
                assertTrue(abs(expectedTrend - actual.trendMgdlPerMin!!) < 0.001)
            }
        }
    }

    @Test
    fun `skips records with unsupported flags using their size octet`() {
        // Record A has the quality flag (bit 1) set and size 8. Record B is a normal six-byte record.
        val recordA = byteArrayOf(8, 2, 0x78, 0x00, 0, 0, 0x00, 0x00)
        val recordB = CgmMeasurementCodec.encode(CgmRecord(10, 99.0))
        val result = CgmMeasurementCodec.decodeAll(recordA + recordB)
        assertEquals(1, result.skipped)
        assertEquals(listOf(10), result.records.map { it.timeOffsetMinutes })
    }

    @Test
    fun `maps SFLOAT infinities to the clamp and drops NaN`() {
        val high = byteArrayOf(6, 0, 0xFE.toByte(), 0x07, 0, 0)
        val low = byteArrayOf(6, 0, 0x02, 0x08, 1, 0)
        val nan = byteArrayOf(6, 0, 0xFF.toByte(), 0x07, 2, 0)
        val result = CgmMeasurementCodec.decodeAll(high + low + nan)
        assertEquals(listOf(400.0, 40.0), result.records.map { it.mgdl })
        assertEquals(1, result.skipped)
    }

    @Test
    fun `rejects truncated or inconsistent records`() {
        assertFailsWith<CgmDecodeException> { CgmMeasurementCodec.decodeAll(byteArrayOf(8, 1, 0x78, 0x00)) }
        assertFailsWith<CgmDecodeException> { CgmMeasurementCodec.decodeAll(byteArrayOf(3, 0, 0, 0)) }
        // Trend flag set but size says 6: inconsistent.
        assertFailsWith<CgmDecodeException> { CgmMeasurementCodec.decodeAll(byteArrayOf(6, 1, 0x78, 0x00, 0, 0)) }
    }

    @Test
    fun `batch keeps whole records under the payload limit`() {
        val records = (0 until 10).map { CgmRecord(it * 5, 100.0 + it, trendMgdlPerMin = 0.5) }
        val batches = CgmMeasurementCodec.batch(records, maxPayloadBytes = 20)
        assertTrue(batches.all { it.size <= 20 })
        val roundTrip = batches.flatMap { CgmMeasurementCodec.decodeAll(it).records }
        assertEquals(records.map { it.timeOffsetMinutes }, roundTrip.map { it.timeOffsetMinutes })
    }

    @Test
    fun `batch refuses a limit smaller than one record`() {
        assertFailsWith<IllegalArgumentException> {
            CgmMeasurementCodec.batch(listOf(CgmRecord(0, 100.0, 1.0)), maxPayloadBytes = 4)
        }
    }

    @Test
    fun `record rejects offsets outside UINT16`() {
        assertFailsWith<IllegalArgumentException> { CgmRecord(-1, 100.0) }
        assertFailsWith<IllegalArgumentException> { CgmRecord(70_000, 100.0) }
        assertEquals(0, CgmMeasurementCodec.decodeAll(ByteArray(0)).records.size)
    }

    // --- v2: status annunciation and E2E-CRC --------------------------------------------------

    @Test
    fun `a warning octet adds one byte and flag bit five`() {
        val record = CgmRecord(
            timeOffsetMinutes = 10,
            mgdl = 60.0,
            statusAnnunciation = StatusAnnunciation(warning = SensorStatusAnnunciation.WARNING_PATIENT_LOW),
        )
        val bytes = CgmMeasurementCodec.encode(record)
        assertEquals(7, bytes.size)
        assertEquals(7, bytes[0].toInt())
        assertEquals(CgmMeasurementCodec.FLAG_WARNING_OCTET_PRESENT, bytes[1].toInt())
        // Announcement octet sits right after the time offset.
        assertEquals(SensorStatusAnnunciation.WARNING_PATIENT_LOW, bytes[6].toInt() and 0xFF)
        assertEquals(record, CgmMeasurementCodec.decodeAll(bytes).records.single())
    }

    @Test
    fun `all three annunciation octets come in status, cal-temp, warning order`() {
        val record = CgmRecord(
            timeOffsetMinutes = 0,
            mgdl = 90.0,
            trendMgdlPerMin = 0.5,
            statusAnnunciation = StatusAnnunciation(status = 0x01, calTemp = 0x02, warning = 0x04),
        )
        val bytes = CgmMeasurementCodec.encode(record)
        // 6 base + 3 annunciation + 2 trend = 11.
        assertEquals(11, bytes.size)
        assertEquals(0xE1, bytes[1].toInt() and 0xFF) // bits 0, 5, 6, 7
        assertEquals(0x01, bytes[6].toInt())
        assertEquals(0x02, bytes[7].toInt())
        assertEquals(0x04, bytes[8].toInt())
        assertEquals(record, CgmMeasurementCodec.decodeAll(bytes).records.single())
    }

    @Test
    fun `zero annunciation octets are not sent`() {
        val record = CgmRecord(timeOffsetMinutes = 0, mgdl = 90.0, statusAnnunciation = StatusAnnunciation())
        val decoded = CgmMeasurementCodec.decodeAll(CgmMeasurementCodec.encode(record)).records.single()
        assertEquals(null, decoded.statusAnnunciation)
    }

    @Test
    fun `encodeValue stamps the same CRC at the end of every record`() {
        val records = listOf(
            CgmRecord(0, 110.0),
            CgmRecord(5, 118.0, trendMgdlPerMin = 1.5),
        )
        val value = CgmMeasurementCodec.encodeValue(records, withCrc = true)
        // Sizes grew by two and include the CRC: 8, 10.
        assertEquals(8, value[0].toInt())
        assertEquals(10, value[8].toInt())
        val crc1 = CgmMeasurementCodec.readU16(value, 6)
        val crc2 = CgmMeasurementCodec.readU16(value, value.size - 2)
        assertEquals(crc1, crc2)
        val decoded = CgmMeasurementCodec.decodeAll(value)
        assertEquals(2, decoded.records.size)
        assertEquals(records.map { it.timeOffsetMinutes }, decoded.records.map { it.timeOffsetMinutes })
    }

    @Test
    fun `a tampered CRC byte fails the whole value`() {
        val value = CgmMeasurementCodec.encodeValue(listOf(CgmRecord(0, 110.0)), withCrc = true)
        value[value.size - 1] = (value[value.size - 1].toInt() xor 0x01).toByte()
        assertFailsWith<CgmDecodeException> { CgmMeasurementCodec.decodeAll(value) }
    }

    @Test
    fun `a tampered glucose byte fails the CRC too`() {
        val value = CgmMeasurementCodec.encodeValue(listOf(CgmRecord(0, 110.0)), withCrc = true)
        value[2] = (value[2].toInt() xor 0x40).toByte()
        assertFailsWith<CgmDecodeException> { CgmMeasurementCodec.decodeAll(value) }
    }

    @Test
    fun `complete v2 record round-trips through an MTU-sized batch`() {
        val thresholdsRecord = CgmRecord(
            timeOffsetMinutes = 55,
            mgdl = 260.0,
            trendMgdlPerMin = -3.5,
            statusAnnunciation = StatusAnnunciation(
                warning = SensorStatusAnnunciation.WARNING_HYPER or SensorStatusAnnunciation.WARNING_PATIENT_HIGH,
            ),
        )
        val records = listOf(thresholdsRecord) + (60..200 step 5).map {
            CgmRecord(it, 120.0 + it % 7, trendMgdlPerMin = 0.25)
        }
        // 11-byte record 0 and 10-byte trend records: 20-byte frames hold one or two records.
        val small = CgmMeasurementCodec.batch(records, maxPayloadBytes = 20, withCrc = true)
        val large = CgmMeasurementCodec.batch(records, maxPayloadBytes = 182, withCrc = true)
        assertTrue(small.size > large.size)
        assertTrue(small.all { it.size <= 20 })
        assertTrue(large.all { it.size <= 182 })
        for (frame in small + large) {
            assertEquals(0, CgmMeasurementCodec.decodeAll(frame).skipped)
        }
        val roundTrip = small.flatMap { CgmMeasurementCodec.decodeAll(it).records }
        assertEquals(records.map { it.timeOffsetMinutes }, roundTrip.map { it.timeOffsetMinutes })
        assertEquals(thresholdsRecord.statusAnnunciation, roundTrip.first().statusAnnunciation)
    }
}
