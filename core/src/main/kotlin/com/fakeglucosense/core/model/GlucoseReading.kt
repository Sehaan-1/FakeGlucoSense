package com.fakeglucosense.core.model

/**
 * One glucose measurement. Glucose is always stored in mg/dL (Bluetooth CGMS mandates mg/dL on
 * the wire; see docs/ble-protocol.md). Time is UTC epoch milliseconds.
 *
 * @param rateMgdlPerMin rate of change in mg/dL per minute, when known (sensor-reported or computed).
 */
data class GlucoseReading(
    val epochMillis: Long,
    val mgdl: Int,
    val trend: Trend = Trend.NOT_COMPUTABLE,
    val rateMgdlPerMin: Double? = null,
) {
    init {
        require(mgdl in MIN_MGDL..MAX_MGDL) { "glucose $mgdl mg/dL is outside $MIN_MGDL..$MAX_MGDL" }
        require(rateMgdlPerMin == null || rateMgdlPerMin.isFinite()) { "rate must be finite" }
    }

    companion object {
        /** Physiological clamp for the simulator and the decoder. Values outside are sensor errors. */
        const val MIN_MGDL = 20
        const val MAX_MGDL = 600
    }
}
