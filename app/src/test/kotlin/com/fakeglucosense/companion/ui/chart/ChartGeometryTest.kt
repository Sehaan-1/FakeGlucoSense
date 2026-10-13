package com.fakeglucosense.companion.ui.chart

import com.fakeglucosense.core.model.AlertThresholds
import com.fakeglucosense.core.model.GlucoseReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartGeometryTest {

    private fun reading(mgdl: Int) = GlucoseReading(epochMillis = 0L, mgdl = mgdl)

    @Test
    fun `y domain contains every reading and both target thresholds`() {
        val thresholds = AlertThresholds()
        val (lo, hi) = ChartGeometry.yDomain(listOf(reading(60), reading(210)), thresholds)
        assertTrue(lo <= 60.0 && lo <= thresholds.lowMgdl)
        assertTrue(hi >= 210.0 && hi >= thresholds.highMgdl)
    }

    @Test
    fun `y domain is snapped to ten and clamped to the physiological range`() {
        val (lo, hi) = ChartGeometry.yDomain(listOf(reading(20), reading(590)), AlertThresholds())
        assertEquals(ChartGeometry.FLOOR_MGDL, lo)
        assertEquals(ChartGeometry.CEIL_MGDL, hi)
    }

    @Test
    fun `y domain is never narrower than the minimum span`() {
        val (lo, hi) = ChartGeometry.yDomain(listOf(reading(100), reading(102)), AlertThresholds())
        assertTrue(hi - lo >= ChartGeometry.MIN_SPAN_MGDL)
    }

    @Test
    fun `empty input still yields a usable domain around the thresholds`() {
        val thresholds = AlertThresholds()
        val (lo, hi) = ChartGeometry.yDomain(emptyList(), thresholds)
        assertTrue(lo <= thresholds.lowMgdl && hi >= thresholds.highMgdl)
        assertTrue(hi - lo >= ChartGeometry.MIN_SPAN_MGDL)
    }

    @Test
    fun `time maps left to right across the window and clamps outside it`() {
        val geometry = ChartGeometry(fromMillis = 1_000L, toMillis = 3_000L, minMgdl = 40.0, maxMgdl = 400.0)
        assertEquals(0f, geometry.fractionX(1_000L))
        assertEquals(0.5f, geometry.fractionX(2_000L))
        assertEquals(1f, geometry.fractionX(3_000L))
        assertEquals(0f, geometry.fractionX(0L))
        assertEquals(1f, geometry.fractionX(9_999L))
    }

    @Test
    fun `glucose maps top to bottom so higher values sit higher on screen`() {
        val geometry = ChartGeometry(fromMillis = 0L, toMillis = 1L, minMgdl = 40.0, maxMgdl = 400.0)
        assertEquals(0f, geometry.fractionY(400.0))
        assertEquals(1f, geometry.fractionY(40.0))
        assertTrue(geometry.fractionY(200.0) < geometry.fractionY(100.0))
    }
}
