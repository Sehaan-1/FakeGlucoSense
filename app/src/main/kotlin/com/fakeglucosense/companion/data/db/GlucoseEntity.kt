package com.fakeglucosense.companion.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One stored reading. The primary key is the sample time, so a reading replayed by the
 * simulator (backfill, reconnect) is ignored rather than duplicated (ADR-0006).
 */
@Entity(tableName = "glucose_readings")
data class GlucoseEntity(
    @PrimaryKey val epochMillis: Long,
    val mgdl: Int,
    /** [com.fakeglucosense.core.model.Trend.name]. */
    val trend: String,
    val rateMgdlPerMin: Double?,
    /** Where the reading came from, for example "simulator" or "ble". */
    val source: String,
)
