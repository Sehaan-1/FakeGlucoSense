package com.fakeglucosense.companion.data.ble

import com.fakeglucosense.core.ble.CgmRecord
import com.fakeglucosense.core.model.Trend
import kotlin.test.Test
import kotlin.test.assertEquals

class CgmRecordMapperTest {

    private val sessionStart = 1_700_000_000_000L

    @Test
    fun `time offset is minutes after the session start`() {
        val reading = CgmRecordMapper.toReading(CgmRecord(timeOffsetMinutes = 65, mgdl = 120.0), sessionStart)
        assertEquals(sessionStart + 65 * 60_000L, reading.epochMillis)
    }

    @Test
    fun `glucose is rounded to whole mg per dl`() {
        assertEquals(121, CgmRecordMapper.toReading(CgmRecord(0, 120.6), sessionStart).mgdl)
        assertEquals(120, CgmRecordMapper.toReading(CgmRecord(0, 119.7), sessionStart).mgdl)
    }

    @Test
    fun `a trend rate becomes both a rate and a trend arrow`() {
        val reading = CgmRecordMapper.toReading(CgmRecord(0, 150.0, trendMgdlPerMin = 2.5), sessionStart)
        assertEquals(2.5, reading.rateMgdlPerMin)
        assertEquals(Trend.SINGLE_UP, reading.trend)
    }

    @Test
    fun `a missing trend stays not computable so the repository can derive it`() {
        val reading = CgmRecordMapper.toReading(CgmRecord(0, 150.0), sessionStart)
        assertEquals(Trend.NOT_COMPUTABLE, reading.trend)
        assertEquals(null, reading.rateMgdlPerMin)
    }
}
