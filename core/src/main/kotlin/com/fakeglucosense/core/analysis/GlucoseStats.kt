package com.fakeglucosense.core.analysis

import com.fakeglucosense.core.model.GlucoseReading
import kotlin.math.sqrt

/** Computes [GlucoseSummary] values. Target range defaults to the common 70–180 mg/dL band. */
object GlucoseStats {
    const val DEFAULT_LOW = 70
    const val DEFAULT_HIGH = 180

    /** Returns null for an empty list. Time-in-range is a share of readings, which assumes even spacing. */
    fun summarize(
        readings: List<GlucoseReading>,
        targetLow: Int = DEFAULT_LOW,
        targetHigh: Int = DEFAULT_HIGH,
    ): GlucoseSummary? {
        require(targetLow < targetHigh) { "target low must be below target high" }
        if (readings.isEmpty()) return null
        val values = readings.map { it.mgdl.toDouble() }
        val n = values.size.toDouble()
        val mean = values.sum() / n
        val variance = values.sumOf { (it - mean) * (it - mean) } / n
        val inRange = readings.count { it.mgdl in targetLow..targetHigh }
        val below = readings.count { it.mgdl < targetLow }
        val above = readings.count { it.mgdl > targetHigh }
        return GlucoseSummary(
            count = readings.size,
            meanMgdl = mean,
            minMgdl = readings.minOf { it.mgdl },
            maxMgdl = readings.maxOf { it.mgdl },
            timeInRangePercent = percent(inRange, readings.size),
            timeBelowPercent = percent(below, readings.size),
            timeAbovePercent = percent(above, readings.size),
            gmiPercent = 3.31 + 0.02392 * mean,
            cvPercent = if (mean > 0) 100.0 * sqrt(variance) / mean else 0.0,
        )
    }

    private fun percent(part: Int, whole: Int): Double = 100.0 * part / whole
}
