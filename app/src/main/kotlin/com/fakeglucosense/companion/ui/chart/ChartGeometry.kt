package com.fakeglucosense.companion.ui.chart

import com.fakeglucosense.core.model.AlertThresholds
import com.fakeglucosense.core.model.GlucoseReading
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Maps time and glucose onto unit-square fractions for the canvas. Pure arithmetic, so the axes
 * are testable without a Canvas. The caller multiplies by pixel size.
 */
class ChartGeometry(
    private val fromMillis: Long,
    private val toMillis: Long,
    val minMgdl: Double,
    val maxMgdl: Double,
) {
    init {
        require(toMillis > fromMillis) { "window must have positive length" }
        require(maxMgdl > minMgdl) { "y range must have positive span" }
    }

    /** 0 at the left edge (window start), 1 at the right edge (now). */
    fun fractionX(epochMillis: Long): Float =
        ((epochMillis - fromMillis).toDouble() / (toMillis - fromMillis)).coerceIn(0.0, 1.0).toFloat()

    /** 0 at the top (highest glucose), 1 at the bottom (lowest). */
    fun fractionY(mgdl: Double): Float =
        (1.0 - (mgdl - minMgdl) / (maxMgdl - minMgdl)).coerceIn(0.0, 1.0).toFloat()

    companion object {
        const val FLOOR_MGDL = 40.0
        const val CEIL_MGDL = 400.0
        const val MIN_SPAN_MGDL = 120.0
        private const val PAD_MGDL = 10.0
        private const val STEP_MGDL = 10.0

        /**
         * Y range that contains every reading and both target thresholds, padded and snapped to
         * 10 mg/dL. Never narrower than [MIN_SPAN_MGDL], and clamped to the physiological range.
         */
        fun yDomain(readings: List<GlucoseReading>, thresholds: AlertThresholds): Pair<Double, Double> {
            var lo = min(thresholds.lowMgdl.toDouble(), readings.minOfOrNull { it.mgdl.toDouble() } ?: Double.MAX_VALUE)
            var hi = max(thresholds.highMgdl.toDouble(), readings.maxOfOrNull { it.mgdl.toDouble() } ?: 0.0)
            lo = floor((lo - PAD_MGDL) / STEP_MGDL) * STEP_MGDL
            hi = ceil((hi + PAD_MGDL) / STEP_MGDL) * STEP_MGDL
            lo = max(FLOOR_MGDL, lo)
            hi = min(CEIL_MGDL, hi)
            if (hi - lo < MIN_SPAN_MGDL) {
                hi = min(CEIL_MGDL, lo + MIN_SPAN_MGDL)
                lo = max(FLOOR_MGDL, hi - MIN_SPAN_MGDL)
            }
            return lo to hi
        }
    }
}
