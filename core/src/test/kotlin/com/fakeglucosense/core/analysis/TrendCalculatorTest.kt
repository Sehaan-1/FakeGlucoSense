package com.fakeglucosense.core.analysis

import com.fakeglucosense.core.model.GlucoseReading
import com.fakeglucosense.core.model.Trend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrendCalculatorTest {
    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    /** Readings every 5 minutes ending at t0, with values from [mgdl]. */
    private fun series(vararg mgdl: Int): List<GlucoseReading> {
        val last = mgdl.size - 1
        return mgdl.mapIndexed { i, value -> GlucoseReading(t0 - (last - i) * 5 * minute, value) }
    }

    @Test
    fun `flat series is flat`() {
        val estimate = TrendCalculator.estimate(series(120, 120, 121, 120), t0)
        assertEquals(Trend.FLAT, estimate.trend)
    }

    @Test
    fun `steady climb of one mg per minute is forty-five up`() {
        // 5 mg/dL every 5 minutes = 1 mg/dL/min.
        val estimate = TrendCalculator.estimate(series(100, 105, 110, 115), t0)
        assertEquals(1.0, estimate.rateMgdlPerMin!!, 1e-9)
        assertEquals(Trend.FORTY_FIVE_UP, estimate.trend)
    }

    @Test
    fun `fast fall of four mg per minute is double down`() {
        val estimate = TrendCalculator.estimate(series(180, 160, 140, 120), t0)
        assertEquals(-4.0, estimate.rateMgdlPerMin!!, 1e-9)
        assertEquals(Trend.DOUBLE_DOWN, estimate.trend)
    }

    @Test
    fun `a single point is not computable`() {
        val estimate = TrendCalculator.estimate(series(120), t0)
        assertNull(estimate.rateMgdlPerMin)
        assertEquals(Trend.NOT_COMPUTABLE, estimate.trend)
    }

    @Test
    fun `readings older than the window are ignored`() {
        val old = GlucoseReading(t0 - 40 * minute, 60)
        val recent = series(120, 120)
        val estimate = TrendCalculator.estimate(listOf(old) + recent, t0)
        assertEquals(Trend.FLAT, estimate.trend)
    }

    @Test
    fun `band edges follow the documented table`() {
        assertEquals(Trend.DOUBLE_UP, Trend.fromRate(3.01))
        assertEquals(Trend.SINGLE_UP, Trend.fromRate(3.0))
        assertEquals(Trend.SINGLE_UP, Trend.fromRate(2.01))
        assertEquals(Trend.FORTY_FIVE_UP, Trend.fromRate(2.0))
        assertEquals(Trend.FORTY_FIVE_UP, Trend.fromRate(1.0))
        assertEquals(Trend.FLAT, Trend.fromRate(0.99))
        assertEquals(Trend.FLAT, Trend.fromRate(-0.99))
        assertEquals(Trend.FORTY_FIVE_DOWN, Trend.fromRate(-1.0))
        assertEquals(Trend.FORTY_FIVE_DOWN, Trend.fromRate(-2.0))
        assertEquals(Trend.SINGLE_DOWN, Trend.fromRate(-2.01))
        assertEquals(Trend.SINGLE_DOWN, Trend.fromRate(-3.0))
        assertEquals(Trend.DOUBLE_DOWN, Trend.fromRate(-3.01))
    }

    @Test
    fun `annotate computes a trend for every reading in order`() {
        val annotated = TrendCalculator.annotate(series(100, 100, 130, 160))
        assertEquals(Trend.NOT_COMPUTABLE, annotated[0].trend)
        assertEquals(Trend.DOUBLE_UP, annotated.last().trend)
    }
}
