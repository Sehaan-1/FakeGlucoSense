package com.fakeglucosense.core.wire

import com.fakeglucosense.core.model.GlucoseReading
import com.fakeglucosense.core.model.Trend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadingJsonCodecTest {

    @Test
    fun `round-trips a reading with rate and sequence`() {
        val reading = GlucoseReading(1_700_000_000_000L, 132, Trend.SINGLE_UP, 2.5)
        val line = ReadingJsonCodec.encode(reading, sequence = 42)
        assertEquals("{\"t\":1700000000000,\"mgdl\":132,\"direction\":\"SingleUp\",\"rate\":2.500,\"seq\":42}", line)
        val parsed = ReadingJsonCodec.decode(line)!!
        assertEquals(reading.copy(rateMgdlPerMin = 2.5), parsed.reading)
        assertEquals(42L, parsed.sequence)
    }

    @Test
    fun `omits optional fields when absent`() {
        val line = ReadingJsonCodec.encode(GlucoseReading(1L, 90))
        assertEquals("{\"t\":1,\"mgdl\":90,\"direction\":\"NOT COMPUTABLE\"}", line)
        val parsed = ReadingJsonCodec.decode(line)!!
        assertEquals(Trend.NOT_COMPUTABLE, parsed.reading.trend)
        assertNull(parsed.reading.rateMgdlPerMin)
    }

    @Test
    fun `returns null for malformed or out-of-range lines`() {
        assertNull(ReadingJsonCodec.decode(""))
        assertNull(ReadingJsonCodec.decode("hello"))
        assertNull(ReadingJsonCodec.decode("{\"t\":1,\"direction\":\"Flat\"}"))
        assertNull(ReadingJsonCodec.decode("{\"t\":1,\"mgdl\":5000}"))
    }
}
