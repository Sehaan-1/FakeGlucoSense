package com.fakeglucosense.sensor.sim

import com.fakeglucosense.core.ble.CgmRecord
import com.fakeglucosense.core.ble.SensorStatusAnnunciation
import com.fakeglucosense.core.ble.StatusAnnunciation
import com.fakeglucosense.core.model.AlertThresholds
import com.fakeglucosense.core.sim.ScenarioPreset
import com.fakeglucosense.core.sim.SimulatedSensor
import kotlin.math.roundToLong

/**
 * One CGM session: a core [SimulatedSensor] plus a bounded history of BLE records, so a central
 * that connects late can be sent the full recent window (the CGM backfill described in
 * docs/ble-protocol.md).
 *
 * Simulated time is passed in by the caller. Keeping the clock outside makes the session
 * deterministic and testable.
 */
class SimulatedCgmSession(
    scenario: ScenarioPreset,
    seed: Long,
    val sessionStartMillis: Long,
    private val historyLimit: Int = DEFAULT_HISTORY,
) {
    private val sensor = SimulatedSensor(
        profile = scenario.profile,
        seed = seed,
        sessionStartMillis = sessionStartMillis,
        intervalMinutes = INTERVAL_MINUTES,
    )
    private val history = ArrayDeque<CgmRecord>()

    /** Generates every reading due up to [simNowMillis] and returns the new records in order. */
    fun advanceTo(simNowMillis: Long): List<CgmRecord> {
        val fresh = sensor.advanceTo(simNowMillis).mapNotNull { sample ->
            val offset = sample.timeOffsetMinutes
            // The model keeps Long offsets so it can run past the field. The BLE Time Offset is UINT16
            // minutes (about 45 days). Samples past that are dropped here, and the service ends the
            // session first via isExpired().
            if (offset !in 0..CgmRecord.MAX_TIME_OFFSET) return@mapNotNull null
            val annunciation = StatusAnnunciation(
                warning = SensorStatusAnnunciation.warningFor(
                    mgdl = sample.reading.mgdl.toDouble(),
                    trend = sample.reading.trend,
                    thresholds = THRESHOLDS,
                ),
            )
            CgmRecord(
                timeOffsetMinutes = offset.toInt(),
                mgdl = sample.reading.mgdl.toDouble(),
                trendMgdlPerMin = sample.reading.rateMgdlPerMin,
                statusAnnunciation = annunciation.takeIf { !it.isEmpty },
            )
        }
        fresh.forEach { record ->
            history.addLast(record)
            while (history.size > historyLimit) history.removeFirst()
        }
        return fresh
    }

    /** Recent records, oldest first, for the backfill sent when a central subscribes. */
    fun backfill(): List<CgmRecord> = history.toList()

    /** Whether [simNowMillis] is past the last Time Offset a UINT16 can carry. */
    fun isExpired(simNowMillis: Long): Boolean {
        val minutes = (simNowMillis - sessionStartMillis) / MILLIS_PER_MINUTE
        return minutes > CgmRecord.MAX_TIME_OFFSET
    }

    companion object {
        const val INTERVAL_MINUTES = 5
        const val DEFAULT_HISTORY = 288 + 12 // 24 hours plus a margin, matching the TCP simulator.
        private const val MILLIS_PER_MINUTE = 60_000L

        /** The sensor-side alert thresholds, matching the companion's defaults (ADR-0014). */
        val THRESHOLDS = AlertThresholds()

        /** Start aligned to the 5-minute grid, [backfillHours] before [nowMillis]. */
        fun alignedStart(nowMillis: Long, backfillHours: Int): Long {
            val grid = INTERVAL_MINUTES * MILLIS_PER_MINUTE
            val aligned = (nowMillis / grid) * grid
            return aligned - backfillHours * 60L * MILLIS_PER_MINUTE
        }

        /** Simulated time for a wall-clock delta at the given speed. Used by the service loop. */
        fun scaledMillis(elapsedWallMillis: Long, speed: Double): Long = (elapsedWallMillis * speed).roundToLong()
    }
}
