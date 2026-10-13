package com.fakeglucosense.core.sim

import com.fakeglucosense.core.analysis.TrendCalculator
import com.fakeglucosense.core.model.GlucoseReading
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * One output of a simulated sensor.
 *
 * @param sequence index of the reading within the session (0-based). Also the BLE Time Offset when
 *   multiplied by the interval.
 * @param timeOffsetMinutes minutes since the session start, matching the CGM Time Offset field.
 */
data class SensorSample(
    val reading: GlucoseReading,
    val sequence: Long,
    val timeOffsetMinutes: Long,
)

/**
 * A simulated CGM sensor. It produces one reading every [intervalMinutes] minutes on a fixed grid
 * anchored at [sessionStartMillis], and computes each reading's trend from its own output (ADR-0005).
 *
 * The same seed, profile, and start time always produce the same series, regardless of how
 * [advanceTo] calls are chunked.
 */
class SimulatedSensor(
    val profile: SimulationProfile,
    seed: Long,
    val sessionStartMillis: Long,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    zone: ZoneId = ZoneOffset.UTC,
) {
    init {
        require(intervalMinutes in 1..60) { "interval must be 1..60 minutes" }
    }

    val intervalMillis: Long = intervalMinutes * MILLIS_PER_MINUTE

    private val random = Random(seed)
    private val physiology = GlucosePhysiology(
        profile = profile,
        random = random,
        startMillis = sessionStartMillis,
        zone = zone,
        planner = MealPlanner(profile, seed, zone),
    )
    private val recent = ArrayDeque<GlucoseReading>()
    private var nextSequence = 0L

    /**
     * Emits every reading due at or before [untilMillis] that hasn't been emitted yet. Calling it
     * again with the same time returns an empty list.
     */
    fun advanceTo(untilMillis: Long): List<SensorSample> {
        val out = mutableListOf<SensorSample>()
        while (true) {
            val due = sessionStartMillis + nextSequence * intervalMillis
            if (due > untilMillis) break
            physiology.advanceTo(due)
            val mgdl = physiology.sensorValue().roundToInt().coerceIn(MIN_MGDL, MAX_MGDL)
            val reading = withTrend(GlucoseReading(epochMillis = due, mgdl = mgdl))
            out += SensorSample(
                reading = reading,
                sequence = nextSequence,
                timeOffsetMinutes = nextSequence * intervalMinutes,
            )
            nextSequence++
        }
        return out
    }

    private fun withTrend(reading: GlucoseReading): GlucoseReading {
        recent += reading
        recent.removeAll { reading.epochMillis - it.epochMillis > TrendCalculator.WINDOW_MILLIS }
        val estimate = TrendCalculator.estimate(recent.toList(), reading.epochMillis)
        return reading.copy(trend = estimate.trend, rateMgdlPerMin = estimate.rateMgdlPerMin)
    }

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 5
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MIN_MGDL = 40
        private const val MAX_MGDL = 400
    }
}
