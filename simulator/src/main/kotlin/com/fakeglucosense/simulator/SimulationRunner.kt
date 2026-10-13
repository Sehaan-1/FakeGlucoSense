package com.fakeglucosense.simulator

import com.fakeglucosense.core.sim.SensorSample
import com.fakeglucosense.core.sim.SimulatedSensor
import com.fakeglucosense.core.wire.ReadingJsonCodec

/** Source of "now" in epoch milliseconds. Injected so tests can control time. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}

/**
 * Drives a [SimulatedSensor] on a simulated timeline. [backfill] emits the recent past at once.
 * [tick] advances the timeline by one sensor interval, so the caller controls real-time pacing
 * (speed factor and delay) and tests stay fast.
 */
class SimulationRunner(
    private val sensor: SimulatedSensor,
    private val publish: (String) -> Unit,
    private val onSample: (SensorSample) -> Unit = {},
) {
    private var simulatedNow: Long = sensor.sessionStartMillis

    /** Emits all readings from session start up to [untilMillis]. */
    fun backfill(untilMillis: Long): Int {
        simulatedNow = untilMillis
        return emit(sensor.advanceTo(untilMillis))
    }

    /** Advances the timeline by one interval and emits any readings that fall due. */
    fun tick(): Int {
        simulatedNow += sensor.intervalMillis
        return emit(sensor.advanceTo(simulatedNow))
    }

    private fun emit(samples: List<SensorSample>): Int {
        for (sample in samples) {
            publish(ReadingJsonCodec.encode(sample.reading, sample.sequence))
            onSample(sample)
        }
        return samples.size
    }

    companion object {
        /** Session start aligned to the interval grid, so readings land on :00, :05, :10 and so on. */
        fun alignedSessionStart(nowMillis: Long, intervalMillis: Long, backfillMillis: Long): Long {
            val aligned = Math.floorDiv(nowMillis, intervalMillis) * intervalMillis
            return aligned - backfillMillis
        }
    }
}
