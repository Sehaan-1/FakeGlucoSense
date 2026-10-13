package com.fakeglucosense.companion.data.ble

import com.fakeglucosense.core.ble.CgmRecord
import com.fakeglucosense.core.model.GlucoseReading
import com.fakeglucosense.core.model.Trend
import kotlin.math.roundToInt

/**
 * Converts a decoded BLE CGM record into a domain reading. Kept free of Android types so the
 * time and unit rules are unit-tested (ADR-0002).
 *
 * The record's time offset is minutes since the session start, so the absolute time is
 * `sessionStart + offset`. Glucose is rounded to whole mg/dL. A missing trend stays
 * NOT_COMPUTABLE, and the repository may derive one from history.
 */
object CgmRecordMapper {
    private const val MILLIS_PER_MINUTE = 60_000L

    fun toReading(record: CgmRecord, sessionStartMillis: Long): GlucoseReading {
        val mgdl = record.mgdl.roundToInt().coerceIn(GlucoseReading.MIN_MGDL, GlucoseReading.MAX_MGDL)
        val rate = record.trendMgdlPerMin
        return GlucoseReading(
            epochMillis = sessionStartMillis + record.timeOffsetMinutes * MILLIS_PER_MINUTE,
            mgdl = mgdl,
            trend = rate?.let { Trend.fromRate(it) } ?: Trend.NOT_COMPUTABLE,
            rateMgdlPerMin = rate,
        )
    }
}
