package com.fakeglucosense.core.analysis

/**
 * Summary statistics for a window of readings.
 *
 * @property gmiPercent Glucose Management Indicator, `3.31 + 0.02392 × mean(mg/dL)`. An estimate of
 *   HbA1c from CGM data, not a diagnosis.
 * @property cvPercent coefficient of variation (glycemic variability), `100 × SD / mean`.
 */
data class GlucoseSummary(
    val count: Int,
    val meanMgdl: Double,
    val minMgdl: Int,
    val maxMgdl: Int,
    val timeInRangePercent: Double,
    val timeBelowPercent: Double,
    val timeAbovePercent: Double,
    val gmiPercent: Double,
    val cvPercent: Double,
)
