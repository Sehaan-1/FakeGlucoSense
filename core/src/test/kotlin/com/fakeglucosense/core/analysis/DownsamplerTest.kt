package com.fakeglucosense.core.analysis

import com.fakeglucosense.core.model.GlucoseReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DownsamplerTest {

    @Test
    fun `returns the input unchanged when it already fits`() {
        val input = (0 until 10).map { GlucoseReading(it * 300_000L, 100 + it) }
        assertSame(input, Downsampler.minMax(input, maxPoints = 10))
    }

    @Test
    fun `keeps the global extremes and the chronological order`() {
        val values = IntArray(1000) { 100 + (it % 50) }
        values[417] = 300 // a spike that must survive downsampling
        values[812] = 45  // a dip that must survive downsampling
        val input = values.mapIndexed { i, v -> GlucoseReading(i * 300_000L, v) }
        val output = Downsampler.minMax(input, maxPoints = 100)
        assertTrue(output.size <= 100)
        assertEquals(300, output.maxOf { it.mgdl })
        assertEquals(45, output.minOf { it.mgdl })
        assertEquals(output.sortedBy { it.epochMillis }, output)
    }
}
