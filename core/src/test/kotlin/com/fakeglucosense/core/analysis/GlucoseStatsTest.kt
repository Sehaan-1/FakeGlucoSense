package com.fakeglucosense.core.analysis

import com.fakeglucosense.core.model.GlucoseReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GlucoseStatsTest {
    private fun readings(vararg values: Int) =
        values.mapIndexed { i, v -> GlucoseReading(i * 300_000L, v) }

    @Test
    fun `empty input has no summary`() {
        assertNull(GlucoseStats.summarize(emptyList()))
    }

    @Test
    fun `summarizes range and mean`() {
        val summary = GlucoseStats.summarize(readings(60, 100, 200))!!
        assertEquals(3, summary.count)
        assertEquals(60, summary.minMgdl)
        assertEquals(200, summary.maxMgdl)
        assertEquals(120.0, summary.meanMgdl, 1e-9)
        assertEquals(100.0 / 3.0, summary.timeInRangePercent, 1e-9)
        assertEquals(100.0 / 3.0, summary.timeBelowPercent, 1e-9)
        assertEquals(100.0 / 3.0, summary.timeAbovePercent, 1e-9)
        assertEquals(3.31 + 0.02392 * 120.0, summary.gmiPercent, 1e-9)
    }

    @Test
    fun `constant readings have zero variability`() {
        val summary = GlucoseStats.summarize(readings(110, 110, 110))!!
        assertEquals(0.0, summary.cvPercent, 1e-9)
        assertEquals(100.0, summary.timeInRangePercent, 1e-9)
    }
}
