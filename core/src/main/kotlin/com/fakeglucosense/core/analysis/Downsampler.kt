package com.fakeglucosense.core.analysis

import com.fakeglucosense.core.model.GlucoseReading

/**
 * Reduces a long series to a chart-sized one without hiding peaks. Each time bucket keeps its
 * minimum and maximum reading, in time order, so a 24-hour view still shows the real spikes.
 */
object Downsampler {

    /**
     * @param readings sorted by time
     * @param maxPoints upper bound on the output size (at least 2)
     */
    fun minMax(readings: List<GlucoseReading>, maxPoints: Int): List<GlucoseReading> {
        require(maxPoints >= 2) { "maxPoints must be at least 2" }
        if (readings.size <= maxPoints) return readings
        val buckets = maxPoints / 2
        val size = readings.size
        val out = ArrayList<GlucoseReading>(buckets * 2)
        for (bucket in 0 until buckets) {
            val from = bucket * size / buckets
            val to = (bucket + 1) * size / buckets
            if (from >= to) continue
            var low = readings[from]
            var high = readings[from]
            for (index in from until to) {
                val candidate = readings[index]
                if (candidate.mgdl < low.mgdl) low = candidate
                if (candidate.mgdl > high.mgdl) high = candidate
            }
            if (low === high) {
                out += low
            } else if (low.epochMillis <= high.epochMillis) {
                out += low
                out += high
            } else {
                out += high
                out += low
            }
        }
        return out
    }
}
