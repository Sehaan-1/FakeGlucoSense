package com.fakeglucosense.core.ble

/**
 * A decoded CGM Measurement record.
 *
 * @param timeOffsetMinutes minutes since the CGM session start. The spec also uses this as a
 *   sequence number, so gaps in offsets reveal missed records.
 * @param mgdl glucose in mg/dL, already mapped from SFLOAT special values (see [CgmMeasurementCodec]).
 * @param trendMgdlPerMin sensor-reported rate of change, present only when the Flags trend bit is set.
 * @param statusAnnunciation the Sensor Status Annunciation octets, when the record carried any.
 *   Zero octets are not sent on the wire and stay zero here.
 */
data class CgmRecord(
    val timeOffsetMinutes: Int,
    val mgdl: Double,
    val trendMgdlPerMin: Double? = null,
    val statusAnnunciation: StatusAnnunciation? = null,
) {
    init {
        require(timeOffsetMinutes in 0..MAX_TIME_OFFSET) { "time offset $timeOffsetMinutes out of UINT16 range" }
        require(mgdl.isFinite()) { "record glucose must be finite" }
    }

    companion object {
        const val MAX_TIME_OFFSET = 0xFFFF
    }
}
