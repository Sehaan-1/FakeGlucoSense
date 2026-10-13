package com.fakeglucosense.core.ble

import com.fakeglucosense.core.model.AlertThresholds
import com.fakeglucosense.core.model.Trend

/**
 * Bit assignments of the Sensor Status Annunciation octets (CGMS v1.0.2). The three octets
 * are independent in a CGM Measurement record: Warning is announced by Flags bit 5,
 * Cal/Temp by bit 6, and Status by bit 7.
 *
 * The simulator only ever sets Warning bits (glucose outside the alert thresholds, rapid
 * changes) and the Status bit [STATUS_SESSION_STOPPED] (in the CGM Status characteristic).
 * Fault and calibration bits stay clear: the fake sensor has no faults and no calibration.
 *
 * Bit names follow the CGMS table as implemented by Nordic's CGMS service
 * (`nrfconnect/sdk-nrf`, `subsys/bluetooth/services/cgms`) and STM32's WBA CGM demo, both
 * recorded in docs/research/prior-art.md.
 */
object SensorStatusAnnunciation {
    // Warning-Octet (Flags bit 5).
    const val WARNING_PATIENT_LOW = 0x01
    const val WARNING_PATIENT_HIGH = 0x02
    const val WARNING_HYPO = 0x04
    const val WARNING_HYPER = 0x08
    const val WARNING_RATE_OF_DECREASE = 0x10
    const val WARNING_RATE_OF_INCREASE = 0x20
    const val WARNING_RESULT_BELOW_DEVICE_RANGE = 0x40
    const val WARNING_RESULT_ABOVE_DEVICE_RANGE = 0x80

    // Cal/Temp-Octet (Flags bit 6). All zero for the simulator.
    const val CALTEMP_TIME_SYNC_REQUIRED = 0x01
    const val CALTEMP_CALIBRATION_NOT_ALLOWED = 0x02
    const val CALTEMP_CALIBRATION_RECOMMENDED = 0x04
    const val CALTEMP_CALIBRATION_REQUIRED = 0x08
    const val CALTEMP_SENSOR_TEMPERATURE_HIGH = 0x10
    const val CALTEMP_SENSOR_TEMPERATURE_LOW = 0x20
    const val CALTEMP_CALIBRATION_PENDING = 0x40

    // Status-Octet (Flags bit 7).
    const val STATUS_SESSION_STOPPED = 0x01
    const val STATUS_DEVICE_BATTERY_LOW = 0x02
    const val STATUS_SENSOR_TYPE_INCORRECT = 0x04
    const val STATUS_SENSOR_MALFUNCTION = 0x08
    const val STATUS_DEVICE_SPECIFIC_ALERT = 0x10
    const val STATUS_GENERAL_DEVICE_FAULT = 0x20

    /**
     * The Warning-Octet for one reading, from the same thresholds the companion alerts on.
     * Zero when the reading is unremarkable, in which case the octet is not sent at all.
     */
    fun warningFor(mgdl: Double, trend: Trend, thresholds: AlertThresholds): Int {
        var bits = 0
        if (mgdl < thresholds.lowMgdl) bits = bits or WARNING_PATIENT_LOW
        if (mgdl > thresholds.highMgdl) bits = bits or WARNING_PATIENT_HIGH
        if (mgdl <= thresholds.urgentLowMgdl) bits = bits or WARNING_HYPO
        if (mgdl >= thresholds.urgentHighMgdl) bits = bits or WARNING_HYPER
        if (trend == Trend.DOUBLE_DOWN) bits = bits or WARNING_RATE_OF_DECREASE
        if (trend == Trend.DOUBLE_UP) bits = bits or WARNING_RATE_OF_INCREASE
        // The simulated sensor saturates at 40/400 mg/dL (CgmMeasurementCodec clamps).
        if (mgdl <= CgmMeasurementCodec.BELOW_RANGE_MGDL && mgdl.toInt() < thresholds.lowMgdl) {
            bits = bits or WARNING_RESULT_BELOW_DEVICE_RANGE
        }
        if (mgdl >= CgmMeasurementCodec.ABOVE_RANGE_MGDL && mgdl.toInt() > thresholds.highMgdl) {
            bits = bits or WARNING_RESULT_ABOVE_DEVICE_RANGE
        }
        return bits
    }
}
