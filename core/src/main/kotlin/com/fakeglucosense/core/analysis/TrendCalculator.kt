package com.fakeglucosense.core.analysis

import com.fakeglucosense.core.model.GlucoseReading
import com.fakeglucosense.core.model.Trend

/**
 * Computes glucose rate of change (mg/dL per minute) with a least-squares slope over a trailing
 * window, then maps it to a [Trend] (ADR-0005). Regression is used instead of a two-point difference
 * so one noisy reading can't flip the arrow.
 */
object TrendCalculator {
    const val WINDOW_MILLIS = 15 * 60_000L
    const val MIN_POINTS = 2

    /** Result of an estimate at a point in time. [rateMgdlPerMin] is null when not computable. */
    data class Estimate(val rateMgdlPerMin: Double?, val trend: Trend)

    /**
     * Estimates the trend at [nowMillis] from [readings]. Only readings in `(now - WINDOW, now]` count.
     */
    fun estimate(readings: List<GlucoseReading>, nowMillis: Long): Estimate {
        val windowStart = nowMillis - WINDOW_MILLIS
        val points = readings
            .asSequence()
            .filter { it.epochMillis > windowStart && it.epochMillis <= nowMillis }
            .map { (it.epochMillis - nowMillis) / 60_000.0 to it.mgdl.toDouble() }
            .toList()
        val rate = slopePerMinute(points)
        return if (rate == null) Estimate(null, Trend.NOT_COMPUTABLE) else Estimate(rate, Trend.fromRate(rate))
    }

    /**
     * Returns [readings] (sorted by time) annotated with trend and rate, each computed from the
     * readings up to and including that point. Existing trend values are replaced.
     */
    fun annotate(readings: List<GlucoseReading>): List<GlucoseReading> {
        val sorted = readings.sortedBy { it.epochMillis }
        return sorted.mapIndexed { index, reading ->
            val history = sorted.subList(0, index + 1)
            val estimate = estimate(history, reading.epochMillis)
            reading.copy(trend = estimate.trend, rateMgdlPerMin = estimate.rateMgdlPerMin)
        }
    }

    /**
     * Least-squares slope of (minutes, mg/dL) pairs. Returns null with fewer than [MIN_POINTS]
     * points or when all x values are identical.
     */
    internal fun slopePerMinute(points: List<Pair<Double, Double>>): Double? {
        if (points.size < MIN_POINTS) return null
        val n = points.size.toDouble()
        val sumX = points.sumOf { it.first }
        val sumY = points.sumOf { it.second }
        val sumXY = points.sumOf { it.first * it.second }
        val sumXX = points.sumOf { it.first * it.first }
        val denominator = n * sumXX - sumX * sumX
        if (kotlin.math.abs(denominator) < 1e-9) return null
        return (n * sumXY - sumX * sumY) / denominator
    }
}
